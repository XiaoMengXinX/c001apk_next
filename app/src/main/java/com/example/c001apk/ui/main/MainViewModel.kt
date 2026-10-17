package com.example.c001apk.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.c001apk.constant.Constants
import com.example.c001apk.logic.repository.BlackListRepo
import com.example.c001apk.logic.repository.NetworkRepo
import com.example.c001apk.logic.repository.SpamConfigRepo
import com.example.c001apk.util.CookieUtil
import com.example.c001apk.util.PrefManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val networkRepo: NetworkRepo,
    private val blackListRepo: BlackListRepo,
    private val spamConfigRepo: SpamConfigRepo,
) : ViewModel() {

    var lastCheck = System.currentTimeMillis()
    var isInit: Boolean = true

    fun fetchAppInfo(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.getAppInfo(id)
                .collect { result ->
                    val appInfo = result.getOrNull()
                    if (appInfo?.data != null) {
                        try {
                            // 跟随官方最新版本号，保持请求头“现代”；空值不覆盖，避免生成无效 token
                            val versionName = appInfo.data.apkversionname
                            val versionCode = appInfo.data.apkversioncode
                            if (!versionName.isNullOrBlank()) PrefManager.VERSION_NAME = versionName
                            if (!versionCode.isNullOrBlank()) PrefManager.VERSION_CODE = versionCode
                            PrefManager.API_VERSION = Constants.API_VERSION
                            PrefManager.USER_AGENT =
                                "Dalvik/2.1.0 (Linux; U; Android ${PrefManager.ANDROID_VERSION}; ${PrefManager.MODEL} ${PrefManager.BUILDNUMBER}) (#Build; ${PrefManager.BRAND}; ${PrefManager.MODEL}; ${PrefManager.BUILDNUMBER}; ${PrefManager.ANDROID_VERSION}) +CoolMarket/${PrefManager.VERSION_NAME}-${PrefManager.VERSION_CODE}-${Constants.MODE}"
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    getCheckLoginInfo()
                }
        }
    }

    private fun getCheckLoginInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.checkLoginInfo()
                .collect { result ->
                    val response = result.getOrNull()
                    response?.let {
                        response.body()?.let {
                            if (response.body()?.data?.token != null) {
                                response.body()?.data?.let { login ->
                                    CookieUtil.badge = login.notifyCount.unreadBadge
                                    CookieUtil.atme = login.notifyCount.atme
                                    CookieUtil.atcommentme = login.notifyCount.atcommentme
                                    CookieUtil.feedlike = login.notifyCount.feedlike
                                    CookieUtil.contacts_follow = login.notifyCount.contactsFollow
                                    CookieUtil.message = login.notifyCount.message
                                    CookieUtil.commentme = login.notifyCount.commentme
                                    PrefManager.isLogin = true
                                    PrefManager.uid = login.uid
                                    PrefManager.username =
                                        withContext(Dispatchers.IO) {
                                            URLEncoder.encode(login.username, "UTF-8")
                                        }
                                    PrefManager.token = login.token
                                    // ★ 启动时换到的**第二凭据**，之后整个进程的业务请求优先用它
                                    PrefManager.refreshToken = login.refreshToken
                                    PrefManager.userAvatar = login.userAvatar
                                }
                            } else if (response.body()?.message == "登录信息有误") {
                                PrefManager.isLogin = false
                                PrefManager.uid = ""
                                PrefManager.username = ""
                                PrefManager.token = ""
                                PrefManager.refreshToken = ""
                                PrefManager.userAvatar = ""
                            }

                            try {
                                val headers = response.headers()
                                val cookies = headers.values("Set-Cookie")
                                val session = cookies[0]
                                val sessionID = session.substring(0, session.indexOf(";"))
                                CookieUtil.SESSID = sessionID
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }

                            syncBlackList()
                        }
                    }
                }
        }
    }

    /** 启动/登录后同步云端数据：黑名单镜像进本地库、其他屏蔽项（关键字/用户/节点）进内存缓存 */
    private fun syncBlackList() {
        if (!PrefManager.isLogin) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { blackListRepo.syncFromCloud() }
                .onFailure { it.printStackTrace() }
            runCatching { spamConfigRepo.refresh() }
                .onFailure { it.printStackTrace() }
        }
    }

    fun onCheckCount() {
        viewModelScope.launch(Dispatchers.IO) {
            networkRepo.checkCount()
                .collect { result ->
                    val response = result.getOrNull()
                    response?.data?.let {
                        CookieUtil.atme = it.atme
                        CookieUtil.atcommentme = it.atcommentme
                        CookieUtil.feedlike = it.feedlike
                        CookieUtil.contacts_follow = it.contactsFollow
                        CookieUtil.badge = it.unreadBadge
                        CookieUtil.notification = it.unreadNotification
                        CookieUtil.message = it.message
                        CookieUtil.commentme = it.commentme
                    }
                }
        }
    }

}