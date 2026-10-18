package com.example.c001apk.ui.feed

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.c001apk.adapter.FooterState
import com.example.c001apk.adapter.LoadingState
import com.example.c001apk.constant.Constants.LOADING_END
import com.example.c001apk.constant.Constants.LOADING_FAILED
import com.example.c001apk.logic.model.CollectionAction
import com.example.c001apk.logic.model.FeedArticleContentBean
import com.example.c001apk.logic.model.HomeFeedResponse
import com.example.c001apk.logic.model.TotalReplyResponse
import com.example.c001apk.logic.repository.BlackListRepo
import com.example.c001apk.logic.repository.HistoryFavoriteRepo
import com.example.c001apk.logic.repository.NetworkRepo
import com.example.c001apk.logic.repository.OfflineCacheRepo
import com.example.c001apk.ui.base.BaseAppViewModel
import com.example.c001apk.util.Event
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.c001apk.util.ApiError

@HiltViewModel(assistedFactory = FeedViewModel.Factory::class)
class FeedViewModel @AssistedInject constructor(
    @Assisted("id") val id: String,
    @Assisted("frid") var frid: String?,
    @Assisted("viewReply") var isViewReply: Boolean,
    /**
     * 离线详情：正文和评论全部读本机存档（[OfflineCacheRepo]），一个请求都不发。
     *
     * 走这条路径时原帖可能已经被删、设备也可能没网，所以除渲染以外的一切都得关掉
     * （点赞 / 收藏 / 回复 / 加载更多 / 下拉刷新，见 FeedFragment 里的守卫）。
     */
    @Assisted("offline") val isOffline: Boolean,
    blackListRepo: BlackListRepo,
    historyRepo: HistoryFavoriteRepo,
    networkRepo: NetworkRepo,
    private val offlineCacheRepo: OfflineCacheRepo
) : BaseAppViewModel(blackListRepo, historyRepo, networkRepo) {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("id") id: String,
            @Assisted("frid") frid: String?,
            @Assisted("viewReply") isViewReply: Boolean,
            @Assisted("offline") isOffline: Boolean
        ): FeedViewModel
    }

    var isAInit = true
    var position: Int? = null
    var rPosition: Int? = null
    var ruid: String? = null
    var cuid: String? = null
    var uname: String? = null
    var type: String? = null
    var isRefreshReply: Boolean? = null
    private var blockStatus = 0
    var fromFeedAuthor = 0
    private var discussMode: Int = 1
    var listType: String = "lastupdate_desc"
    var firstItem: String? = null
    var itemCount = 2
    var feedUid: String? = null
    var funame: String? = null
    var avatar: String? = null
    var device: String? = null

    // 认证角标：挂在顶栏头像右下角，uid 复用 feedUid
    var verifyIcon: String? = null
    var verifyStatus: Int? = null
    var replyCount: String? = null
    var dateLine: Long? = null
    /**
     * 当前置顶回复的 id（服务端只保留一个）。除了刷新时过滤重复项，评论菜单也要读它
     * 决定「置顶 / 取消置顶」哪个文案，所以是公开的。
     */
    var topReplyId: String? = null
    private var replyMeId: String? = null
    private var isTop: Boolean? = null
    var feedType: String? = null

    var rid: String? = null
    var feedTypeName: String? = null

    var feedDataList: MutableList<HomeFeedResponse.Data>? = null
    var articleList: MutableList<FeedArticleContentBean.Data>? = null

    /**
     * 图文详情的头部项数据（作者行 + 封面）在 adapter 里的位置和 [feedDataList] 的首项等价：
     * 动态把作者行并进内容卡，图文则单独铺一项在最上面。非图文时为 null。
     */
    var articleHeader: HomeFeedResponse.Data? = null
    var articleMsg: String? = null
    var articleDateLine: Long? = null
    private val feedTopReplyList = ArrayList<TotalReplyResponse.Data>()

    val feedReplyData = MutableLiveData<List<TotalReplyResponse.Data>>()
    val feedUserState = MutableLiveData<Event<Boolean>>()

    /** 收藏回写（见 [onFavoriteChanged]）：要整条重绑，不能混进 feedUserState 的 payload 分支 */
    val feedFavState = MutableLiveData<Event<Boolean>>()

    fun onFollowUnFollow(url: String, uid: String, followAuthor: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.postFollowUnFollow(url, uid)
                .collect { result ->
                    val response = result.getOrNull()
                    if (response != null) {
                        if (response.message != null) {
                            toastText.postValue(Event(response.message))
                        } else {
                            val newState = if (followAuthor == 1) 0 else 1
                            feedDataList?.getOrNull(0)?.userAction?.followAuthor = newState
                            // 图文没有 feedDataList，作者行挂在 articleHeader（同一份 feedData）上
                            feedData?.userAction?.followAuthor = newState
                            feedUserState.postValue(Event(true))
                        }
                    } else {
                        result.exceptionOrNull()?.printStackTrace()
                    }
                }
        }
    }

    fun onLikeReply(id: String, isLike: Int) {
        val likeType = if (isLike == 1) "unLikeReply" else "likeReply"
        val likeUrl = "/v6/feed/$likeType"
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.postLikeReply(likeUrl, id)
                .collect { result ->
                    val response = result.getOrNull()
                    if (response != null) {
                        if (response.data != null) {
                            val currentList = feedReplyData.value?.map {
                                if (it.id == id) {
                                    it.copy(
                                        likenum = response.data,
                                        userAction = it.userAction?.copy(like = if (isLike == 1) 0 else 1)
                                    )
                                } else it
                            } ?: emptyList()
                            feedReplyData.postValue(currentList)
                        } else {
                            response.message?.let {
                                toastText.postValue(Event(it))
                            }
                        }
                    } else {
                        result.exceptionOrNull()?.printStackTrace()
                    }
                }
        }
    }

    fun fetchFeedReply() {
        if (isOffline) {
            // 存档里的评论在建 Fragment 之前就装好了（见 loadOffline），这里只负责把
            // 分页标志收干净：不下拉、不加载更多，Footer 直接收尾
            feedReplyData.postValue(offlineReplyList ?: emptyList())
            isRefreshing = false
            isLoadMore = false
            isEnd = true
            footerState.postValue(FooterState.LoadingEnd(LOADING_END))
            return
        }
        // 详情和评论首屏是并发发的：详情页"列表项直出首屏"那条路径里 Activity 不等详情就先建了
        // Fragment，于是评论请求和详情请求同时在飞。而 isRefreshing / isLoadMore 是**共用**的分页
        // 标志，只要半路有人把它俩清掉（详情请求曾经就会，见 fetchFeedData），评论回来时就判到自己
        // "既不是刷新也不是加载更多"，整批数据被丢掉——表现是进页面评论区空着，下拉刷新才有。
        // 起请求时把这次的意图固定下来，回调里不再读共享标志，谁清都不影响这一批数据。
        val isRefreshRequest = isRefreshing
        val isLoadMoreRequest = isLoadMore
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getFeedContentReply(
                id, listType, page, firstItem, lastItem, discussMode,
                feedType.toString(), blockStatus, fromFeedAuthor
            )
                .onStart {
                    if (isLoadMoreRequest)
                        footerState.postValue(FooterState.Loading)
                }
                .collect { result ->
                    // 复位放在最前面，别放尾部：下面每个 return@collect 都是一个出口，
                    // 漏掉一次（接口回 message 那种）isRefreshing 就永远卡在 true，
                    // loadMore 的守卫一直被挡住 —— 滚到底再也不会加载，只能下拉刷新
                    isRefreshing = false
                    isLoadMore = false
                    val feedReplyList = feedReplyData.value?.toMutableList() ?: ArrayList()
                    val data = result.getOrNull()
                    if (data != null) {
                        if (data.message != null) {
                            footerState.postValue(FooterState.LoadingError(data.message))
                            return@collect
                        } else if (!data.data.isNullOrEmpty()) {
                            if (firstItem == null)
                                firstItem = data.data.first().id
                            lastItem = data.data.last().id
                            if (isRefreshRequest) {
                                feedReplyList.clear()
                                if (listType == "lastupdate_desc" && feedTopReplyList.isNotEmpty())
                                    feedReplyList.addAll(feedTopReplyList)
                            }
                            if (isRefreshRequest || isLoadMoreRequest) {
                                data.data.forEach { reply ->
                                    if (reply.entityType == "feed_reply") {
                                        if (listType == "lastupdate_desc"
                                            && reply.id in listOf(topReplyId, replyMeId)
                                        )
                                            return@forEach
                                        if (!blackListRepo.checkUid(reply.uid)) {
                                            // reply tag
                                            val unameTag =
                                                when (reply.uid) {
                                                    feedUid -> " [楼主]"
                                                    else -> ""
                                                }
                                            reply.username = "${reply.username}$unameTag"

                                            if (!reply.replyRows.isNullOrEmpty()) {
                                                reply.replyRows = reply.replyRows?.filter {
                                                    !blackListRepo.checkUid(it.uid)
                                                }?.map {
                                                    it.copy(
                                                        message = generateMess(
                                                            it,
                                                            feedUid,
                                                            reply.uid
                                                        )
                                                    )
                                                }?.toMutableList()
                                            }
                                            feedReplyList.add(reply)
                                        }
                                    }
                                }
                            }
                            page++
                            feedReplyData.postValue(feedReplyList)
                            footerState.postValue(FooterState.LoadingDone)
                        } else if (data.data?.isEmpty() == true) {
                            isEnd = true
                            if (isRefreshRequest)
                                feedReplyData.postValue(emptyList())
                            footerState.postValue(FooterState.LoadingEnd(LOADING_END))
                        }
                    } else {
                        footerState.postValue(FooterState.LoadingError(ApiError.textOf(result.exceptionOrNull())))
                        result.exceptionOrNull()?.printStackTrace()
                    }
                }
        }
    }


    var feedData: HomeFeedResponse.Data? = null

    /**
     * 详情那份数据的落点：动态是 `feedDataList[0]`，图文是 `articleHeader`（和 `feedData` 同一份对象）。
     * 改 likenum / favnum / userAction 这类字段都从这里取——只写 feedDataList，图文下是空改。
     */
    private fun currentFeedData(): HomeFeedResponse.Data? =
        feedDataList?.getOrNull(0) ?: articleHeader ?: feedData

    /**
     * 首屏是列表项直出的（详情还没回来）。列表项不下的 `userAction.followAuthor` 等字段
     * 在 [fetchFeedData] 回来前一律当"未知"，由 UI 转圈占位，不能按默认值渲染。
     */
    var isPreview = false

    /** 详情回填后通知 Fragment 换掉首屏列表（adapter 持有的是旧 list 引用） */
    val feedDataUpdateState = MutableLiveData<Event<Boolean>>()

    fun fetchFeedData() {
        if (isOffline) {
            viewModelScope.launch(Dispatchers.IO) { loadOffline() }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getFeedContent(id, frid)
                .collect { result ->
                    // 这一支**不动** isRefreshing / isLoadMore：它俩是评论分页的标志，详情请求跟分页
                    // 没关系。而详情和评论首屏是并发发的（列表项直出首屏那条路径），详情回来顺手把
                    // 标志清掉，评论请求就会判到自己"既不是刷新也不是加载更多"整批丢掉；预取那类
                    // 守卫也会误判成"没人在请求"而重复发一页
                    val feed = result.getOrNull()
                    if (feed != null) {
                        if (feed.message != null) {
                            activityState.postValue(LoadingState.LoadingError(feed.message))
                            return@collect
                        } else if (feed.data != null) {
                            feedData = feed.data
                            isPreview = false
                            handleFeedData()
                            activityState.postValue(LoadingState.LoadingDone)
                            feedDataUpdateState.postValue(Event(true))
                        }
                    } else {
                        activityState.postValue(LoadingState.LoadingFailed(ApiError.textOf(result.exceptionOrNull())))
                        result.exceptionOrNull()?.printStackTrace()
                    }
                }
        }
    }

    /** 离线存档里的评论（[isOffline] 时才非空），展示态装饰在 [loadOffline] 里一次做完 */
    private var offlineReplyList: List<TotalReplyResponse.Data>? = null

    /**
     * 离线详情：正文和置顶评论取详情存档，前 20 条评论取评论存档，全程不发请求。
     *
     * 存档里存的是接口原样 JSON（图片早已换成本机地址），解出来直接喂给 [handleFeedData]，
     * 和在线共用同一条渲染路径 —— 断网、原帖被删都不影响。
     */
    private suspend fun loadOffline() {
        val entity = offlineCacheRepo.get(id)
        val data = entity?.let { offlineCacheRepo.feedDataOf(it) }
        if (entity == null || data == null) {
            activityState.postValue(LoadingState.LoadingError("离线缓存已损坏或已被删除"))
            return
        }
        feedData = data
        isPreview = false
        handleFeedData()
        // 补 [楼主] 标记要读 feedUid，所以必须排在 handleFeedData 之后
        offlineReplyList = offlineCacheRepo.replyListOf(entity).mapNotNull { decorateReply(it) }
        activityState.postValue(LoadingState.LoadingDone)
        feedDataUpdateState.postValue(Event(true))
    }

    /**
     * 把存档里的评论回放成展示态：补 `[楼主]`、套 [generateMess] 补楼中楼的层主/楼主标记，
     * 并且照在线那套过滤黑名单、掐掉已经在置顶项里的那一条。
     *
     * 存档存的是接口原始数据（评论是请求回来就直接落盘的，没过在线那套装饰），
     * 所以这些都得在这里补一遍，否则离线看评论区会缺标记、置顶项还会重一份。
     *
     * 挂起是因为黑名单要查本机库（[com.example.c001apk.logic.repository.BlackListRepo.checkUid]）。
     */
    private suspend fun decorateReply(reply: TotalReplyResponse.Data): TotalReplyResponse.Data? {
        if (reply.entityType != "feed_reply") return null
        if (reply.id == topReplyId || reply.id == replyMeId) return null
        if (blackListRepo.checkUid(reply.uid)) return null

        reply.username = "${reply.username}${if (reply.uid == feedUid) " [楼主]" else ""}"
        if (!reply.replyRows.isNullOrEmpty()) {
            reply.replyRows = reply.replyRows
                ?.filter { !blackListRepo.checkUid(it.uid) }
                ?.map { it.copy(message = generateMess(it, feedUid, reply.uid)) }
                ?.toMutableList()
        }
        return reply
    }

    private fun generateMess(
        reply: TotalReplyResponse.Data,
        feedUid: String?,
        uid: String?
    ): String =
        run {
            val replyTag =
                when (reply.uid) {
                    feedUid -> " [楼主] "
                    uid -> " [层主] "
                    else -> ""
                }

            val rReplyTag =
                when (reply.ruid) {
                    feedUid -> " [楼主] "
                    uid -> " [层主] "
                    else -> ""
                }

            val rReplyUser =
                when (reply.ruid) {
                    uid -> ""
                    else -> """<a class="feed-link-uname" href="/u/${reply.ruid}">${reply.rusername}${rReplyTag}</a>"""
                }

            val replyPic =
                when (reply.pic) {
                    "" -> ""
                    else -> """ <a class=\"feed-forward-pic\" href=${reply.pic}>查看图片(${reply.picArr?.size})</a>"""
                }

            """<a class="feed-link-uname" href="/u/${reply.uid}">${reply.username}${replyTag}</a>回复${rReplyUser}: ${reply.message}${replyPic}"""

        }

    /**
     * 收藏夹弹窗回来：addItem 的响应里已经有最新收藏数和「是否已收藏」，就地写回详情数据即可。
     * 不重拉详情——多一次往返不说，详情那份 favnum 还有服务端缓存滞后（实测取消后仍返回旧值）。
     *
     * 收藏数在 `data.favnum`、星标在 `data.userAction`，只有整条重绑才会刷新——
     * `feedUserState` 那条事件带 payload，payload 分支只重绑点赞/关注，这两处不会跟着变。
     */
    fun onFavoriteChanged(action: CollectionAction) {
        currentFeedData()?.let { data ->
            action.favnum?.let { data.favnum = it.toString() }
            data.userAction?.let { userAction ->
                userAction.collect = action.collect
                userAction.favorite = action.collect
            }
        }
        feedFavState.postValue(Event(true))
    }

    fun onLikeFeed(id: String, isLike: Int) {
        val likeType = if (isLike == 1) "unlike" else "like"
        val likeUrl = "/v6/feed/$likeType"
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.postLikeFeed(likeUrl, id)
                .collect { result ->
                    val response = result.getOrNull()
                    if (response != null) {
                        // 落到局部变量：下面要在 let 里读它，属性访问的智能转换不值得赌
                        val respData = response.data
                        if (respData != null) {
                            // 图文没有 feedDataList，点赞数字在末尾互动栏那份 data 上
                            currentFeedData()?.let {
                                it.likenum = respData.count
                                it.userAction?.like = if (isLike == 1) 0 else 1
                            }
                            feedUserState.postValue(Event(true))
                        } else {
                            response.message?.let {
                                toastText.postValue(Event(it))
                            }
                        }
                    } else {
                        result.exceptionOrNull()?.printStackTrace()
                    }
                }
        }
    }

    fun postDeleteFeedReply(url: String, id: String, position: Int, rPosition: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.postDelete(url, id)
                .collect { result ->
                    val response = result.getOrNull()
                    if (response != null) {
                        if (response.data == "删除成功") {
                            toastText.postValue(Event("删除成功"))
                            val newList: List<TotalReplyResponse.Data> =
                                if (rPosition == null || rPosition == -1) {
                                    feedReplyData.value?.filterIndexed { index, _ ->
                                        index != position
                                    } ?: emptyList()
                                } else {
                                    feedReplyData.value?.mapIndexed { index, reply ->
                                        if (index == position) {
                                            reply.copy(
                                                lastupdate = System.currentTimeMillis(),
                                                replyRows = reply.replyRows.also {
                                                    it?.removeAt(rPosition)
                                                }
                                            )
                                        } else reply
                                    } ?: emptyList()
                                }
                            feedReplyData.postValue(newList)
                        } else if (!response.message.isNullOrEmpty()) {
                            response.message.let {
                                toastText.postValue(Event(it))
                            }
                        }
                    } else {
                        result.exceptionOrNull()?.printStackTrace()
                    }
                }
        }
    }

    /**
     * 帖主置顶 / 取消置顶某条回复（`/v6/feed/addReplyTopToFeed` 与 `cancelReplyTopFromFeed`）。
     *
     * 只有动态作者能操作，菜单那边已经按 uid 卡过一道，服务端也会校验。
     * 成功后不重新拉列表，只做本地重排，理由：接口返回的 `data` 是一句文案不是列表，
     * 而 [topReplyId] / [feedTopReplyList] 正是下拉刷新拼首屏的依据
     * （见 [fetchFeedReply] 里 `feedTopReplyList` 的两支），不同步对齐的话，
     * 刷新后置顶项会丢，或者连同服务端下发的那条一起出现两条。
     */
    fun postReplyTop(replyId: String, cancel: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val request =
                if (cancel) networkRepo.cancelReplyTopFromFeed(id)
                else networkRepo.addReplyTopToFeed(replyId, id)
            request.collect { result ->
                val response = result.getOrNull()
                if (response != null) {
                    if (!response.message.isNullOrEmpty()) {
                        response.message.let {
                            toastText.postValue(Event(it))
                        }
                    } else {
                        toastText.postValue(
                            Event(response.data ?: if (cancel) "已取消置顶" else "置顶成功")
                        )
                        applyReplyTop(replyId, cancel)
                    }
                } else {
                    result.exceptionOrNull()?.printStackTrace()
                }
            }
        }
    }

    /**
     * 置顶 / 取消置顶的本地重排。
     *
     * 置顶标记就是往 `username` 上加 `" [置顶]"`，写法与 [handleFeedData] 里处理服务端
     * `topReplyRows` 的那份一致。这里一律用 `copy` 造新实例、不改原对象：列表走 DiffUtil，
     * 实例不变的话 [FeedReplyDiffCallback] 认不出「同一条、内容变了」，条目不会重绑，
     * 标记要等条目被回收复用才显出来。
     *
     * 置顶后不拉接口：服务端把这条排在最前，本地同步挪到 0 位，观感与刷新后一致；
     * 取消置顶则位置不动，下次刷新服务端按时间重排。
     */
    private fun applyReplyTop(replyId: String, cancel: Boolean) {
        val replyList = feedReplyData.value?.toMutableList() ?: return
        val replyTag = " [置顶]"
        val oldTopId = topReplyId
        if (cancel) {
            topReplyId = null
            feedTopReplyList.clear()
            feedReplyData.postValue(clearReplyTag(replyList, replyId, replyTag))
            return
        }
        val target = replyList.firstOrNull { it.id == replyId } ?: return
        val pinned = target.copy(username = target.username.removeSuffix(replyTag) + replyTag)
        replyList.remove(target)
        replyList.add(0, pinned)
        topReplyId = replyId
        feedTopReplyList.clear()
        feedTopReplyList.add(pinned)
        // 服务端只保留一个置顶：旧的那条要摘掉标记，否则屏幕上会并排两条「置顶」
        feedReplyData.postValue(clearReplyTag(replyList, oldTopId, replyTag))
    }

    /** 把 [replyId] 那条的置顶标记摘掉；不在列表里、本来就没标记时原样返回 */
    private fun clearReplyTag(
        replyList: List<TotalReplyResponse.Data>,
        replyId: String?,
        replyTag: String
    ): List<TotalReplyResponse.Data> = replyList.map {
        if (it.id != replyId || !it.username.endsWith(replyTag)) it
        else it.copy(username = it.username.removeSuffix(replyTag))
    }

    fun saveUid(uid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            blackListRepo.saveUid(uid)
        }
    }

    fun saveHistory(
        id: String,
        uid: String,
        username: String,
        userAvatar: String,
        deviceTitle: String,
        message: String,
        dateline: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepo.saveHistory(
                id,
                uid,
                username,
                userAvatar,
                deviceTitle,
                message,
                dateline,
            )
        }
    }

    override fun fetchData() {}

    fun preFetchVoteComment() {
        val fid = feedDataList?.getOrNull(0)?.vote?.id ?: ""
        when (val type = feedDataList?.getOrNull(0)?.vote?.type) {
            0 -> { // 2 options
                fetchVoteCommentType0(
                    fid,
                    feedDataList?.getOrNull(0)?.vote?.options?.getOrNull(0)?.id ?: ""
                )
            }

            1 -> {
                fetchVoteCommentType1(fid)
            }

            else -> {
                toastText.postValue(Event("unsupported vote type: $type"))
            }
        }
    }

    private fun fetchVoteCommentType0(fid: String, extraKey: String) {
        val isLeft = extraKey == feedDataList?.getOrNull(0)?.vote?.options?.getOrNull(0)?.id
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getVoteComment(fid, extraKey, page, null, null)
                .onStart {
                    if (isLeft)
                        footerState.postValue(FooterState.Loading)
                }
                .collect { result ->
                    val feedReplyList = feedReplyData.value?.toMutableList() ?: ArrayList()
                    val data = result.getOrNull()
                    if (data != null) {
                        if (data.message != null) {
                            footerState.postValue(FooterState.LoadingError(data.message))
                            // 左选项那一支本来是"接着发右选项、由右选项收尾"把标志收掉的，
                            // 这里不再往下走，就得自己收：漏掉的话 isRefreshing 永远为真，
                            // loadMore 的守卫一直被挡，滚到底再也不会加载
                            isRefreshing = false
                            isLoadMore = false
                            return@collect
                        } else if (!data.data.isNullOrEmpty()) {
                            if (isRefreshing) {
                                if (isLeft)
                                    feedReplyList.clear()
                            }
                            if (isRefreshing || isLoadMore) {
                                data.data.forEach {
                                    if (it.entityType == "feed" && !blackListRepo.checkUid(it.uid)) {
                                        feedReplyList.add(it)
                                    }
                                }
                            }
                            feedReplyData.postValue(feedReplyList)
                            if (!isLeft)
                                footerState.postValue(FooterState.LoadingDone)
                        } else if (data.data?.isEmpty() == true) {
                            if (!isLeft)
                                footerState.postValue(FooterState.LoadingEnd(LOADING_END))
                        }
                        if (!isLeft)
                            page++
                    } else {
                        if (!isLeft)
                            footerState.postValue(FooterState.LoadingError(ApiError.textOf(result.exceptionOrNull())))
                        result.exceptionOrNull()?.printStackTrace()
                    }
                    if (isLeft) {
                        fetchVoteCommentType0(
                            fid,
                            feedDataList?.getOrNull(0)?.vote?.options?.getOrNull(1)?.id ?: ""
                        )
                    }
                    if (!isLeft) {
                        isRefreshing = false
                        isLoadMore = false
                    }
                }
        }
    }

    private fun fetchVoteCommentType1(fid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getVoteComment(fid, "", page, null, lastItem)
                .onStart {
                    footerState.postValue(FooterState.Loading)
                }
                .collect { result ->
                    val feedReplyList = feedReplyData.value?.toMutableList() ?: ArrayList()
                    val data = result.getOrNull()
                    if (data != null) {
                        if (data.message != null) {
                            footerState.postValue(FooterState.LoadingError(data.message))
                            // 同 fetchVoteCommentType0：这条出口不补标志，翻页就再也发不出去了
                            isRefreshing = false
                            isLoadMore = false
                            return@collect
                        } else if (!data.data.isNullOrEmpty()) {
                            lastItem = data.data.last().id
                            if (isRefreshing)
                                feedReplyList.clear()
                            if (isRefreshing || isLoadMore) {
                                data.data.forEach {
                                    if (it.entityType == "feed" && !blackListRepo.checkUid(it.uid)) {
                                        feedReplyList.add(it)
                                    }
                                }
                            }
                            page++
                            feedReplyData.postValue(feedReplyList)
                            footerState.postValue(FooterState.LoadingDone)
                        } else if (data.data?.isEmpty() == true) {
                            isEnd = true
                            if (isRefreshing)
                                feedReplyData.postValue(emptyList())
                            footerState.postValue(FooterState.LoadingEnd(LOADING_END))
                        }
                    } else {
                        footerState.postValue(FooterState.LoadingError(ApiError.textOf(result.exceptionOrNull())))
                        result.exceptionOrNull()?.printStackTrace()
                    }
                    isRefreshing = false
                    isLoadMore = false
                }
        }
    }


    fun fetchAnswerList(sort: String = "reply") {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getAnswerList(id, sort, page, null, lastItem)
                .onStart {
                    footerState.postValue(FooterState.Loading)
                }
                .collect { result ->
                    val feedReplyList = feedReplyData.value?.toMutableList() ?: ArrayList()
                    val data = result.getOrNull()
                    if (data != null) {
                        if (data.message != null) {
                            footerState.postValue(FooterState.LoadingError(data.message))
                            // 同 fetchVoteCommentType0：这条出口不补标志，答主列表就再也翻不动了
                            isRefreshing = false
                            isLoadMore = false
                            return@collect
                        } else if (!data.data.isNullOrEmpty()) {
                            lastItem = data.data.last().id
                            if (isRefreshing)
                                feedReplyList.clear()
                            if (isRefreshing || isLoadMore) {
                                data.data.forEach {
                                    if (it.entityType == "feed" && !blackListRepo.checkUid(it.uid)) {
                                        feedReplyList.add(it)
                                    }
                                }
                            }
                            page++
                            feedReplyData.postValue(feedReplyList)
                            footerState.postValue(FooterState.LoadingDone)
                        } else if (data.data.isNullOrEmpty()) {
                            isEnd = true
                            if (isRefreshing)
                                feedReplyData.postValue(emptyList())
                            footerState.postValue(FooterState.LoadingEnd(LOADING_END))
                        }
                    } else {
                        footerState.postValue(FooterState.LoadingError(ApiError.textOf(result.exceptionOrNull())))
                        result.exceptionOrNull()?.printStackTrace()
                    }
                    isRefreshing = false
                    isLoadMore = false
                }
        }
    }

    fun updateReply(data: TotalReplyResponse.Data) {
        val feedReplyList: List<TotalReplyResponse.Data> =
            if (type == "feed") { // feed
                val newList =
                    feedReplyData.value?.toMutableList() ?: ArrayList()
                newList.add(0, data)
                newList
            } else { //feed reply
                feedReplyData.value?.mapIndexed { index, reply ->
                    if (index == position) {
                        reply.copy(
                            lastupdate = System.currentTimeMillis(),
                            replyRows = (reply.replyRows ?: ArrayList()).also {
                                it.add(
                                    reply.replyRows?.size ?: 0,
                                    data.also { reply ->
                                        reply.message =
                                            generateMess(reply, feedUid, cuid)
                                    }
                                )
                            }
                        )
                    } else reply
                } ?: emptyList()
            }
        feedReplyData.postValue(feedReplyList)
    }

    fun handleFeedData() {
        feedData?.let {data->
            feedUid = data.uid
            funame = data.userInfo?.username
            avatar = data.userAvatar
            device = data.deviceTitle
            verifyIcon = data.userInfo?.verifyIcon
            verifyStatus = data.userInfo?.verifyStatus
            replyCount = data.replynum
            dateLine = data.dateline
            feedTypeName = data.feedTypeName
            feedType = data.feedType

            // 列表项不下发 message_raw_output（Kotlin 侧是 null，不等于字符串 "null"，单看原条件
            // 会放行并 Gson 出空正文）→ 预览态不解析正文，但排版照图文铺（作者行 + 封面 + 标题），
            // 详情回来只在其下补正文，首屏不会先出卡片再整块换成图文。
            if (feedType in listOf("feedArticle", "trade")
                && (isPreview || data.messageRawOutput != "null")
            ) {
                articleMsg =
                    if ((data.message?.length ?: 0) > 150)
                        data.message?.substring(0, 150)
                    else data.message
                articleDateLine = data.dateline
                // 作者行和封面在头部项里（见 FeedDataAdapter.ArticleHeaderViewHolder），
                // 所以正文列表从标题开始
                articleList = ArrayList<FeedArticleContentBean.Data>().also {
                    if (data.messageTitle?.isNotEmpty() == true) {
                        it.add(
                            FeedArticleContentBean.Data(
                                "text", data.messageTitle, null,
                                null, "true", null, null
                            )
                        )
                    }
                    if (!isPreview) {
                        val feedRaw = """{"data":${data.messageRawOutput}}"""
                        val feedJson: FeedArticleContentBean = Gson().fromJson(
                            feedRaw, FeedArticleContentBean::class.java
                        )
                        feedJson.data?.forEach { item ->
                            if (item.type in listOf("text", "image", "shareUrl"))
                                it.add(item)
                        }
                    }
                    // HeaderAdapter(1) + 图文头部项(1) + 正文块 + 末尾互动栏
                    itemCount = it.size + 3
                }
                articleHeader = data
                // 分支必须互斥：两条分支共用一个 adapter，而 FeedDataAdapter.getItemCount 在
                // 两边同时非空时返回 0。预览态和详情回填都走这一支，feedDataList 不清掉就会让
                // 图文整块变 0 高度、只剩评论区
                feedDataList = null
            } else {
                feedDataList = ArrayList<HomeFeedResponse.Data>().also {
                    it.add(data)
                }
                articleList = null
                articleHeader = null
                // HeaderAdapter(1) + 内容卡(1)
                itemCount = 2
            }
            if (!data.topReplyRows.isNullOrEmpty()) {
                isTop = true
                feedTopReplyList.clear()
                data.topReplyRows.getOrNull(0)?.let {
                    topReplyId = it.id
                    val unameTag =
                        when (it.uid) {
                            feedUid -> " [楼主]"
                            else -> ""
                        }
                    val replyTag = " [置顶]"
                    it.username = "${it.username}$unameTag$replyTag"
                    if (!it.replyRows.isNullOrEmpty()) {
                        it.replyRows = it.replyRows?.map { reply ->
                            reply.copy(
                                message = generateMess(reply, feedUid, it.uid)
                            )
                        }?.toMutableList()
                    }
                }
                feedTopReplyList.addAll(data.topReplyRows)
            }
            if (!data.replyMeRows.isNullOrEmpty()) {
                run {
                    data.replyMeRows.getOrNull(0)?.let {
                        if (it.id == topReplyId)
                            return@run
                        else
                            replyMeId = it.id
                        val unameTag =
                            when (it.uid) {
                                feedUid -> " [楼主]"
                                else -> ""
                            }
                        it.username = "${it.username}$unameTag"
                        if (!it.replyRows.isNullOrEmpty()) {
                            it.replyRows = it.replyRows?.map { reply ->
                                reply.copy(
                                    message = generateMess(reply, feedUid, it.uid)
                                )
                            }?.toMutableList()
                        }
                    }
                    feedTopReplyList.addAll(data.replyMeRows)
                }
            }
        }
    }


}