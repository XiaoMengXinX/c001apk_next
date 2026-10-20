package com.example.c001apk.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 拉 GitHub 用户资料：头像 URL 与 bio，给关于页「维护者」用。
 *
 * 走公开的 `api.github.com/users/{user}`，不需要 token。注意**未认证时限流按 IP 算**
 * （每小时 60 次），所以成功过的结果就缓存在内存里，之后直接复用：
 * 关于页是低频入口，这样够用，也不会出现「这次有网就显示、下次限流就消失」的闪烁。
 *
 * 头像不打包进 APK：同一个响应里就带 `avatar_url`，换人/换头像都不用重新发版。
 */
object GitHubProfile {

    private const val BASE_URL = "https://api.github.com/users/"

    /** 独立客户端：请求发往 GitHub，不该挂酷安的 Cookie / 签名 / 日志拦截器 */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** 用户资料；[bio] 为空串表示确认过 TA 没写 bio（同样不再重复请求） */
    data class Profile(val avatarUrl: String?, val bio: String)

    /** 用户名 → 资料 */
    private val cache = ConcurrentHashMap<String, Profile>()

    /**
     * 拉用户资料；网络失败 / 限流时返回 null，调用方回落到内置头像并藏掉 bio。
     *
     * `avatar_url` 默认给的是 460px 的图，这里补上 `s=120` 取个够用的尺寸
     * （头像显示 40dp，2~3 倍屏 120px 足够，也省流量）。
     */
    suspend fun fetch(user: String): Profile? = withContext(Dispatchers.IO) {
        cache[user]?.let { return@withContext it }
        val profile = runCatching {
            val request = Request.Builder()
                .url("$BASE_URL$user")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val obj = JSONObject(resp.body?.string().orEmpty())
                Profile(
                    avatarUrl = obj.stringOrNull("avatar_url")
                        ?.let { if (it.contains('?')) "$it&s=120" else "$it?s=120" },
                    bio = obj.stringOrNull("bio").orEmpty(),
                )
            }
        }.getOrNull() ?: return@withContext null
        cache[user] = profile
        profile
    }

    /**
     * 取字符串字段，JSON `null` 视为没有值。
     *
     * 不能用 `optString`：它遇到 JSON null 会返回**字符串 "null"**，
     * 于是没写 bio 的人会在界面上显示一行字面的 `null`（实测踩过）。
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null
        else optString(key).trim().takeIf { it.isNotEmpty() }
}
