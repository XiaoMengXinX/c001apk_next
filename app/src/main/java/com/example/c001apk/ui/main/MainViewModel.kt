package com.example.c001apk.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.c001apk.logic.repository.BlackListRepo
import com.example.c001apk.logic.repository.NetworkRepo
import com.example.c001apk.logic.repository.SpamConfigRepo
import com.example.c001apk.util.CdidClient
import com.example.c001apk.util.CookieUtil
import com.example.c001apk.util.IosDeviceUtils
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
                    // 这里原本会把酷安官方最新版本号写进 PrefManager 并据此拼 Android UA，
                    // 供 Android 身份下的请求头使用。改用 iOS 身份后版本号来自
                    // IosHeaders.APP_VERSION / APP_CODE，UA 也由 IosHeaders 生成，
                    // 那套字段再没有人读，继续写只会留下拼空的假 UA（见 ImageUtil）。
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
                                    PrefManager.refreshToken = login.refreshToken.orEmpty()
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

                            // 登录后向 CDID 服务换一个服务端登记的固定设备 ID：
                            // 随机 UUID 只能读，点赞/回复等写接口会被风控返回 -10。
                            // 只在首次登录/退出后重新登录时请求一次，之后长期复用。
                            if (PrefManager.isLogin && PrefManager.iosCdid.isBlank()) {
                                runCatching {
                                    val cdid = CdidClient.fetch(
                                        IosDeviceUtils.current(),
                                        IosDeviceUtils.idfv(),
                                        IosDeviceUtils.installId(),
                                    )
                                    IosDeviceUtils.setCdid(cdid)
                                }.onFailure { it.printStackTrace() }
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