package com.example.c001apk

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import com.example.c001apk.ui.others.BugHandlerActivity
import com.example.c001apk.util.PrefManager
import com.example.c001apk.util.RemoteConfig
import com.example.c001apk.util.RiskControlPrompter
import com.example.c001apk.util.ShareLinkPrompter
import com.example.c001apk.util.SignatureGuard
import com.example.c001apk.util.SslErrorPrompter
import com.example.c001apk.util.SslVerify
import dagger.hilt.android.HiltAndroidApp
import net.mikaelzero.mojito.Mojito
import net.mikaelzero.mojito.loader.glide.GlideImageLoader
import net.mikaelzero.mojito.view.sketch.SketchImageLoadFactory
import net.mikaelzero.mojito.view.sketch.core.Sketch
import kotlin.system.exitProcess

@HiltAndroidApp
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        context = applicationContext

        // SSL 校验失败 → 风险环境警告弹窗（跟踪前台 Activity）
        SslErrorPrompter.install(this)

        // 风控命中（-415 账号过多 / err_request_captcha_v2）→ 「设备标识未配置」引导弹窗
        RiskControlPrompter.install(this)

        // 每次进入应用读一次剪贴板：是酷安分享链接就弹「是否打开」
        ShareLinkPrompter.install(this)

        // 本机签名校验：装的不是官方包（且非 debug 包）就弹窗拒绝启动。
        // 与升级接口里那套云端签名核验各写一份、互不引用，见 SignatureGuard 的注释
        SignatureGuard.install(this)

        // 网络传输调试模式（设置 - 高级）：放开进程级 HttpsURLConnection 默认校验，
        // 不放开的话抓包时走系统默认栈的图片会全部加载失败
        SslVerify.applyDebugGlobally()

        AppCompatDelegate.setDefaultNightMode(PrefManager.darkTheme)

        // 匿名统计用的用户随机 ID（useradomid）：第一次启动就生成并落盘，之后不再变化。
        // 读取本身就会生成，这里显式读一次只是为了把生成时机钉在「首次启动」，
        // 顺带保证自更新接口第一次打请求时就已经有值（见 PrefManager.userRandomId）
        PrefManager.userRandomId

        // 系统派发的本应用设备标识（SSAID）：**清数据 / 重装都不变** ✓
        // 用来给统计去重（随机 ID 在这两种情况下会变成新的一串 ✗）
        // 只在启动时读一次并落盘，避免每帧都走 ContentResolver ✓
        runCatching {
            val ssaid = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
            if (!ssaid.isNullOrEmpty()) PrefManager.ssaid = ssaid
        }

        // ★ 跟踪前台 Activity —— 「szlmId 失效」提示要弹窗，必须有 Activity ✗
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) =
                com.example.c001apk.util.SzlmHint.track(activity)

            override fun onActivityPaused(activity: android.app.Activity) =
                com.example.c001apk.util.SzlmHint.track(null)

            override fun onActivityCreated(activity: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })

        // HTTP 请求日志的目录（设置 → 高级 里可开关 / 导出 / 清空 ✓）
        // 只是建一个目录 + 记下 context ✓ 开关关着时完全不写盘 ✓
        runCatching {
            com.example.c001apk.util.AppContextHolder.context = applicationContext
            com.example.c001apk.util.HttpLog.init(this)
        }

        // 云端配置（用户认证表 / 可信链接白名单）：先用上次缓存，再后台刷新一次。
        // 放在这里而不是各页面里拉，是因为认证标散落在列表、详情、消息各处，
        // 每处各拉一次没必要，而且首次进入时未必已经有表。
        RemoteConfig.initFromCache()
        RemoteConfig.refreshAsync()

        // 图片加载同样走 OkHttp（Mojito 的 Glide 会替换 GlideUrl 加载器）。
        // 这个客户端必须给：图片 CDN 按 UA 放行，没 UA 会被判 567 直接黑屏，
        // SslVerify.imageClient() 会补上酷安 UA（并在调试模式下放开证书校验）
        Mojito.initialize(
            GlideImageLoader.with(this, SslVerify.imageClient()),
            SketchImageLoadFactory()
        )

        // 全屏看图器里的图是交给 Sketch 显示的（列表图片走 Glide），所以 Sketch 只有点开大图时才会
        // 第一次被用到。Sketch.with() 是进程级单例，首次调用会在当前线程同步构造 Configuration：
        // 建磁盘缓存、位图池、内存池、线程池，还要扫一遍缓存目录、读一次 PackageManager 元数据，
        // 在主线程上就是几百毫秒 —— 表现正好是第一次点开图片会卡/闪一下，退出去再进来就好了
        // （那时单例已经建好）。这里冷启动时后台预热，把这份一次性开销从点开的瞬间挪走。
        Thread {
            runCatching { Sketch.with(this) }.onFailure {
                android.util.Log.w("MyApplication", "Sketch 预热失败", it)
            }
        }.start()

        Thread.setDefaultUncaughtExceptionHandler { _, paramThrowable ->
            val exceptionMessage = android.util.Log.getStackTraceString(paramThrowable)

            val intent = Intent(this, BugHandlerActivity::class.java)
            intent.putExtra("exception_message", exceptionMessage)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)

            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
    }

}