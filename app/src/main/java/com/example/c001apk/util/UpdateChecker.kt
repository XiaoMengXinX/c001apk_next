package com.example.c001apk.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.c001apk.BuildConfig
import com.example.c001apk.MyApplication
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本应用自更新检查。
 *
 * 升级信息走自建接口（顺带给服务端做匿名访问统计）：
 *
 *   GET https://service.houlangs.cn/c001apk/update/
 *   请求头 X-App-userrandomid：本应用自造的随机用户 ID（见 [PrefManager.userRandomId]）。
 *   值不再拿数字联盟 DUID（那玩意能反查到具体设备），是本机随机生成、
 *   与账号设备都无关的 `userrandomid`；服务端据此统计活跃设备 / 用户量。
 *
 * 一次请求同时返回两段（服务端：仓库 _rev/update_files/index.php）：
 * {
 *   "code": 0, "message": "ok", "time": 1789806080,
 *   "stable": { 正式版 }, "org": { 关于页按钮 }
 * }
 *
 * 单段 JSON 格式：
 * {
 *   "versionName": "1.0.1",
 *   "versionCode": 468,
 *   "changelog": "## 更新日志\n- xxx（markdown 文本）",
 *   "download": [
 *     {"name": "线路一", "url": "https://..."},
 *     {"name": "线路二", "url": "https://..."}
 *   ]
 * }
 *
 * versionCode 大于当前 BuildConfig.VERSION_CODE 才算有更新；
 * 下载一律跳转外部浏览器打开，不在应用内下载。
 *
 * 只查**正式版**：Beta 通道连同开关、Preference 一起删了（2026-10-07），
 * 服务端响应里的 beta 段即使还在也不会被读到。
 */
object UpdateChecker {

    /**
     * 自建更新接口；stable / org 都在同一次响应里，不用分别请求。
     * 服务端 2026-10-07 把升级 php 从 c001apk/ 挪到了 c001apk/update/，
     * 旧地址只留一个 302 给没升级的老客户端，这里直接用新地址。
     */
    const val BASE_URL = "https://service.houlangs.cn/c001apk/update/"

    /**
     * 关于页「组织」按钮配置（响应里的 org 段），随时可下发/改名/换链接：
     *
     *   {"buttons": [{"name": "官方群组", "url": "https://..."}]}
     * 也兼容 {"name": "...", "url": "..."} 和裸数组 [{"name": "...", "url": "..."}]
     *
     * 点击后强制跳外部浏览器打开。
     */

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
     * 独立客户端：不挂 Cookie / 日志拦截器 —— 更新接口是自建服务，
     * 不该把酷安的登录凭证带过去。
     *
     * 也不套 [SslVerify.apply]：那条路径在「校验 SSL 证书」开启时要求证书链锚定在
     * 随包内置的 Mozilla CA 库里，万一自建服务器的证书链不合规，更新检查会在握手阶段
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
            // 本包真实签名证书的 SHA-256：服务端据此核验是不是官方包
            // （不是就直接拒，连升级信息都不给），见接口里的 verify_client_signature()
            "X-App-Signature" to packageSignature(pm, pkg).orEmpty(),
        ).filter { it.second.isNotEmpty() }
    }.getOrDefault(emptyList())

    /**
     * 本包签名证书的 SHA-256（小写 hex；多签名时取第一个）。
     *
     * 独立实现：**刻意不去调 [SignatureGuard] 里那套同类逻辑**。本机那套是弹给用户看的，
     * 二次开发的人图省事把它删掉时，只要没顺手把这里也改了，上报的仍然是他真实包的签名，
     * 服务端就能认出不是官方包，统计表进不了脏数据。改这个方法之前先想清楚这一点。
     */
    private fun packageSignature(pm: PackageManager, pkg: String): String? = runCatching {
        @Suppress("DEPRECATION")
        val certs = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.toList()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.toList()
        }).orEmpty()
        certs.firstOrNull()?.let { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        }
    }.getOrNull()

    /** 自建接口一次响应里的各段 JSON（原样留着，按需取用） */
    private class Snapshot(
        val stable: String?,
        val org: String?,
        val at: Long,
    )

    private const val CACHE_TTL = 60_000L

    /** 同一份响应 60 秒内复用：启动时自动查一次、用户点「立即检查」再查一次，只会打一次接口 */
    @Volatile
    private var cached: Snapshot? = null

    /**
     * 拉取自建接口并拆成 stable / org 两段；失败返回 null
     * （调用方按「没有更新」「没有按钮」处理，不弹错误框）。
     *
     * X-App-userrandomid 传 [PrefManager.userRandomId]（本机随机 32 位 hex、首启生成后不变），
     * 服务端据此统计活跃设备 / 用户量。
     */
    private suspend fun loadSnapshot(): Snapshot? = withContext(Dispatchers.IO) {
        cached?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL }
            ?.let { return@withContext it }
        runCatching {
            val builder = Request.Builder()
                .url(BASE_URL)
                .header("X-App-userrandomid", PrefManager.userRandomId)
                // ★ SSAID：清数据 / 重装不变 ✓ 云端据此去重（老的随机 ID 照旧上报 ✓）
                .header("X-Client-SSaid", PrefManager.ssaid)
                .header("User-Agent", userAgent)
            installHeaders().forEach { (name, value) -> builder.header(name, value) }
            val request = builder.build()
            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            } ?: return@runCatching null
            val obj = JSONObject(body)
            Snapshot(
                stable = obj.optJSONObject("stable")?.toString(),
                org = obj.optJSONObject("org")?.toString(),
                at = System.currentTimeMillis(),
            ).also { cached = it }
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
     * 查正式版的更新信息。接口不通或该段缺失时返回 null。
     */
    suspend fun fetchUpdate(): UpdateInfo? {
        val snapshot = loadSnapshot() ?: return null
        return snapshot.stable?.let { parse(it) }
    }

    fun parse(json: String): UpdateInfo? = runCatching {
        val obj = JSONObject(json)
        val lines = obj.optJSONArray("download")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("url")
                if (u.isBlank()) null
                else DownloadLine(o.optString("name").ifBlank { "线路${i + 1}" }, u)
            }
        }.orEmpty()
        UpdateInfo(
            versionName = obj.optString("versionName"),
            versionCode = obj.optLong("versionCode", -1L),
            changelog = obj.optString("changelog"),
            lines = lines,
        ).takeIf { it.versionCode > 0 && it.lines.isNotEmpty() }
    }.getOrNull()

    data class OrgLink(val name: String, val url: String)

    /** 关于页「组织」按钮配置；失败 / 缺失返回空表（不显示按钮） */
    suspend fun fetchOrgLinks(): List<OrgLink> {
        val json = loadSnapshot()?.org ?: return emptyList()
        return parseOrgLinks(json)
    }

    fun parseOrgLinks(json: String): List<OrgLink> = runCatching {
        val text = json.trim()
        val arr = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            val obj = JSONObject(text)
            obj.optJSONArray("buttons") ?: obj.optJSONArray("groups")
            ?: return@runCatching listOfNotNull(parseOrgLink(obj))
        }
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseOrgLink(it) } }
    }.getOrDefault(emptyList())

    private fun parseOrgLink(o: JSONObject): OrgLink? {
        val url = o.optString("url")
        if (url.isBlank()) return null
        return OrgLink(o.optString("name").ifBlank { "加入群组" }, url)
    }

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
