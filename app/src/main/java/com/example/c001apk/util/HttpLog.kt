package com.example.c001apk.util

import android.content.Context
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * HTTP 请求日志（排查用）。
 *
 * 对应 Flutter 桌面端的 `lib/utils/http_log.dart` ✓ 行为一致 ✓
 *
 * ## 开关
 * 设置 → 高级 → 「记录 HTTP 请求日志」（**默认关闭** ✓）
 * 存 `PrefManager.recordHttpLog` ✓
 * 关闭时 [record] 第一行就 return ⇒ **零开销** ✓
 *
 * ## 落盘
 * `<filesDir>/http_logs/YYYYMMDD.log` ✓ 一行一条 JSON ✓
 * - 单文件 > [MAX_FILE_BYTES]（2 MB）⇒ 换 `YYYYMMDD-2.log`、`-3` …
 * - 超过 [KEEP_DAYS]（7 天）的自动删掉 ✓
 *
 * ## 脱敏
 * `token` / `cookie` / `authorization` / `password` / `ssaid` / `uid` /
 * `username` / `sig` / `signature` / `x-app-token` / `x-client-ssaid` /
 * `x-app-userrandomid` → 值打成 `*** (len=N)` ✓ **保留长度** ✓
 * 两种形式都匹配：JSON `"token":"…"` 与 Cookie `token=…;`
 *
 * ## 线程
 * 写入走**单线程 executor** ✓ 不阻塞 okhttp 的 IO 线程 ✓
 * 所有异常都吞掉 ✓ —— 日志绝不能影响正常请求 ✓
 */
object HttpLog {

    private const val TAG = "HttpLog"

    /** 单文件上限：2 MB */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 只保留最近 7 天 */
    const val KEEP_DAYS = 7

    /** 请求体 / 响应体各记前 2 KB */
    const val MAX_BODY_CHARS = 2048

