package com.example.c001apk.util

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.EditText
import com.example.c001apk.BuildConfig
import com.example.c001apk.R
import com.example.c001apk.ui.others.SzlmIdWebActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 「获取数字联盟 ID」知情同意弹窗。
 *
 * 正文列出会被上传的设备信息，两个出口：
 *  - 不同意，手动填入自己的 ID；
 *  - 知情并同意，打开内置网页（[SzlmIdWebActivity]，人机验证 + 签名校验）换取 DUID，
 *    结果落到 [PrefManager.SZLMID]。
 *
 * 触发点：应用启动时发现 SZLMID 为空（[RiskControlPrompter]），
 * 以及「设置 - 高级 - 数字联盟ID」被点击时。
 */
object SzlmIdPrompt {

    /**
     * 弹知情同意窗。
     * @param onDismiss 弹窗关闭（含两个按钮各自动作）后回调，调用方用它复位重入标记
     */
    fun showConsent(activity: Activity, onDismiss: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) {
            onDismiss?.invoke()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.szlm_id_consent_title)
            .setMessage(R.string.szlm_id_consent_message)
            .setNegativeButton(R.string.szlm_id_consent_manual) { d, _ ->
                d.dismiss()
                showManualInput(activity)
            }
            .setPositiveButton(R.string.szlm_id_consent_agree) { d, _ ->
                d.dismiss()
                openWeb(activity)
            }
            .setOnDismissListener { onDismiss?.invoke() }
            .show()
    }

    /** 手动填入框（不同意自动获取时走这里，与设置里的编辑框一致） */
    fun showManualInput(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = LayoutInflater.from(activity).inflate(R.layout.item_x_app_token, null, false)
        val editText: EditText = view.findViewById(R.id.editText)
        editText.setText(PrefManager.SZLMID)
        MaterialAlertDialogBuilder(activity).apply {
            setView(view)
            setTitle(R.string.szlmId)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                save(editText.text.toString().trim())
            }
            if (BuildConfig.DEBUG) {
                setNeutralButton(R.string.random_value) { _, _ ->
                    // 调试用：换一份随机 szlmId（不置 szlmIdConfigured，仍算未配置）
                    PrefManager.SZLMID = TokenDeviceUtils.randHexString(16)
                    PrefManager.szlmIdNoticed = true
                    rebuildDeviceString()
                }
            }
        }.create().apply {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            editText.requestFocus()
        }.show()
    }

    /**
     * 打开内置网页换取 DUID（人机验证 + App 签名校验都在网页侧和服务端完成）。
     *
     * 不再由 App 直连接口：`szlmid/request_api.php` 已停用，直连只会拿到
     * 「请求被拒绝，请升级最新版本」。参数经 JsBridge 交给网页，结果由网页回调回来，
     * 保存动作见 [SzlmIdWebActivity.onIssued]。
     */
    private fun openWeb(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching { activity.startActivity(Intent(activity, SzlmIdWebActivity::class.java)) }
    }

    /** 落盘 + 重建设备串（szlmId 是设备串首字段，改完必须重造才生效） */
    internal fun save(id: String) {
        PrefManager.SZLMID = id
        // 填过（非空）才算「已配置」；清空则回到未配置状态
        PrefManager.szlmIdConfigured = id.isNotEmpty()
        PrefManager.szlmIdNoticed = true
        rebuildDeviceString()
    }

    /**
     * szlmId 与机型是两件独立的事：这里只重建设备串本身，既不写机型字段，
     * 也不碰 [PrefManager.customFingerprint] —— 换 ID 不该把用户设过的机型冲掉。
     *
     * 只在用户自定义过机型时就地重造：其余情况设备串本来就用默认那一组，
     * 由 [TokenDeviceUtils.buildDeviceCode] 在读取时按当前 szlmId 现算，不需要提前写回。
     *
     * 注意：改用 iOS 身份后这条设备串已不参与请求头（请求走 [IosHeaders]），
     * 只影响设置页展示的 `X-App-Device`。
     */
    private fun rebuildDeviceString() {
        if (PrefManager.customFingerprint) {
            PrefManager.xAppDevice = TokenDeviceUtils.getDeviceCode()
        }
    }
}
