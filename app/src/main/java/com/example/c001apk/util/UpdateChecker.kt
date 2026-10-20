package com.example.c001apk.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.c001apk.BuildConfig
import com.example.c001apk.MyApplication
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 本应用自更新检查。
 *
 * 升级信息直接查 GitHub Releases（`GET /repos/{owner}/{repo}/releases/latest`）：
 * CI 每发一次正式版都会建一个 `V1.0.x` 的 Release，并把正式包挂上去，
 * 所以 `tag_name` 就是版本名、`assets` 里就是下载地址，不需要额外的自建接口。
 *
 * 判定有更新：Release 的 versionCode > 当前 BuildConfig.VERSION_CODE。
 * versionCode 只从正式包的 asset 文件名取（`...-release.10009.apk`，
 * CI 生成时文件名带 `(10009)`，GitHub 会规范化成 `.10009`），不从 tag 推导 ——
 * CI 的 VERSION_NAME 与 VERSION_CODE 是各自独立 +1 的，从 tag 推算迟早会算错。
 *
 * 下载一律跳转外部浏览器打开，不在应用内下载。
 *
 * 关于统计：以前走自建接口时会顺带上报 `X-App-userrandomid` / SSAID / 安装来源，
 * 换成 GitHub 后这些都不再发送（GitHub 也不收）。[RemoteConfig] 仍走自建服务。
 */
object UpdateChecker {

    /** 本应用的 GitHub 仓库；fork 后改这里即可让更新检查指向自己的 Release */
    const val REPO_OWNER = "XiaoMengXinX"
    const val REPO_NAME = "c001apk_next"

    private const val API_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    /** 每个进程只做一次启动时自动检查（Activity 重建不会重复弹窗） */
    var checkedThisSession = false

