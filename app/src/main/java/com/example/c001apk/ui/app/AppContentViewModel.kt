package com.example.c001apk.ui.app

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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import com.example.c001apk.util.ApiError

/**
 * 应用详情页的各个区块：讨论的三种排序 + 版本历史 / 发现者 / 礼包 / 相关应用。
 *
 * id 的取法按区块区分，服务端不通用、传错只会静默返回空列表：
 *  - 包名：评价/讨论、发现者、相关应用
 *  - 应用数字 ID：版本历史、礼包
 */
class AppContentViewModel @AssistedInject constructor(
    @Assisted("type") private val type: String,
    @Assisted("appId") private val appId: String,
    @Assisted("packageName") packageName: String,
    blackListRepo: BlackListRepo,
    historyRepo: HistoryFavoriteRepo,
    networkRepo: NetworkRepo
) : BaseAppViewModel(blackListRepo, historyRepo, networkRepo) {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("type") type: String,
            @Assisted("appId") appId: String,
            @Assisted("packageName") packageName: String,
        ): AppContentViewModel
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        fun provideFactory(
            assistedFactory: Factory,
            type: String,
            appId: String,
            packageName: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return assistedFactory.create(type, appId, packageName) as T
            }
        }
    }

    // 详情接口偶尔不下发 apkname，这时退回数字 ID，避免整块列表空白
    private val pkg: String = packageName.ifEmpty { appId }

    // 版本历史条目点击下载要用：数字 ID + 包名
    override val appIdForDownload: String get() = appId
    override val packageNameForDownload: String get() = pkg

    private val isCommentTab: Boolean =
        type !in listOf("version", "discoverer", "gift", "related")

    private val listType: String = when (type) {
        "pub" -> "dateline_desc"
        "hot" -> "popular"
        else -> "lastupdate_desc"
    }

    override fun fetchData() {
        viewModelScope.launch(Dispatchers.IO) {
            val flow = when (type) {
                "version" -> networkRepo.getAppVersionList(appId, page)
                "discoverer" -> networkRepo.getAppDiscovererList(pkg, page)
                "gift" -> networkRepo.getAppGiftList(appId, page)
                "related" -> networkRepo.searchRelatedApp(pkg, page)
                else -> networkRepo.getAppCommentList(pkg, listType, page)
            }
            flow
                .onStart {
                    if (isLoadMore) {
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.Loading)
                        else
                            footerState.postValue(FooterState.Loading)
                    }
                }
                .collect { result ->
                    val contentList = dataList.value?.toMutableList() ?: ArrayList()
                    val response = result.getOrNull()
                    if (!response?.message.isNullOrEmpty()) {
                        response?.message?.let {
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingError(it))
                            else
                                footerState.postValue(FooterState.LoadingError(it))
                        }
                        return@collect
                    } else if (!response?.data.isNullOrEmpty()) {
                        lastItem = response?.data?.last()?.id
                        if (isRefreshing)
                            contentList.clear()
                        if (isRefreshing || isLoadMore) {
                            response?.data?.forEach { item ->
                                if (isCommentTab) {
                                    // 评价区里混着 feed 与其它实体，只有 feed 要过黑名单
                                    if (item.entityType == "feed" && !isBlocked(item))
                                        contentList.add(item)
                                } else {
                                    contentList.add(item)
                                }
                            }
                        }
                        page++
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.LoadingDone)
                        else
                            footerState.postValue(FooterState.LoadingDone)
                        dataList.postValue(contentList)
                    } else if (response?.data?.isEmpty() == true) {
                        isEnd = true
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.LoadingFailed(LOADING_EMPTY))
                        else {
                            if (isRefreshing)
                                dataList.postValue(emptyList())
                            footerState.postValue(FooterState.LoadingEnd(LOADING_END))
                        }
                    } else {
                        // ★ 这里是**抛了异常/响应为空**的分支 ✗
                        //   原来固定写 `LOADING_FAILED`（"加载失败"）⇒ 具体原因全丢 ✗
                        //   现在走 ApiError ✓ —— 会从响应体的 message 里取人话 ✓
                        isEnd = true
                        val err = result.exceptionOrNull()
                        val text = ApiError.textOf(err)
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.LoadingFailed(text))
                        else
                            footerState.postValue(FooterState.LoadingError(text))
                        err?.printStackTrace()
                    }
                    isRefreshing = false
                    isLoadMore = false
                }
        }
    }

    private suspend fun isBlocked(data: HomeFeedResponse.Data): Boolean =
        blackListRepo.checkUid(data.userInfo?.uid ?: data.uid.orEmpty())
                || blackListRepo.checkTopic(
            data.tags.orEmpty() + data.ttitle.orEmpty() +
                    data.relationRows?.getOrNull(0)?.title.orEmpty()
        )

}
