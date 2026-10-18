package com.example.c001apk.util

import android.app.Activity
import android.content.Context
import android.util.Log
import com.example.c001apk.R
import com.example.c001apk.ui.others.SzlmIdWebActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 「可能是数字联盟 ID（szlmId / DUID）失效」的识别与提示。
 *
 * ## 为什么需要它
 * 2026-10-18 实测（30 个业务接口 × 旧号 / 随机号）：
 * 设备标识失效时服务器**仍回 HTTP 200** ✗，错误藏在 JSON 的 `data` 字符串里 ✓
 *
 * | 坏 szlmId | 受影响接口 | 服务器文案 |
 * |---|---|---|
 * | 换号前的旧值（过期）| 动态详情 / 动态回复 / 楼中楼 | `评论接口异常` |
 * | 同上 | 动态详情 | `检测到当前版本存在异常，…请使用官方APP访问(-419)` |
 * | 随机值 | 动态详情 / 动态回复 / 楼中楼 | `当前访问需要验证码` |
 *
 * 其余 27 个接口（首页 / 用户 / 通知 / 私信 / 收藏 / 搜索 / 应用 …）**不受影响** ✓
 *
 * ## 为什么在 OkHttp 层做
 * 这些文案出现在 `data` 里，而 `data` 本应是**数组** ✗
 * ⇒ Retrofit + Gson 直接抛 `JsonSyntaxException`（"Expected BEGIN_ARRAY but was STRING"）✗
 * ⇒ 到了 ViewModel 手里**原文已经丢了** ✗ 没法判断 ✗
 * ⇒ 只能在**响应体还是原始字符串**的时候看 ✓（见 [Interceptor]）
 */
object SzlmHint {

    private const val TAG = "SzlmHint"

    /** 命中这些子串 ⇒ 判定为设备标识失效 ✓ */
    private val MARKERS = listOf(
        "评论接口异常",
        "当前访问需要验证码",
        "检测到当前版本存在异常",
        "(-419)",
    )

    /** 错误体都很小（实测 46~352 字节 ✓）；超过这个字节数就不看了 ✗ 免得白读大响应 */
    private const val MAX_PEEK = 4096L

    /** 一次失效会连续触发很多请求 ✗ 60 秒内只弹一次 ✓ */
    private const val COOLDOWN_MS = 60_000L

    @Volatile
    private var lastShownAt = 0L

    /**
     * 当前前台 Activity —— 弹窗必须要 Activity ✗ Application context 不行 ✗
     * 由 [com.example.c001apk.MyApplication] 的 lifecycle 回调维护 ✓
     */
    @Volatile
    private var currentActivity: Activity? = null

    fun track(activity: Activity?) {
        currentActivity = activity
    }

    /** 这条文案是不是「szlmId 失效」类 ✓ */
    fun matches(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return MARKERS.any { message.contains(it) }
    }

    /**
     * 命中就弹一次「去重新取号」提示 ✓（主线程 ✓ 60 秒节流 ✓）
     *
     * @param context 调用方的 Context（拿不到 Activity 时用它兜底 ✗ 这种情况下不弹 ✗）
     */
    fun maybeShow(context: Context?, message: String?) {
        if (!matches(message)) return
        val now = System.currentTimeMillis()
        if (now - lastShownAt < COOLDOWN_MS) return
        lastShownAt = now

        val activity = currentActivity ?: (context as? Activity)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "命中 szlmId 失效文案但没有可用 Activity，跳过弹窗：${message?.take(40)}")
            return
        }

        activity.runOnUiThread {
            runCatching {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.szlm_hint_title)
                    .setMessage(R.string.szlm_hint_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.szlm_hint_action) { _, _ ->
                        runCatching {
                            activity.startActivity(
                                android.content.Intent(activity, SzlmIdWebActivity::class.java)
                            )
                        }
                    }
                    .show()
            }.onFailure { Log.w(TAG, "弹窗失败: $it") }
        }
    }

    /**
     * 挂在 OkHttpClient 上的**只读**观察者 ✓
     *
     * - 只处理「响应体很小」的响应 ✓（错误体都是几十~几百字节 ✓）
     * - 用 `peekBody` **不消费** body ✓（业务照常能读 ✓）
     * - 任何异常都吞掉 ✓ —— 绝不能影响正常请求 ✓
     */
    class Interceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            try {
                val len = response.body?.contentLength() ?: -1L
                // 错误体一定很小 ✓ 大响应直接跳过 ✗（不白拷 200KB 的列表 ✗）
                if (len in 0..MAX_PEEK) {
                    val text = response.peekBody(MAX_PEEK).string()
                    if (matches(text)) {
                        Log.w(TAG, "命中 szlmId 失效文案：${text.take(80)}")
                        maybeShow(AppContextHolder.context, text)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "检查响应体失败: $e")
            }
            return response
        }
    }
}
