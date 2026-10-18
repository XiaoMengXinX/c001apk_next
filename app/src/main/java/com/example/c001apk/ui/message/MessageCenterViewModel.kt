package com.example.c001apk.ui.message

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.c001apk.adapter.FooterState
import com.example.c001apk.constant.Constants.LOADING_EMPTY
import com.example.c001apk.constant.Constants.LOADING_END
import com.example.c001apk.constant.Constants.LOADING_FAILED
import com.example.c001apk.logic.model.MessageResponse
import com.example.c001apk.logic.repository.BlackListRepo
import com.example.c001apk.logic.repository.HistoryFavoriteRepo
import com.example.c001apk.logic.repository.NetworkRepo
import com.example.c001apk.util.CookieUtil
import com.example.c001apk.util.Event
import com.example.c001apk.util.MessageCenterSeenStore
import com.example.c001apk.util.MessageKit
import com.example.c001apk.util.NotificationV18Kit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 消息中心（独立页面）：顶部宫格 + 下方「所有未读消息」汇总列表。
 *
 * 服务端只在 checkCount 里给**分类未读数**、不给逐条已读标记（实测 2026-10-05
 * `/v6/notification/list` 一页 20 条 `isnew` 全是 0；2026-10-06 换源时又量了一次
 * `notificationV18/list` 全量 53 条，`isnew` / `unread_count` **同样全是 0**），
 * 所以这里的「未读」口径是**该分类最新的 N 条、去掉本机已经看过的**，
 * N 就是服务端该分类未读数——与 coolapk-desktop 的 `notificationSeen.ts` 完全一致。
 *
 * 展示过的条目在**离开本页时**记进本机账本（[MessageCenterSeenStore]，见 [commitSeen]）：
 * 既不会再出现在列表里，也会把宫格红点抵掉（看过就消，而不是等 16 天前的旧通知一直挂在下面）。
 * 落账刻意推迟到 onStop，而不是展示的那一刻 —— 否则宫格红点会在列表铺好的同一帧消失，
 * 用户看不出哪一类有新消息。
 */
