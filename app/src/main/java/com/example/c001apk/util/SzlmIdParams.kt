package com.example.c001apk.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.webkit.WebSettings
import com.example.c001apk.BuildConfig
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 「获取数字联盟 ID」流程的入参构造：交给 App 内置网页（szlmid/webgetv1）经 JsBridge 取用。
 *
 * 网页端把这几项 POST 给服务端换取 DUID，服务端用 `deviceinfo` 的**原文**做指纹派生，
 * 所以三段内容必须稳定：同一台机器每次生成要逐字节一致，不能掺时间戳 / 随机数 /
 * 用户可改的伪装值。见各私有函数注释。
 *
 * 历史：这里原来挂在 SzlmIdApi 上，直连 `szlmid/request_api.php` 取号；该入口现在只会回
 * 「请求被拒绝，请升级最新版本」，取号统一改走带人机验证的内置网页，本文件只负责备料。
 */
object SzlmIdParams {

    /**
     * 官方包的签名证书 SHA-256（小写 hex）。
     *
     * 与 [SignatureGuard] 里那份、以及 [UpdateChecker] 上报给服务端的那份**各自独立维护**：
     * 三处签名校验互不复用，任何一处被二次开发者图省事删掉/绕过，其余两处仍然生效。
     */
    const val OFFICIAL_SIGNATURE =
        "9e7692d0d3f996476c81be80841fc638530eeb1285c8ba325b65ca47af190ec8"

    /** 内置网页地址（人机验证 + 签名校验都过了才签发） */
    const val WEB_ENTRY = "https://service.houlangs.cn/c001apk/szlmid/webgetv1/"

    /**
     * App 内置 WebView 的 UA 私有标记。
     *
     * 服务端要求请求的 User-Agent 同时带 `wv`（Android WebView 固有标记）和这个标记，
     * 才会走下一条校验 —— 于是"在浏览器里直接打开网页"这条路拿不到号。
     * 标记放在 UA 末尾，前面仍是真实浏览器 UA，不影响 Turnstile 判定。
     */
    const val WEB_UA_TAG = "C001ApkWebView"

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 组装给内置网页的全部参数（一次性备齐，JsBridge 被回调时同步返回）。
     */
    suspend fun buildForWeb(ctx: Context): String {
        // WebView 默认 UA 必须在主线程取，先拿到再进 IO
        val ua = withContext(Dispatchers.Main) { realUserAgent(ctx) }
        return withContext(Dispatchers.IO) {
            JSONObject().apply {
                put("ua", ua)
                put("deviceinfo", deviceInfoJson(ctx))
                put("appinfo", appInfoJson(ctx))
                put("signature", signature(ctx))
            }.toString()
        }
    }

    /**
     * `deviceinfo`：**本机真实**机型参数 + SSAID。
     *
     * 刻意绕开 [PrefManager.MANUFACTURER] / [PrefManager.MODEL] 等字段：那些会被
     * 「设置 - 机型参数」改写成伪装值（甚至随机值），而服务端拿这个 JSON 当指纹来源，
     * 一旦被改就会每次换一个新 DUID、白耗取号额度。这里直接读 [Build]
     * （走 [TokenDeviceUtils.detectRealDevice]），再加 SSAID，保证原文稳定。
     *
     * SSAID（`Settings.Secure.ANDROID_ID`）只用于让服务端唯一认出这台设备。
     */
    private fun deviceInfoJson(ctx: Context): String {
        val d = TokenDeviceUtils.detectRealDevice()
        val ssaid = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        // 手拼而非 JSONObject：服务端按原文比对，键顺序必须固定
        return buildString {
            append('{')
            append("\"manufacturer\":").append(JSONObject.quote(d.manufacturer)).append(',')
            append("\"brand\":").append(JSONObject.quote(d.brand)).append(',')
            append("\"model\":").append(JSONObject.quote(d.model)).append(',')
            append("\"build\":").append(JSONObject.quote(d.buildNumber)).append(',')
            append("\"android\":").append(JSONObject.quote(d.androidVersion)).append(',')
            append("\"sdk\":").append(JSONObject.quote(d.sdkInt)).append(',')
            append("\"ssaid\":").append(JSONObject.quote(ssaid))
            append('}')
        }
    }

    /**
     * `appinfo`：本应用**真实**版本信息（服务端只记日志，不参与业务）。
     *
     * 用 [BuildConfig] 而非 [PrefManager.VERSION_NAME] —— 后者可能被伪装成酷安版本号，
     * 这里要的是这个客户端自己的版本。
     */
    private fun appInfoJson(ctx: Context): String = buildString {
        append('{')
        append("\"ver\":").append(JSONObject.quote(BuildConfig.VERSION_NAME)).append(',')
        append("\"code\":").append(BuildConfig.VERSION_CODE).append(',')
        append("\"pkg\":").append(JSONObject.quote(ctx.packageName)).append(',')
        append("\"debug\":").append(BuildConfig.DEBUG)
        append('}')
    }

    /**
     * app 真实 UA：取系统 WebView 默认 UA（`Mozilla/5.0 (Linux; Android …) Chrome/…`）。
     *
     * 明确不用两种假 UA：OkHttp 自动附加的 `okhttp/4.x`，以及给酷安接口伪装用的
     * iOS UA（[IosDeviceUtils.coolapkUserAgent]）。
     * 主线程/WebView 不可用时回落到自报名格式，宁可信息少也不给假 UA。
     */
    private fun realUserAgent(ctx: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(ctx) }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "c001apk_next/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"

    /**
     * 交给内置 WebView 用的 UA：系统默认 UA + [WEB_UA_TAG]。
     *
     * 只有本 Activity 的 WebView 会带这个标记，服务端据此确认请求来自 App 内部。
     * （纯粹的痕迹校验，不是密钥 —— 真正拦人的是签名白名单和人机验证。）
     */
    fun webUserAgent(ctx: Context): String = "${realUserAgent(ctx)} $WEB_UA_TAG"

    /**
     * 本包签名证书的 SHA-256（小写 hex；多签名时取第一个），随参数一起交给网页上报。
     *
     * 独立实现：**刻意不调** [UpdateChecker] / [SignatureGuard] 里那两份同类逻辑。
     * 服务端拿它核验是不是官方包，不是就拒绝签发（对外只说「访问被拒绝」，原因只写服务端日志）。
     * 二次开发的人绕过弹出的自检弹窗时，只要没顺手改这里，上报的仍然是他真实包的签名。
     */
    @Suppress("DEPRECATION")
    private fun signature(ctx: Context): String = runCatching {
        val pm: PackageManager = ctx.packageManager
        val pkg = ctx.packageName
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()?.toByteArray()
        } ?: return ""

        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        sb.toString()
    }.getOrDefault("")
}
