package com.example.c001apk.util

import com.example.c001apk.util.CookieUtil.SESSID
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 给所有酷安 API 请求加上 iOS 客户端请求头与登录 Cookie。
 *
 * 签名/设备头由 [IosDeviceUtils] 统一生成（iOS `X-App-Token`，只依赖持久化设备 UUID
 * 与时间戳）。这里不再使用 Android 的 v3 token、szlmId 设备串、Dalvik UA 或
 * `X-Dark-Mode` / `Referer` 等 Android 专有头。
 */
object AddCookiesInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder: Request.Builder = chain.request().newBuilder()

        // 用 header() 覆盖而不是 addHeader()：同一请求经过多次拦截器时不会出现重复头
        IosDeviceUtils.headers().forEach { (name, value) ->
            builder.header(name, value)
        }

        if (PrefManager.isLogin) {
            builder.header(
                "Cookie",
                // ★ 凭据优先级：refreshToken（启动时换到的第二凭据）> token（主凭据）。
                //   两者在业务接口上表现一致，见 PrefManager.refreshToken 的注释。
                "uid=${PrefManager.uid}; username=${PrefManager.username}; " +
                    "token=${PrefManager.refreshToken.ifEmpty { PrefManager.token }}"
            )
        } else if (SESSID.isNotEmpty()) {
            builder.header("Cookie", SESSID)
        }

        return chain.proceed(builder.build())
    }
}
