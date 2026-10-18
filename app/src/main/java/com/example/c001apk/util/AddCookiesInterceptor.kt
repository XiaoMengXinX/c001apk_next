package com.example.c001apk.util

import com.example.c001apk.constant.Constants
import com.example.c001apk.constant.Constants.APP_ID
import com.example.c001apk.constant.Constants.CHANNEL
import com.example.c001apk.constant.Constants.DARK_MODE
import com.example.c001apk.constant.Constants.LOCALE
import com.example.c001apk.constant.Constants.MODE
import com.example.c001apk.constant.Constants.REQUEST_WITH
import com.example.c001apk.util.CookieUtil.SESSID
import com.example.c001apk.util.TokenDeviceUtils.getTokenV3
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

object AddCookiesInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder: Request.Builder = chain.request().newBuilder()
        val deviceCode = TokenDeviceUtils.getLastingDeviceCode()
        // 版本头必须与 token 使用同一个 versionCode，否则服务端校验不通过
        val versionName = PrefManager.VERSION_NAME.ifEmpty { Constants.VERSION_NAME }
        val versionCode = PrefManager.VERSION_CODE.ifEmpty { Constants.VERSION_CODE }
        val token = deviceCode.getTokenV3(versionCode)
        builder.apply {
            addHeader("User-Agent", PrefManager.USER_AGENT.ifEmpty { Constants.USER_AGENT })
            addHeader("X-Requested-With", REQUEST_WITH)
            addHeader("X-Sdk-Int", PrefManager.SDK_INT)
            addHeader("X-Sdk-Locale", LOCALE)
            addHeader("X-App-Id", APP_ID)
            addHeader("X-App-Token", token)
            addHeader("X-App-Version", versionName)
            addHeader("X-App-Code", versionCode)
            addHeader("X-Api-Version", PrefManager.API_VERSION.ifEmpty { Constants.API_VERSION })
            addHeader("X-App-Device", deviceCode)
            addHeader("X-Dark-Mode", DARK_MODE)
            addHeader("X-App-Channel", CHANNEL)
            addHeader("X-App-Mode", MODE)
            addHeader("X-App-Supported", versionCode)
            addHeader(
                "Referer",
                "https://api.coolapk.com/?ref=CoolMarket/$versionName-$versionCode-$MODE"
            )
            addHeader("Content-Type", "application/x-www-form-urlencoded")
            if (PrefManager.isLogin)
                addHeader(
                    "Cookie",
                    // ★ 凭据优先级：refreshToken（启动时换到的第二凭据）> token（主凭据）。
                    //   两者在业务接口上表现一致，见 PrefManager.refreshToken 的注释。
                    //   ⚠️ 必须写成**表达式** —— `addHeader(...)` 的参数位置不能放 `val` ✗
                    "uid=${PrefManager.uid}; username=${PrefManager.username}; " +
                        "token=${PrefManager.refreshToken.ifEmpty { PrefManager.token }}"
                )
            else addHeader("Cookie", SESSID)
        }
        return chain.proceed(builder.build())
    }
}
