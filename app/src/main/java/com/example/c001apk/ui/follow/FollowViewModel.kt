package com.example.c001apk.ui.follow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.c001apk.adapter.FooterState
import com.example.c001apk.adapter.LoadingState
import com.example.c001apk.constant.Constants.LOADING_EMPTY
import com.example.c001apk.constant.Constants.LOADING_END
import com.example.c001apk.constant.Constants.LOADING_FAILED
import com.example.c001apk.logic.repository.BlackListRepo
import com.example.c001apk.logic.repository.HistoryFavoriteRepo
import com.example.c001apk.logic.repository.NetworkRepo
import com.example.c001apk.ui.base.BaseAppViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import com.example.c001apk.util.ApiError

class FollowViewModel @AssistedInject constructor(
    @Assisted("uid") var uid: String,
    @Assisted("type") val type: String,
    blackListRepo: BlackListRepo,
    historyRepo: HistoryFavoriteRepo,
    networkRepo: NetworkRepo
) : BaseAppViewModel(blackListRepo, historyRepo, networkRepo) {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("uid") uid: String,
            @Assisted("type") type: String
        ): FollowViewModel
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        /**
         * 这几个 type 都是「某人的内容列表」，整页按完整动态卡排（见 [feedCoverAsFeed]）。
         */
        private val FULL_CARD_LISTS = setOf("feed", "like", "reply", "replyToMe", "recentHistory")

        fun provideFactory(
            assistedFactory: Factory,
            uid: String,
            type: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return assistedFactory.create(uid, type) as T
            }
        }
    }

    /**
     * 用户内容列表里的文章（entityTemplate=feedCover）也按完整动态卡渲染。
     *
     * 「我的动态」实测在同一个列表里混下发两种模板（文章是 feedCover、普通动态是 feed），
     * 照模板分派会让文章变成一张没有头像 / 用户名的紧凑封面卡，要求它跟同页动态长一样，
     * 所以整页统一走完整卡（开关见 AppAdapter.feedCoverAsFeed）。
     * 首页 / 教程页 / 数码首页 / 产品页晒单那些发现流仍然用紧凑封面卡，不受这里影响。
     */
    override val feedCoverAsFeed: Boolean get() = type in FULL_CARD_LISTS

    val tabList = ArrayList<String>()
    var title: String? = null
    var url: String? = null

    override fun fetchData() {
        if (url.isNullOrEmpty()) {
            initUrl()
        }
        when (type) {
            "feed", "fans", "follow", "apk", "reply", "replyToMe", "recentHistory", "like" -> fetchFeedList()

            "topic", "product", "favorite" -> fetchTopicData()
        }
    }

    private fun fetchFeedList() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getFollowList(url.toString(), uid, page, lastItem)
                .onStart {
                    if (isLoadMore) {
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.Loading)
                        else
                            footerState.postValue(FooterState.Loading)
                    }
                }
                .collect { result ->
                    val list = dataList.value?.toMutableList() ?: ArrayList()
                    val feed = result.getOrNull()
                    if (feed != null) {
                        if (!feed.message.isNullOrEmpty()) {
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingError(feed.message))
                            else
                                footerState.postValue(FooterState.LoadingError(feed.message))
                            return@collect
                        } else if (!feed.data.isNullOrEmpty()) {
                            lastItem = feed.data.last().id
                            if (isRefreshing) list.clear()
                            if (isRefreshing || isLoadMore) {
                                feed.data.forEach {
                                    if (it.entityType in listOf(
                                            "feed", "contacts", "apk", "feed_reply", "recentHistory"
                                        )
                                    )
                                        if (!blackListRepo.checkUid(it.userInfo?.uid.toString())
                                            && !blackListRepo.checkTopic(
                                                it.tags + it.ttitle + it.relationRows?.getOrNull(0)?.title
                                            )
                                        ) {
                                            if (it.entityType == "feed" && !it.replyRows.isNullOrEmpty()) {
                                                if (!blackListRepo.checkUid(
                                                        it.replyRows?.getOrNull(0)?.uid.toString()
                                                    )
                                                ) {
                                                    it.replyRows?.getOrNull(0)?.let { reply ->
                                                        val pic =
                                                            if (reply.picArr.isNullOrEmpty()) ""
                                                            else " ${if (reply.message == "[图片]") "" else "[图片] "}<a class=\"feed-forward-pic\" href=${reply.pic}>查看图片(${reply.picArr.size})</a>"

                                                        reply.message =
                                                            "<a class=\"feed-link-uname\" href=\"/u/${reply.uid}\">${reply.userInfo?.username}</a>: ${reply.message}$pic"
                                                    }
                                                } else it.replyRows = null
                                            }
                                            list.add(it)
                                        }

                                }
                            }
                            page++
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingDone)
                            else
                                footerState.postValue(FooterState.LoadingDone)
                            dataList.postValue(list)
                        } else if (feed.data?.isEmpty() == true) {
                            isEnd = true
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingFailed(LOADING_EMPTY))
                            else {
                                if (isRefreshing)
                                    dataList.postValue(emptyList())
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

    private fun initUrl() {
        url = when (type) {
            "feed" -> "/v6/user/feedList?showAnonymous=0&isIncludeTop=1"
            "follow" -> "/v6/user/followList"
            "fans" -> "/v6/user/fansList"
            "apk" -> {
                uid = ""
                "/v6/user/apkFollowList"
            }

            "forum" -> {
                uid = ""
                "/v6/user/forumFollowList"
            }

            "like" -> "/v6/user/likeList"

            "reply" -> "/v6/user/replyList"

            "replyToMe" -> "/v6/user/replyToMeList"

            "recentHistory" -> "/v6/user/recentHistoryList"

            else -> type
        }
    }

    private fun fetchTopicData() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getDataList(url.toString(), title.toString(), null, lastItem, page)
                .onStart {
                    if (isLoadMore) {
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.Loading)
                        else
                            footerState.postValue(FooterState.Loading)
                    }
                }
                .collect { result ->
                    val list = dataList.value?.toMutableList() ?: ArrayList()
                    val data = result.getOrNull()
                    if (data != null) {
                        if (!data.message.isNullOrEmpty()) {
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingError(data.message))
                            else
                                footerState.postValue(FooterState.LoadingError(data.message))
                            return@collect
                        } else if (!data.data.isNullOrEmpty()) {
                            lastItem = data.data.last().id
                            if (isRefreshing)
                                list.clear()
                            if (isRefreshing || isLoadMore) {
                                data.data.forEach {
                                    if (it.entityType in listOf("feed", "topic", "product", "user"))
                                        if (!blackListRepo.checkUid(it.userInfo?.uid.toString())
                                            && !blackListRepo.checkTopic(
                                                it.tags + it.ttitle + it.relationRows?.getOrNull(0)?.title
                                            )
                                        )
                                            list.add(it)
                                }
                            }
                            page++
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingDone)
                            else
                                footerState.postValue(FooterState.LoadingDone)
                            dataList.postValue(list)
                        } else if (data.data?.isEmpty() == true) {
                            isEnd = true
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingFailed(LOADING_EMPTY))
                            else {
                                if (isRefreshing)
                                    dataList.postValue(emptyList())
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