@HiltViewModel
class MessageCenterViewModel @Inject constructor(
    private val blackListRepo: BlackListRepo,
    private val historyRepo: HistoryFavoriteRepo,
    private val networkRepo: NetworkRepo
) : ViewModel() {

    /** 外层列表一次展示多少条（上滑加载更多按这个粒度追加） */
    private val pageSize = 20

    /**
     * 未读汇总的数据来源：分类 → 接口。
     *
     * 「评论回复」（commentMe）走官方现在在用的 V18 统一通知流，不再用老
     * `/v6/notification/list`：后者一页 20 条里 15 条是酷安小秘书刷屏、正文还是 HTML，
     * 而且**一条「回复了你的评论」都没有**，这类未读在 app 里完全看不到。
     * 归一化和类型过滤都在 NetworkRepo 里做（见 [NotificationV18Kit]），这里只认端点。
     *
     * 它现在在宫格里也有独立入口了（「我的回复」），但汇总列表照旧一并收着——
     * 跟 @我 / 赞 / 关注那几类一样，宫格有入口不代表下面不列。
     */
    private val sources = linkedMapOf(
        "atMe" to "/v6/notification/atMeList",
        "atCommentMe" to "/v6/notification/atCommentMeList",
        "commentMe" to NotificationV18Kit.URL,
        "feedLike" to NotificationV18Kit.LIKE_URL,
        "contactsFollow" to "/v6/notification/contactsFollowList",
    )

    /** 单个分类的拉取进度 */
    private class SourceState {
        var page = 1
        var lastItem: String? = null

        /** 该分类这轮要收的未读条数 = 服务端未读数 − 本机已读抵消 */
        var target = 0

        /** 已经扫过多少条（含被跳过的小秘书 / 已读 / 黑名单），用来卡住 target */
        var fetched = 0

        /** 这一类收工了（收满 target，或接口翻到底 / 报错） */
        var end = false
    }

    var isRefreshing: Boolean = false
    var isLoadMore: Boolean = false
    var isEnd: Boolean = false

    var messCountList = MutableLiveData<Boolean>()
    val footerState = MutableLiveData<FooterState>()
    val messageData = MutableLiveData<List<MessageCenterAdapter.Item>>()
    val toastText = MutableLiveData<Event<String>>()

    private val states = mutableMapOf<String, SourceState>()

    /** 已经拉到的未读条目（按时间倒序），对外展示的是它的前 [shown] 条 */
    private val pool = mutableListOf<MessageCenterAdapter.Item>()
    private var shown = 0

    /**
     * 「展示即已读」的待落账队列：分类 → 本页展示过的条目 id。
     *
     * 不在这里当场记账，是因为宫格红点读的就是这份账本：一进来就记，红点会跟着列表
     * 铺好的那一帧当场消失（用户要求「至少在离开本页之前保持显示」）。
     * 真正落账在 [commitSeen]，由页面 onStop 调。
     */
    private val pendingSeen = mutableMapOf<String, MutableSet<String>>()

    /** [pendingSeen] 在 IO 线程写、主线程（onStop）读，得自己护住 */
    private val seenLock = Any()

    /** 下拉刷新：重新读各分类未读数，从头攒一份未读列表 */
    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        isLoadMore = false
        isEnd = false
        shown = 0
        pool.clear()
        states.clear()
        load()
    }

    /** 上滑加载更多：先摊开池子里还没展示的，不够再按分类续拉 */
    fun loadMore() {
        if (isRefreshing || isLoadMore || isEnd) return
        isLoadMore = true
        load()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isLoadMore) footerState.postValue(FooterState.Loading)

                if (states.isEmpty()) {
                    // 刷新：先按各分类未读数定下这轮要收多少条未读
                    val counts = fetchCounts()
                    sources.keys.forEach { category ->
                        val server = counts[category] ?: 0
                        // 服务端未读降到本机抵消数以下（通知被删、在别处读过）就收敛
                        MessageCenterSeenStore.syncSeenCount(category, server)
                        states[category] = SourceState().apply {
                            // ★ 2026-10-18 用户要求：消息中心**显示全部**，不只是未读
                            //   ⇒ 不再用「服务端未读数」当条数上限 ✗
                            //   ⇒ 上限设成无穷，真正结束由 fetchPage 里的
                            //     「data 为空 ⇒ state.end = true」判定 ✓
                            //   未读数仍然要同步（宫格红点用它 ✓），只是不再决定条数 ✓
                            target = Int.MAX_VALUE
                        }
                    }
                }

                ensurePool(shown + pageSize)
                submit()
            } catch (e: Exception) {
                e.printStackTrace()
                footerState.postValue(FooterState.LoadingError(LOADING_FAILED))
                isRefreshing = false
                isLoadMore = false
            }
        }
    }

    /** 池子不够就继续按分类拉，直到够 [need] 条或所有分类都拉完 */
    private suspend fun ensurePool(need: Int) {
        while (pool.size < need) {
            val pending = sources.keys.filter { category ->
                states[category]?.let { it.fetched < it.target && !it.end } == true
            }
            if (pending.isEmpty()) return
            pending.forEach { fetchPage(it) }
        }
    }

    /** 按分类拉一页，把落在未读区间里的条目收进池子 */
    private suspend fun fetchPage(category: String) {
        val state = states[category] ?: return
        val url = sources[category] ?: return

        val response = networkRepo.getMessage(url, state.page, state.lastItem)
            .first().getOrNull()
        val data = response?.data
        if (data.isNullOrEmpty()) {
            state.end = true
            return
        }

        state.lastItem = data.last().id
        state.page++

        data.forEach { item ->
            if (state.fetched >= state.target) {
                state.end = true
                return@forEach
            }
            state.fetched++
            if (isUnreadCandidate(category, item))
                pool.add(MessageCenterAdapter.Item(category, item))
        }

        if (state.fetched >= state.target) state.end = true
    }

    /**
     * 这一条要不要进消息中心的列表。
     *
     * 2026-10-18 起：**不再看是否已读** ✓（用户要求显示全部消息 ✓）
     * 仍然过滤掉：小秘书（宫格里有独立入口，避免重复列 ✓）、黑名单用户（静默 ✓）、
     * 以及 id 为空的脏数据 ✓
     */
    private suspend fun isUnreadCandidate(
        category: String,
        data: MessageResponse.Data
    ): Boolean {
        if (data.id.isBlank()) return false
        if (MessageKit.isSecretaryNotify(data)) return false
        // ★ 这里**不再**因为「本机已读」就排除本条 ✗
        //   用户要求：消息中心显示**全部**消息，读完也留在列表里 ✓
        //   （原来 isSeen 过滤 ⇒ 离开再进来就消失 ✗ 用户明确不喜欢 ✗）
        //   已读仍然照常记账 ✓ —— 影响的只是宫格红点 ✓ 不是列表可见性 ✓
        val uid = when (category) {
            "feedLike" -> data.likeUid
            "atMe", "atCommentMe" -> data.uid
            else -> data.fromuid
        }
        return !blackListRepo.checkUid(uid)
    }

    /**
     * 把池子里该展示的部分提交给列表。跨分类合并后仍按时间倒序，
     * 已展示的部分不动（顺序稳定），新收进来的只排尾部。
     */
    private fun submit() {
        if (pool.size > shown) {
            val head = pool.take(shown)
            val tail = pool.drop(shown).sortedByDescending { it.data.dateline }
            pool.clear()
            pool.addAll(head)
            pool.addAll(tail)
        }

        val next = pool.take(shown + pageSize)

        if (next.size <= shown) {
            // 没有新东西可展示了
            isEnd = true
            if (shown == 0) {
                messageData.postValue(emptyList())
                footerState.postValue(FooterState.LoadingEnd(LOADING_EMPTY))
            } else {
                footerState.postValue(FooterState.LoadingEnd(LOADING_END))
            }
            isRefreshing = false
            isLoadMore = false
            return
        }

        // 展示即已读：先攒进待落账队列，宫格红点等离开本页再抵消（见 [commitSeen]）
        synchronized(seenLock) {
            next.drop(shown).forEach { item ->
                pendingSeen.getOrPut(item.category) { mutableSetOf() }.add(item.data.id)
            }
        }

        shown = next.size
        messageData.postValue(next)
        messCountList.postValue(true)
        footerState.postValue(FooterState.LoadingDone)
        isRefreshing = false
        isLoadMore = false
    }

    /**
     * 把本页展示过的条目落进本机账本（宫格红点、首页角标、下次进本页的未读列表都读它）。
     *
     * 由页面 onStop 调，而不是展示时当场调：用户要求「至少在离开消息中心之前，
     * 红点保持显示」，进去一帧就消掉会看不出哪一类有新消息。
     */
    fun commitSeen() {
        val pending = synchronized(seenLock) {
            val copy = pendingSeen.toMap()
            pendingSeen.clear()
            copy
        }
        pending.forEach { (category, ids) ->
            val added = MessageCenterSeenStore.markSeen(category, ids.toList())
            MessageCenterSeenStore.addSeenCount(category, added)
        }
    }

    /** 拉一次 checkCount：写 CookieUtil（宫格红点）并返回各分类未读数 */
    private suspend fun fetchCounts(): Map<String, Int> {
        val data = networkRepo.checkCount().first().getOrNull()?.data ?: return emptyMap()
        CookieUtil.atme = data.atme
        CookieUtil.atcommentme = data.atcommentme
        CookieUtil.commentme = data.commentme
        CookieUtil.feedlike = data.feedlike
        CookieUtil.contacts_follow = data.contactsFollow
        CookieUtil.badge = data.unreadBadge
        CookieUtil.notification = data.unreadNotification
        CookieUtil.message = data.message
        messCountList.postValue(true)
        return mapOf(
            "atMe" to data.atme,
            "atCommentMe" to data.atcommentme,
            "commentMe" to data.commentme,
            "feedLike" to data.feedlike,
            "contactsFollow" to data.contactsFollow,
        )
    }

    fun onCheckCount() {
        viewModelScope.launch(Dispatchers.IO) { fetchCounts() }
    }

    fun onPostDelete(position: Int, id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.postDelete("/v6/notification/delete", id)
                .collect { result ->
                    val response = result.getOrNull()
                    if (response != null) {
                        if (response.data == "删除成功") {
                            removeAt(position)
                            toastText.postValue(Event(response.data))
                        } else if (!response.message.isNullOrEmpty()) {
                            toastText.postValue(Event(response.message))
                        }
                    } else {
                        result.exceptionOrNull()?.printStackTrace()
                    }
                }
        }
    }

    private fun removeAt(position: Int) {
        val list = messageData.value?.toMutableList() ?: return
        if (position !in list.indices) return
        val removed = list.removeAt(position)
        // 池子里也摘掉，免得下一次刷新前又被当成未读
        pool.removeAll { it.category == removed.category && it.data.id == removed.data.id }
        // 服务端已经删了这条，别在待落账队列里留着（留着会把本机抵消数抬高、
        // 连带把还没看的那几条也抵没了）
        synchronized(seenLock) { pendingSeen[removed.category]?.remove(removed.data.id) }
        if (shown > 0) shown--
        messageData.postValue(list)
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
}
