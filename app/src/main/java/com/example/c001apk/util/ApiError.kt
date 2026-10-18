package com.example.c001apk.util

import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 统一的错误描述（安卓端）。
 *
 * 对应 Flutter 桌面端的 `lib/logic/network/api_error.dart` ✓ 文案规则一致 ✓
 *
 * ## 为什么需要
 * `AppContentViewModel` 之类的地方原来是：
 * ```kotlin
 * } else {
 *     loadingState.postValue(LoadingState.LoadingFailed(LOADING_FAILED))  // 固定字符串 ✗
 *     result.exceptionOrNull()?.printStackTrace()                          // 只进 logcat ✗
 * }
 * ```
 * ⇒ 用户只看到「加载失败」✗ 服务端给的 `message` 完全没有 ✗
 *
 * ## 文案优先级（与桌面端一致 ✓）
 * ```
 * ① HTTP 响应体 JSON 里的 message     ← 最有用 ✓
 * ② error / msg / error_description / errorMessage
 * ③ 纯文本响应 → 像 HTML 就说「服务器返回了错误页」，否则截 200 字
 * ④ 都没有 → 按 HTTP 状态码给固定文案
 * ⑤ 不是 HTTP 异常（IO/SSL/超时）→ 按异常类型给文案
 * ```
 */
data class ApiError(
    /** 主要文案（一定是人话 ✓） */
    val title: String,
    /** 次要信息（服务端状态码 ✓ 可能为空） */
    val detail: String? = null,
    /** HTTP 状态码（连不上时为 0） */
    val statusCode: Int = 0,
    /** 请求路径（可空） */
    val path: String? = null,
) {
    /** 给 UI 的一行：标题 + （可选）状态码 */
    fun display(): String =
        if (statusCode > 0) "$title\nHTTP $statusCode" else title

    /** 「复制详情」用 */
    fun copyText(): String = buildString {
        appendLine("c001apk 错误详情")
        appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("标题：$title")
        detail?.takeIf { it.isNotEmpty() }?.let { appendLine("说明：$it") }
        if (statusCode > 0) appendLine("HTTP $statusCode")
        path?.takeIf { it.isNotEmpty() }?.let { appendLine("位置：$it") }
    }

    companion object {

        /**
         * 把任意异常转成 [ApiError] ✓
         *
         * 调用方的典型用法：
         * ```kotlin
         * result.exceptionOrNull()?.let { ApiError.from(it).display() }
         * ```
         */
        fun from(t: Throwable): ApiError {
            // ① Retrofit 的 HTTP 异常（非 2xx）★ 最常见，也最有用 ✓
            if (t is HttpException) {
                val code = t.code()
                val body = runCatching { t.response()?.errorBody()?.string() }.getOrNull()
                val msg = titleFromBody(body) ?: titleForStatus(code)
                return ApiError(
                    title = msg,
                    detail = detailFromBody(body),
                    statusCode = code,
                    path = runCatching { t.response()?.raw()?.request?.url?.encodedPath }.getOrNull(),
                )
            }

            // ② 网络层
            when (t) {
                is UnknownHostException ->
                    return ApiError("无法解析域名，请检查网络或 DNS")
                is ConnectException ->
                    return ApiError("连接失败，请检查网络")
                is SocketTimeoutException ->
                    return ApiError("响应超时，请稍后重试")
                is SSLException ->
                    return ApiError("证书校验失败", detail = t.message)
                is IOException ->
                    return ApiError("网络异常，请检查网络", detail = t.message)
            }

            // ③ 兜底：异常自身的文案（去掉包名前缀 ✓）
            val raw = t.message?.trim().orEmpty()
            val title = when {
                raw.isEmpty() -> t.javaClass.simpleName
                raw.length > 200 -> raw.substring(0, 200) + "…"
                else -> raw
            }
            return ApiError(title)
        }

        /** 从响应体里找文案 ✓ 找不到返回 null */
        fun titleFromBody(body: String?): String? {
            if (body.isNullOrBlank()) return null
            val t = body.trim()

            // 尝试当 JSON 解析 ✓
            if (t.startsWith("{")) {
                val json = runCatching { JSONObject(t) }.getOrNull()
                if (json != null) {
                    for (k in listOf("message", "error", "msg", "error_description", "errorMessage")) {
                        if (!json.has(k) || json.isNull(k)) continue
                        val v = json.opt(k)
                        val s = (v as? String) ?: v.toString()
                        if (s.isNotBlank() && s != "null") return clipLine(s)
                    }
                    // 嵌套一层 data ✓
                    if (json.has("data") && !json.isNull("data")) {
                        val inner = json.optJSONObject("data")
                        if (inner != null) return titleFromBody(inner.toString())
                    }
                    return null
                }
            }

            // 纯文本 ✓
            val low = t.lowercase()
            if (low.contains("<html") || low.contains("<!doctype")) {
                return "服务器返回了错误页"
            }
            return clipLine(t)
        }

        private fun detailFromBody(body: String?): String? {
            if (body.isNullOrBlank()) return null
            val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
            for (k in listOf("status", "error", "code")) {
                if (json.has(k) && !json.isNull(k)) {
                    val v = json.opt(k)
                    if (v is Number) return "服务端状态码 $v"
                }
            }
            return null
        }

        fun titleForStatus(code: Int): String = when {
            code == 400 -> "请求参数有误"
            code == 401 || code == 403 -> "没有权限（可能登录已过期）"
            code == 404 -> "内容不存在（可能已被删除）"
            code == 409 -> "请求冲突"
            code == 429 -> "请求太频繁，请稍后再试"
            code in 500..599 -> "服务器出错了（$code）"
            code > 0 -> "请求失败（HTTP $code）"
            else -> "网络请求失败"
        }

        private fun clipLine(s: String, max: Int = 200): String {
            val one = s.replace(Regex("\\s+"), " ").trim()
            if (one.isEmpty()) return "未知错误"
            return if (one.length <= max) one else one.substring(0, max) + "…"
        }

        /**
         * 兜底：把 `Throwable` 转成一句人话 ✓（给还在用 String 的旧调用点 ✓）
         *
         * 这样 `LoadingFailed(LOADING_FAILED)` 可以就地改成
         * `LoadingFailed(ApiError.textOf(e))` ✓ 不用一次改完所有调用点 ✓
         */
        fun textOf(t: Throwable?): String =
            if (t == null) "网络请求失败" else from(t).display()
    }
}