    data class DownloadLine(val name: String, val url: String)

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Long,
        val changelog: String,
        val lines: List<DownloadLine>,
    ) {
        val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
    }

    /**
     * 独立客户端：不挂 Cookie / 日志拦截器 —— 更新信息来自 GitHub，
     * 不该把酷安的登录凭证带过去。
     *
     * 也不套 [SslVerify.apply]：那条路径在「校验 SSL 证书」开启时要求证书链锚定在
     * 随包内置的 Mozilla CA 库里，万一哪天证书链不合规，更新检查会在握手阶段
     * 静默失败（下面的 runCatching 会把异常吞掉，用户只看到「没有更新」），代价太大。
     * 常规校验交给 network_security_config（信任锚 = 仅内置 Mozilla CA，
     * 用户安装的抓包证书一样会被拒），这里只在「网络传输调试模式」下显式放开，
     * 否则裸 OkHttpClient 走平台默认校验，抓包证书永远过不去。
     */
    private val client by lazy {
        if (PrefManager.isSslDebug) SslVerify.applyDebug(OkHttpClient.Builder()).build()
        else OkHttpClient()
    }

    private val userAgent: String by lazy {
        val d = TokenDeviceUtils.detectRealDevice()
        "Dalvik/2.1.0 (Linux; U; Android ${d.androidVersion}; ${d.model} ${d.buildNumber}) " +
            "(#Build; ${d.brand}; ${d.model}; ${d.buildNumber}; ${d.androidVersion}) okhttp/4.12.0"
    }

    private fun installHeaders(): List<Pair<String, String>> = runCatching {
        val ctx = MyApplication.context
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val (installer, initiator) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val src = pm.getInstallSourceInfo(pkg)
            src.installingPackageName to src.initiatingPackageName
        } else {
            @Suppress("DEPRECATION")
            val legacy = pm.getInstallerPackageName(pkg)
            legacy to null
        }
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, 0)
        listOf(
            "X-Install-Source" to installer.orEmpty(),
            "X-Install-Origin" to initiator.orEmpty(),
            "X-Install-Time" to info.firstInstallTime.toString(),
            "X-Update-Time" to info.lastUpdateTime.toString(),
            "X-Client-Version" to BuildConfig.VERSION_NAME,
            "X-Client-Code" to BuildConfig.VERSION_CODE.toString(),
        ).filter { it.second.isNotEmpty() }
    }.getOrDefault(emptyList())

    private const val CACHE_TTL = 60_000L

    /** 最近一次查到的 Release（60 秒内复用：启动时查一次、用户点「立即检查」再查一次只打一次接口） */
    @Volatile
    private var cached: Pair<String, Long>? = null

    /**
     * 拉 GitHub 的 latest Release；失败返回 null
     * （调用方按「没有更新」「没有按钮」处理，不弹错误框）。
     */
    private suspend fun loadLatestRelease(): JSONObject? = withContext(Dispatchers.IO) {
        cached?.takeIf { System.currentTimeMillis() - it.second < CACHE_TTL }
            ?.let { return@withContext runCatching { JSONObject(it.first) }.getOrNull() }
        runCatching {
            val builder = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                // GitHub API 要求带 UA，否则 403；这里的 UA 同时也在做设备上报
                .header("User-Agent", userAgent)
            installHeaders().forEach { (name, value) -> builder.header(name, value) }
            val body = client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            } ?: return@runCatching null
            JSONObject(body).also { cached = it.toString() to System.currentTimeMillis() }
        }.getOrNull()
    }

    /**
     * 拉自建服务的其它 JSON 配置（用户认证表 / 可信链接白名单，见 [RemoteConfig]）。
     *
     * 与升级接口共用同一个 client、UA 与请求头：都是自有域名，没必要各建一套连接池，
     * 服务端也能统一按 X-App-userrandomid 统计。非 200 或异常返回 null，调用方保留旧缓存。
     */
    suspend fun fetchConfig(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("X-App-userrandomid", PrefManager.userRandomId)
                // ★ SSAID：清数据 / 重装不变 ✓ 云端据此去重（老的随机 ID 照旧上报 ✓）
                .header("X-Client-SSaid", PrefManager.ssaid)
                .header("User-Agent", userAgent)
            installHeaders().forEach { (name, value) -> builder.header(name, value) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }

    /**
     * 查正式版的更新信息。接口不通或没有可用 APK 时返回 null。
     */
    suspend fun fetchUpdate(): UpdateInfo? {
        val release = loadLatestRelease() ?: return null
        return parseRelease(release)
    }

    /**
     * 从 GitHub Release 的 JSON 里取出更新信息。
     *
     * 版本名取 `tag_name`（CI 建的是 `V1.0.9`），versionCode 从正式包的
     * asset 文件名解析（见 [versionCodeOf]）。没有正式包时返回 null。
     */
    fun parseRelease(release: JSONObject): UpdateInfo? = runCatching {
        val tag = release.stringOrEmpty("tag_name")
        if (tag.isEmpty()) return@runCatching null

        val assets = release.optJSONArray("assets")
            ?: return@runCatching null
        val lines = (0 until assets.length()).mapNotNull { i ->
            val o = assets.optJSONObject(i) ?: return@mapNotNull null
            val url = o.stringOrEmpty("browser_download_url")
            val name = o.stringOrEmpty("name")
            // 只认正式包：debug 包同样挂在 Release 上时不该被推荐
            if (url.isEmpty() || versionCodeOf(name) == null) null
            else DownloadLine(name, url)
        }
        if (lines.isEmpty()) return@runCatching null

        val versionCode = versionCodeOf(lines.first().name) ?: return@runCatching null

        UpdateInfo(
            versionName = tag,
            versionCode = versionCode,
            // Release 正文就是 CI 生成的更新说明（含提交号、构建时间、功能清单）
            changelog = release.stringOrEmpty("body"),
            lines = lines,
        )
    }.getOrNull()

    /**
     * 取字符串字段并 trim，JSON `null` / 缺失 / 空串都当作空串。
     *
     * 不能用裸 `optString`：它遇到 JSON null 会返回**字符串 "null"**。
     */
    private fun JSONObject.stringOrEmpty(key: String): String =
        if (isNull(key)) "" else optString(key).trim()

    /**
     * 从 asset 文件名里抠出 versionCode，例如
     * `c001apk_next-V1.0.9-release.10009.apk` → `10009`
     * （Gradle 生成的文件名带 `(10009)`，GitHub 上传时把括号规范化成了点）。
     *
     * 只认文件名、不从 tag 推导：CI 的 `VERSION_NAME` 与 `VERSION_CODE` 是**各自**
     * 独立 +1 的（V1.0.9 ↔ 10009 只是因为两者都从 0 起步），
     * 一旦哪天改成 V1.1.0 或跳号，从 tag 推算就会算错，而文件名里始终带着真实 code。
     */
    internal fun versionCodeOf(fileName: String): Long? =
        Regex("""-release\.(\d+)\.apk$""").find(fileName)?.groupValues?.get(1)?.toLongOrNull()

    /** 一律跳外部浏览器打开（下载、反馈群组都用它） */
    fun openExternal(context: Context, url: String, failTip: String = "无法打开链接") {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(context, failTip, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 更新弹窗：展示更新日志，多线路时可选择线路后跳外部浏览器下载。
     *
     * @param onIgnored 「不再提示」后的回调（用来刷新界面上的开关状态，可空）
     */
    fun showUpdateDialog(
        context: Context,
        info: UpdateInfo,
        onIgnored: (() -> Unit)? = null
    ) {
        val scrollView = ScrollView(context)
        val textView = TextView(context).apply {
            text = info.changelog.ifBlank { "无更新日志" }
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(context).apply {
            setTitle("发现新版本 ${info.versionName}")
            setView(scrollView)
            setNegativeButton(android.R.string.cancel, null)
            setNeutralButton("不再提示") { _, _ ->
                PrefManager.isCheckUpdateStable = false
                onIgnored?.invoke()
            }
            setPositiveButton("下载") { _, _ ->
                if (info.lines.size == 1) {
                    openExternal(context, info.lines[0].url, "打开下载链接失败")
                } else {
                    val names = info.lines.map { it.name }.toTypedArray()
                    MaterialAlertDialogBuilder(context).apply {
                        setTitle("选择下载线路")
                        setItems(names) { d, which ->
                            d.dismiss()
                            openExternal(context, info.lines[which].url, "打开下载链接失败")
                        }
                        setNegativeButton(android.R.string.cancel, null)
                        show()
                    }
                }
            }
            show()
        }
    }
}
