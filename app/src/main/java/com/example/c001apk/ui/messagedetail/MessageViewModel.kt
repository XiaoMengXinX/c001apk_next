package com.example.c001apk.ui.messagedetail

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.c001apk.adapter.FooterState
import com.example.c001apk.adapter.LoadingState
import com.example.c001apk.constant.Constants.LOADING_EMPTY
import com.example.c001apk.constant.Constants.LOADING_END
import com.example.c001apk.constant.Constants.LOADING_FAILED
import com.example.c001apk.logic.model.MessageResponse
import com.example.c001apk.logic.repository.BlackListRepo
import com.example.c001apk.logic.repository.NetworkRepo
import com.example.c001apk.ui.base.BaseViewModel
import com.example.c001apk.util.CookieUtil
import com.example.c001apk.util.MessageCenterSeenStore
import com.example.c001apk.util.MessageKit
import com.example.c001apk.util.NotificationV18Kit
import com.example.c001apk.util.UnreadCounter
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import com.example.c001apk.util.ApiError

class MessageViewModel @AssistedInject constructor(
    @Assisted val type: String,
    private val blackListRepo: BlackListRepo,
    private val networkRepo: NetworkRepo
) : BaseViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(type: String): MessageViewModel
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        fun provideFactory(
            assistedFactory: Factory,
            type: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return assistedFactory.create(type) as T
            }
        }
    }

    var url: String? = null
    val footerState = MutableLiveData<FooterState>()
    val messageListData = MutableLiveData<List<MessageResponse.Data>>()

    /**
     * 本机账本里的分类名（null = 不参与「看过即已读」）。
     * 私信（list）的未读是会话级的、分类页自己有已读逻辑，不算进来。
     */
    private val seenCategory: String? = when (type) {
        "atMe", "atCommentMe", "feedLike", "contactsFollow", "secretary" -> type
        // 「我的回复」跟消息中心汇总列表里的 commentMe 是同一份账本，分类名要对齐，
        // 否则从宫格进去看完，汇总列表那边的红点不会消（反之亦然）
        "commentMe" -> UnreadCounter.COMMENT_ME
        else -> null
    }

    /** 该分类服务端未读数：服务端未读 = 这个分类最新的 N 条 */
    private val seenTarget: Int
        get() = when (type) {
            "atMe" -> CookieUtil.atme ?: 0
            "atCommentMe" -> CookieUtil.atcommentme ?: 0
            "feedLike" -> CookieUtil.feedlike ?: 0
            "contactsFollow" -> CookieUtil.contacts_follow ?: 0
            "commentMe" -> CookieUtil.commentme ?: 0
            "secretary" -> CookieUtil.notification
            else -> 0
        }

    /**
     * 分类页看过的未读也算已读：第一页开头的 N 条（N = 服务端该分类未读数）就是未读，
     * 记进本机账本后，消息中心的宫格红点回来时才会消掉。
     */
    private fun markSeen(data: List<MessageResponse.Data>) {
        val category = seenCategory ?: return
        val target = seenTarget
        if (target <= 0) return
        val added = MessageCenterSeenStore.markSeen(category, data.take(target).map { it.id })
        MessageCenterSeenStore.addSeenCount(category, added)
    }

    override fun fetchData() {
        if (url.isNullOrEmpty())
            when (type) {
                "atMe" -> url = "/v6/notification/atMeList"
                "atCommentMe" -> url = "/v6/notification/atCommentMeList"
                // 「我收到的赞」也换到 V18 那条流：老接口下发的正文是**被赞的动态**、
                // 点赞人藏在 likeUsername 里，渲染侧漏读一处，整页就显示成「我自己的动态」；
                // V18 的 likeList 把点赞人直接放在 from_* 上（NetworkRepo 负责归一化）。
                "feedLike" -> url = NotificationV18Kit.LIKE_URL
                "contactsFollow" -> url = "/v6/notification/contactsFollowList"
                // 「我的回复」（别人回复我的评论）走官方现在在用的 V18 统一通知流：
                // 老 /v6/notification/list 一页 20 条里 15 条是小秘书、而且一条
                // 「回复了你的评论」都没有。NetworkRepo 会按端点做归一化和类型过滤。
                "commentMe" -> url = NotificationV18Kit.URL
                "list" -> url = "/v6/message/list"
                // 小秘书不是私信对象：它的登录提醒 / 站内信都在通知列表里（type=notify_xms），
                // /v6/message/list 里根本没有它，见 MessageKit.isSecretaryNotify
                "secretary" -> url = "/v6/notification/list"

            }
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getMessage(url.toString(), page, lastItem)
                .onStart {
                    if (isLoadMore) {
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.Loading)
                        else
                            footerState.postValue(FooterState.Loading)
                    }
                }
                .collect { result ->
                    val messageList = messageListData.value?.toMutableList() ?: ArrayList()
                    val feed = result.getOrNull()
                    if (feed != null) {
                        if (!feed.message.isNullOrEmpty()) {
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingError(feed.message))
                            else
                                footerState.postValue(FooterState.LoadingError(feed.message))
                            // 这条出口在尾部复位之前就 return 了，接口一回错误信息，
                            // isRefreshing 就永远是真的、翻页再也发不出去（同 FeedViewModel）
                            isRefreshing = false
                            isLoadMore = false
                            return@collect
                        } else if (!feed.data.isNullOrEmpty()) {
                            lastItem = feed.data.last().id
                            // 只有第一页开头才是未读区间，翻页的不算
                            if (page == 1) markSeen(feed.data)
                            if (isRefreshing) messageList.clear()
                            if (isRefreshing || isLoadMore) {
                                feed.data.forEach {
                                    // "message" 是私信会话（/v6/message/list）的 entityType，
                                    // 漏掉它会让私信列表整个空白；
                                    // "notificationV18" 是 V18 通知流的（老接口的通知是 "notification"），
                                    // 漏掉它「我的回复」会整页空白
                                    if (it.entityType !in listOf(
                                            "feed", "feed_reply", "notification",
                                            "message", NotificationV18Kit.ENTITY_TYPE
                                        )
                                    ) return@forEach
                                    // 小秘书列表只要它自己的通知：服务端不认 type / fromuid 过滤参数
                                    // （实测照旧返回全部 20 条），只能在这儿本地筛
                                    if (type == "secretary" && !MessageKit.isSecretaryNotify(it))
                                        return@forEach
                                    if (!blackListRepo.checkUid(it.uid))
                                        messageList.add(it)
                                }
                            }
                            page++
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingDone)
                            else
                                footerState.postValue(FooterState.LoadingDone)
                            messageListData.postValue(messageList)
                        } else if (feed.data?.isEmpty() == true) {
                            isEnd = true
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingFailed(LOADING_EMPTY))
                            else {
                                if (isRefreshing)
                                    messageListData.postValue(emptyList())
                                footerState.postValue(FooterState.LoadingEnd(LOADING_END))
                            }
                        }
                    } else {
                        isEnd = true
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.LoadingFailed(ApiError.textOf(result.exceptionOrNull())))
                        else
                            footerState.postValue(FooterState.LoadingError(ApiError.textOf(result.exceptionOrNull())))
                        result.exceptionOrNull()?.printStackTrace()
                    }
                    isRefreshing = false
                    isLoadMore = false
                }
        }

    }


}