    private val SENSITIVE = listOf(
        "token", "cookie", "authorization", "password", "passwd", "secret",
        "session", "ssaid", "uid", "username", "sig", "signature",
        "x-app-token", "x-client-ssaid", "x-app-userrandomid",
    )

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "http-log").apply { isDaemon = true }
    }

    @Volatile
    private var dir: File? = null

    /** 首次使用时初始化（`MyApplication` 里调一次 ✓） */
    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.filesDir, "http_logs").apply { if (!exists()) mkdirs() }
    }

    private fun dir(context: Context? = null): File? {
        dir?.let { return it }
        val c = context ?: AppContextHolder.context ?: return null
        return File(c.filesDir, "http_logs").apply {
            if (!exists()) mkdirs()
            dir = this
        }
    }

    // ── 记录 ────────────────────────────────────────────────

    fun record(
        method: String,
        url: String,
        status: Int,
        elapsedMs: Long,
        reqBody: String? = null,
        respBody: String? = null,
        error: String? = null,
    ) {
        // ★ 零开销：一次 bool 判断 ✓
        if (!PrefManager.recordHttpLog) return
        val d = dir() ?: return
        val now = Date()

        io.execute {
            try {
                val row = JSONObject().apply {
                    put("ts", now.time / 1000)
                    put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(now))
                    put("method", method)
                    put("url", mask(url))
                    put("status", status)
                    put("ms", elapsedMs)
                    put("req", clip(mask(reqBody ?: "")))
                    put("resp", clip(mask(respBody ?: "")))
                    put("err", clip(error ?: ""))
                }
                pickFile(d, now).appendText(row.toString() + "\n")

                // 1/20 概率清理过期文件 ✓
                if (System.currentTimeMillis() % 20 == 0L) prune(d)
            } catch (e: Throwable) {
                Log.w(TAG, "record 失败: $e") // 不影响主流程 ✓
            }
        }
    }

    private fun pickFile(d: File, now: Date): File {
        val ymd = SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
        var i = 1
        while (true) {
            val f = File(d, if (i == 1) "$ymd.log" else "$ymd-$i.log")
            if (!f.exists()) return f
            if (f.length() < MAX_FILE_BYTES) return f
            i++
            if (i > 99) return f
        }
    }

    private fun prune(d: File) {
        try {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
            d.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 截断（按**字符**算 ✓ 与桌面端一致 ✓）
     *
     * ⚠️ 别用 `length / 1024` 当 KB：中文在多字节下会算少 ✗
     */
    private fun clip(s: String): String =
        if (s.length <= MAX_BODY_CHARS) s else s.substring(0, MAX_BODY_CHARS) + "…(截断)"

    /** 敏感值打码：`"token":"abc"` → `"token":"*** (len=3)"` ✓ 子串匹配、不分大小写 ✓ */
    fun mask(s: String): String {
        if (s.isEmpty()) return s
        var out = s
        for (k in SENSITIVE) {
            out = Regex("(\"$k\"\\s*:\\s*\")([^\"]*)(\")", RegexOption.IGNORE_CASE)
                .replace(out) { m -> "${m.groupValues[1]}*** (len=${m.groupValues[2].length})${m.groupValues[3]}" }
            out = Regex("($k=)([^;&\\s\"]+)", RegexOption.IGNORE_CASE)
                .replace(out) { m -> "${m.groupValues[1]}*** (len=${m.groupValues[2].length})" }
        }
        return out
    }

    // ── 设置页要用的三件 ────────────────────────────────────

    /** (总字节数, 文件数, 行数) */
    fun stats(context: Context? = null): Triple<Long, Int, Int> {
        val d = dir(context) ?: return Triple(0, 0, 0)
        var bytes = 0L
        var files = 0
        var lines = 0
        d.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            files++
            bytes += f.length()
            lines += runCatching { f.readLines().size }.getOrDefault(0)
        }
        return Triple(bytes, files, lines)
    }

    /** 导出全部日志成一份文本 ✓ 返回 `(建议文件名, 内容)` */
    fun exportAll(context: Context? = null): Pair<String, String> {
        val d = dir(context)
        val sb = StringBuilder()
        sb.appendLine("# c001apk HTTP 请求日志")
        sb.appendLine("# 导出时间：${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("# 说明：敏感字段已脱敏（长度保留）；响应体只记前 $MAX_BODY_CHARS 字符")
        sb.appendLine("# 每行一条 JSON")
        sb.appendLine()
        d?.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                runCatching { sb.append(f.readText()) }
            }
        val name = "c001apk-http-log-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
        return name to sb.toString()
    }

    /** 清空全部日志 ✓ 返回删掉的文件数 */
    fun clear(context: Context? = null): Int {
        val d = dir(context) ?: return 0
        var n = 0
        d.listFiles()?.forEach { f ->
            if (f.isFile && f.delete()) n++
        }
        return n
    }

    // ── okhttp 拦截器 ───────────────────────────────────────

    /**
     * 挂在 OkHttpClient 上 ✓（见 `logic/network/Network.kt`）
     *
     * 开关关着时**整个拦截器只是在末尾记一次 bool 判断** ✓
     * 请求体 / 响应体要用 `Buffer` 复制 ✗（不能直接消费 ✗ 否则业务拿不到 ✓）
     */
    class Interceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            if (!PrefManager.recordHttpLog) return chain.proceed(chain.request())

            val req = chain.request()
            val t0 = System.currentTimeMillis()
            val reqBody = bodyToString(req)

            return try {
                val resp = chain.proceed(req)
                val ms = System.currentTimeMillis() - t0
                val respBody = runCatching {
                    resp.peekBody(MAX_BODY_CHARS.toLong() * 4).string()
                }.getOrDefault("")
                record(
                    method = req.method,
                    url = req.url.toString(),
                    status = resp.code,
                    elapsedMs = ms,
                    reqBody = reqBody,
                    respBody = respBody,
                )
                resp
            } catch (e: Throwable) {
                record(
                    method = req.method,
                    url = req.url.toString(),
                    status = 0,
                    elapsedMs = System.currentTimeMillis() - t0,
                    reqBody = reqBody,
                    error = "${e.javaClass.simpleName}: ${e.message ?: ""}",
                )
                throw e
            }
        }

        private fun bodyToString(req: Request): String {
            return try {
                val b = req.body ?: return ""
                if (b.isDuplex()) return "<duplex>"
                val buf = Buffer()
                b.writeTo(buf)
                buf.readUtf8()
            } catch (_: Throwable) {
                ""
            }
        }
    }
}

/**
 * Application context 持有者（给 `HttpLog.dir()` 兜底用 ✓）。
 *
 * 为什么需要它：`HttpLog.record()` 在 okhttp 线程上跑 ✗ 拿不到 Activity ✗
 * 而 `MyApplication.onCreate` 里会调 `init(ctx)` ✓ 理论上不需要这个兜底 ✗
 * 但设置页可能在 init 之前就查 stats（理论上不会 ✗）⇒ 留个保险 ✓
 */
object AppContextHolder {
    @Volatile
    var context: Context? = null
}
