package com.example.c001apk.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.example.c001apk.BuildConfig
import com.example.c001apk.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * 本机签名校验：这个包是不是官方签的。
 *
 * 官方签名证书的 SHA-256 写死在下面，任何页面创建时都拿本包真实签名比一次，
 * 对不上、又**不是 debug 包**，就弹一个不可取消的窗并退出 —— 拒绝启动。
 *
 * 为什么放过 debug 包：debug 构建用的是 Android 默认调试签名，哈希必然对不上，
 * 本地直接跑、CI 打的 debug 渠道包都得能起来。注意 CI 的 debug 渠道包其实是用正式
 * keystore 签的（channel=debug 只是给 versionName 加后缀），它照样能过这里的校验。
 *
 * 这道校验是给「二次开发」看的，不是安全边界：改了包重新签名就跑不起来。
 * 云端升级接口里另有一套**独立**的签名核验（见 _rev/update_files/update/index.php 的
 * verify_client_signature，与 App 侧上报哈希的 UpdateChecker.packageSignature），
 * 两套各写一份、互不引用 —— 把这里删掉只是本地不再拦，上报的签名照样是假的，
 * 统计表收不到脏数据。**所以改这个文件时别顺手把上传那套「复用」过来。**
 */
object SignatureGuard {

    /** 官方包的签名证书 SHA-256（小写 hex，来自 CI 的 apksigner --print-certs 输出） */
    private const val OFFICIAL_SIGNATURE =
        "0cdc1f20f68160d15cca978ca5988d20f3d240bfb725a4a8e1b0f963875c920b"

    /** 本包签名是不是官方的；null = 还没查过（进程内只查一次） */
    private var official: Boolean? = null

    /** 弹窗是不是已经挂出来了（每个页面都建一次的话别弹一堆） */
    private var showing = false

    /** 在 Application.onCreate 里注册 */
    fun install(app: Application) {
        if (!isMainProcess(app)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (showing || isOfficial(activity)) return
                showing = true
                // 等这份 decorView 真正挂到窗口上再弹，onCreate 里直接 show 会 BadTokenException
                activity.window.decorView.post { showDialog(activity) }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun isOfficial(context: Context): Boolean {
        official?.let { return it }
        // debug 包不校验：调试签名必然对不上官方哈希
        val result = BuildConfig.DEBUG ||
            readSignatures(context).any { it == OFFICIAL_SIGNATURE }
        official = result
        return result
    }

    /** 本包所有签名者的证书 SHA-256（多签名时命中任一即可） */
    private fun readSignatures(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        @Suppress("DEPRECATION")
        val certs = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.toList()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.toList()
        }).orEmpty()
        certs.mapNotNull { cert ->
            runCatching {
                MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                    .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun showDialog(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.signature_error_title)
            .setMessage(R.string.signature_error_message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                activity.finishAffinity()
                exitProcess(0)
            }
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                setOnDismissListener { showing = false }
                show()
            }
    }

    /** :webview 是独立进程，那边也会重建一份 Application；只在主进程拦一次就够 */
    private fun isMainProcess(app: Application): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // API 28 以下没有 Application.getProcessName()，从 /proc 里读
            runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()
        }
        return name.isNullOrEmpty() || name == app.packageName
    }
}
