package com.example.c001apk.ui.topic

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

class TopicContentViewModel @AssistedInject constructor(
    @Assisted("url") var url: String,
    @Assisted("title") var title: String,
    blackListRepo: BlackListRepo,
    historyRepo: HistoryFavoriteRepo,
    networkRepo: NetworkRepo
) : BaseAppViewModel(blackListRepo, historyRepo, networkRepo) {

    // 产品页「参数」tab：由 Fragment 从 product/detail 的 configRows 传入，
    // 合并进 dataList 下发的 productConfigList 卡片（服务端下发的这张卡片 entities 恒为空）
    var configRows: List<HomeFeedResponse.ConfigRow>? = null

    // 讨论 tab 当前三段式排序（默认/最新/热度），Fragment 重建时恢复选中态
    var currentSort: String = "默认"

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("url") url: String,
            @Assisted("title") title: String,
        ): TopicContentViewModel
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        fun provideFactory(assistedFactory: Factory, url: String, title: String)
                : ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return assistedFactory.create(url, title) as T
            }
        }
    }

    override fun fetchData() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getDataList(url, title, "", lastItem, page)
                .onStart {
                    if (isLoadMore) {
                        if (listSize <= 0)
                            loadingState.postValue(LoadingState.Loading)
                        else
                            footerState.postValue(FooterState.Loading)
                    }
                }
                .collect { result ->
                    val dataListList = dataList.value?.toMutableList() ?: ArrayList()
                    val data = result.getOrNull()
                    if (data != null) {
                        if (!data.message.isNullOrEmpty()) {
                            if (listSize <= 0)
                                loadingState.postValue(LoadingState.LoadingError(data.message))
                            else
                                footerState.postValue(FooterState.LoadingError(data.message))
                            return@collect
                        } else if (!data.data.isNullOrEmpty()) {
                            lastItem = data.data.lastOrNull()?.id
                            if (isRefreshing)
                                dataListList.clear()
                            if (isRefreshing || isLoadMore) {
                                data.data.forEach {
                                    // 「card」类型也要放行：产品页「参数」tab 的
                                    // productConfigList/listCard 都是 card，过滤掉会整页空白
                                    if (it.entityType in listOf(
                                            "feed", "topic", "product", "user", "card"
                                        ) && it.entityTemplate != "sortSelectCard"
                                    )
                                        if (!blackListRepo.checkUid(it.userInfo?.uid.toString())
                                            && !blackListRepo.checkTopic(
                                                it.tags + it.ttitle + it.relationRows?.getOrNull(0)?.title
                                            )
                                        )
                                            dataListList.add(
                                                // 参数 tab：把本地 configRows 合并进版本配置卡片
                                                if (it.entityTemplate == "productConfigList"
                                                    && it.configRows == null
                                                )
                                                    it.copy(configRows = configRows)
                                                else it
                                            )
                                }
                            }
                            page++
                            if (listSize <= 0) {
                                loadingState.postValue(LoadingState.LoadingDone)
                            } else
                                footerState.postValue(FooterState.LoadingDone)
                            dataList.postValue(dataListList)
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
