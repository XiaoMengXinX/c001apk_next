package com.example.c001apk.ui.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.c001apk.adapter.FooterState
import com.example.c001apk.adapter.LoadingState
import com.example.c001apk.constant.Constants.LOADING_EMPTY
import com.example.c001apk.constant.Constants.LOADING_END
import com.example.c001apk.constant.Constants.LOADING_FAILED
import com.example.c001apk.logic.model.HomeFeedResponse
import com.example.c001apk.logic.repository.BlackListRepo
import com.example.c001apk.logic.repository.HistoryFavoriteRepo
import com.example.c001apk.logic.repository.NetworkRepo
import com.example.c001apk.ui.base.BaseAppViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import com.example.c001apk.util.ApiError

/**
 * 个人主页里的各个 tab 列表：动态 / 点评 / 图文 / 问答 / 酷图。
 * 除了「动态」走 /v6/user/feedList，其余都走官方主页用的那几个接口。
 */
class UserTabViewModel @AssistedInject constructor(
    @Assisted("uid") val uid: String,
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
        ): UserTabViewModel
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        fun provideFactory(
            assistedFactory: Factory, uid: String, type: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return assistedFactory.create(uid, type) as T
            }
        }
    }

    /**
     * 个人主页「动态」「图文」两个 tab 整页统一按完整动态卡渲染。
     *
     * 这两个接口都会在同一个列表里混下发两种模板（htmlFeedList 实测 17 条里 6 条
     * entityTemplate=feedCover、11 条 feed），照模板分派会在一页里混出「紧凑封面卡 /
     * 完整动态卡」两种排法。要求是这一页的文章跟同页普通动态长一样，所以 feedCover 不单独排版
     * （开关见 AppAdapter.feedCoverAsFeed）。
     */
    override val feedCoverAsFeed: Boolean get() = type == "article" || type == "feed"

    // 这些列表官方都是翻页取（page），只有动态支持 lastItem
    private suspend fun request(): Flow<Result<HomeFeedResponse>> = when (type) {
        "article" -> networkRepo.getUserHtmlFeed(uid, page, null)
        "question" -> networkRepo.getUserQuestionAndAnswer(uid, page, null)
        "rating" -> networkRepo.getDataList(
            "/feed/nodeRatingList?uid=$uid&targetType=all&parseRatingToFeed=1",
            "", null, null, page
        )
        "coolpic" -> networkRepo.getDataList(
            "/feed/userCoolPictureFeedList?fragmentTemplate=flex&uid=$uid",
            "", null, null, page
        )
        else -> networkRepo.getUserFeed(uid, page, lastItem)
    }

    override fun fetchData() {
        viewModelScope.launch(Dispatchers.IO) {
            request()
                .onStart {
                    if (isLoadMore) {
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.Loading)
                        else
                            footerState.postValue(FooterState.Loading)
                    }
                }
                .collect { result ->
                    val feedList = dataList.value?.toMutableList() ?: ArrayList()
                    val feed = result.getOrNull()
                    if (feed != null) {
                        if (!feed.message.isNullOrEmpty()) {
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingError(feed.message))
                            else
                                footerState.postValue(FooterState.LoadingError(feed.message))
                            return@collect
                        } else if (!feed.data.isNullOrEmpty()) {
                            if (type == "feed") lastItem = feed.data.last().id
                            if (isRefreshing) feedList.clear()
                            if (isRefreshing || isLoadMore) {
                                feed.data.forEach {
                                    // noMoreDataCard
                                    if (it.entityTemplate == "noMoreDataCard") {
                                        isEnd = true
                                        isRefreshing = false
                                        isLoadMore = false
                                        dataList.postValue(feedList)
                                        if (listSize <= 0 && feedList.isEmpty())
                                            loadingState.postValue(
                                                LoadingState.LoadingError(it.title ?: "")
                                            )
                                        else {
                                            footerState.postValue(
                                                FooterState.LoadingEnd(it.title ?: "")
                                            )
                                            loadingState.postValue(LoadingState.LoadingDone)
                                        }
                                        return@collect
                                    } else if (it.entityType == "feed"
                                        || (type == "rating" && it.entityType == "nodeRating")
                                    )
                                        if (!blackListRepo.checkUid(it.userInfo?.uid.toString())
                                            && !blackListRepo.checkTopic(
                                                it.tags + it.ttitle + it.relationRows?.getOrNull(
                                                    0
                                                )?.title
                                            )
                                        )
                                            feedList.add(it)
                                }
                            }
                            page++
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingDone)
                            else
                                footerState.postValue(FooterState.LoadingDone)
                            dataList.postValue(feedList)
                        } else {
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
