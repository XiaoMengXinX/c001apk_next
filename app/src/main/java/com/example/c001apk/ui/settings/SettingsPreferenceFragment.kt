package com.example.c001apk.ui.settings

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import com.example.c001apk.BuildConfig
import com.example.c001apk.R
import com.example.c001apk.ui.blacklist.BlackListActivity
import com.example.c001apk.ui.main.MainActivity
import com.example.c001apk.ui.others.AboutActivity
import com.example.c001apk.ui.others.CopyActivity
import com.example.c001apk.ui.settings.params.ParamsActivity
import com.example.c001apk.util.CacheDataManager
import com.example.c001apk.util.IntentUtil
import com.example.c001apk.util.PrefManager
import com.example.c001apk.util.SzlmIdPrompt
import com.example.c001apk.util.TransitionAnim
import com.example.c001apk.util.doOnMainThreadIdle
import com.example.c001apk.util.setBottomPaddingSpace
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import rikka.core.util.ResourceUtils
import rikka.material.preference.MaterialSwitchPreference
import rikka.preference.SimpleMenuPreference
import kotlin.system.exitProcess
import com.example.c001apk.util.HttpLog
import java.util.Locale

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    /** 待写出的导出内容（等用户选完保存位置再写 ✓） */
    private var pendingExport: Pair<String, String>? = null

    /** 导出 HTTP 日志的请求码（SAF：ACTION_CREATE_DOCUMENT ✓） */
    private val REQ_EXPORT_HTTP_LOG = 1001

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView =
            super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        recyclerView.apply {
            //overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy > 0) {
                        (activity as? MainActivity)?.hideNavigationView()
                    } else if (dy < 0) {
                        (activity as? MainActivity)?.showNavigationView()
                    }
                }
            })

            doOnMainThreadIdle {
                recyclerView.setBottomPaddingSpace()
            }

        }
        return recyclerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(resources.getDrawable(R.drawable.divider, requireContext().theme))
    }

    /**
     * 进入二级设置页：改为打开独立的 [SettingsDetailActivity]。
     *
     * 不能再在本 Activity 内压 Fragment 返回栈 + setCustomAnimations：Fragment 1.7.0 的
     * 预测性返回只对 Animator（res/animator）与 AndroidX Transition 1.5.0+ 生效，
     * res/anim 的 View 动画拿不到跟手预览 —— 右滑时二级页纹丝不动，和帖子详情页手感不一致。
     * 独立 Activity 则由系统处理跨 Activity 预测性返回，预览正常。
     */
    override fun onNavigateToScreen(preferenceScreen: PreferenceScreen) {
        val key = preferenceScreen.key ?: return
        IntentUtil.startActivity<SettingsDetailActivity>(requireContext()) {
            putExtra(SettingsDetailActivity.ARG_ROOT_KEY, key)
        }
    }

    /** 一级页标题固定是「设置」，二级页标题取当前子屏的 title（两个 Activity 共用本 Fragment） */
    private fun syncToolbarTitle() {
        val title = preferenceScreen?.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.tab_setting)
        (activity as? AppCompatActivity)?.supportActionBar?.title = title
    }

    override fun onResume() {
        super.onResume()
        syncToolbarTitle()
        // 开关可能被别处改动（如风险弹窗里关闭了「校验 SSL 证书」），回到页面时重新同步互斥状态
        syncSslDebugState()
    }

    class SettingsPreferenceDataStore : PreferenceDataStore() {
        override fun getString(key: String?, defValue: String?): String {
            return when (key) {
                "darkTheme" -> PrefManager.darkTheme.toString()
                "themeColor" -> PrefManager.themeColor
                "animCurve" -> PrefManager.animCurve
                "animType" -> PrefManager.animType
                "animDuration" -> PrefManager.animDuration.toString()
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putString(key: String?, value: String?) {
            when (key) {
                "darkTheme" -> PrefManager.darkTheme = value?.toInt() ?: 0
                "themeColor" -> PrefManager.themeColor = value ?: "MATERIAL_DEFAULT"
                "animCurve" -> PrefManager.animCurve =
                    value ?: TransitionAnim.CURVE_M3_EMPHASIZED

                "animType" -> PrefManager.animType = value ?: TransitionAnim.TYPE_SLIDE
                "animDuration" -> PrefManager.animDuration = value?.toIntOrNull() ?: 300
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "recordHttpLog" -> PrefManager.recordHttpLog
                "blackDarkTheme" -> PrefManager.blackDarkTheme
                "followSystemAccent" -> PrefManager.followSystemAccent
                "showEmoji" -> PrefManager.showEmoji
                "customToken" -> PrefManager.customToken
                "isRecordHistory" -> PrefManager.isRecordHistory
                "isIconMiniCard" -> PrefManager.isIconMiniCard
                "isOpenLinkOutside" -> PrefManager.isOpenLinkOutside
                "isColorFilter" -> PrefManager.isColorFilter
                "verifySsl" -> PrefManager.isVerifySsl
                "sslDebug" -> PrefManager.isSslDebug
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
                "recordHttpLog" -> PrefManager.recordHttpLog = value
                "blackDarkTheme" -> PrefManager.blackDarkTheme = value
                "followSystemAccent" -> PrefManager.followSystemAccent = value
                "showEmoji" -> PrefManager.showEmoji = value
                "customToken" -> PrefManager.customToken = value
                "isRecordHistory" -> PrefManager.isRecordHistory = value
                "isIconMiniCard" -> PrefManager.isIconMiniCard = value
                "isOpenLinkOutside" -> PrefManager.isOpenLinkOutside = value
                "isColorFilter" -> PrefManager.isColorFilter = value
                "verifySsl" -> PrefManager.isVerifySsl = value
                "sslDebug" -> PrefManager.isSslDebug = value
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }
    }

    @SuppressLint("SetTextI18n", "InflateParams")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = SettingsPreferenceDataStore()
        setPreferencesFromResource(R.xml.settings, rootKey)

        findPreference<SimpleMenuPreference>("darkTheme")?.setOnPreferenceChangeListener { _, newValue ->
            val newMode = (newValue as String).toInt()
            if (PrefManager.darkTheme != newMode) {
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
            true
        }

        findPreference<MaterialSwitchPreference>("blackDarkTheme")?.setOnPreferenceChangeListener { _, _ ->
            if (ResourceUtils.isNightMode(requireContext().resources.configuration))
                activity?.recreate()
            true
        }

        findPreference<MaterialSwitchPreference>("followSystemAccent")?.setOnPreferenceChangeListener { _, _ ->
            activity?.recreate()
            true
        }

        findPreference<SimpleMenuPreference>("themeColor")?.setOnPreferenceChangeListener { _, _ ->
            activity?.recreate()
            true
        }

        findPreference<MaterialSwitchPreference>("showEmoji")?.setOnPreferenceChangeListener { _, _ ->
            activity?.recreate()
            true
        }

        // 「关于」已提到一级，点击直接打开关于页（不再有中间的二级目录）
        findPreference<Preference>("about")?.apply {
            summary = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
            setOnPreferenceClickListener {
                IntentUtil.startActivity<AboutActivity>(requireContext()) {}
                true
            }
        }


        findPreference<Preference>("params")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<ParamsActivity>(requireContext()) {}
            true
        }

        // 「数字联盟ID」：与启动时同一个知情同意窗（同意则自动获取，不同意则手动填入）
        findPreference<Preference>("szlmId")?.setOnPreferenceClickListener {
            activity?.let { SzlmIdPrompt.showConsent(it) }
            true
        }

        findPreference<Preference>("userBlackList")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<BlackListActivity>(requireContext()) {
                putExtra("type", "user")
            }
            true
        }

        findPreference<Preference>("topicBlackList")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<BlackListActivity>(requireContext()) {
                putExtra("type", "topic")
            }
            true
        }

        findPreference<Preference>("spamWord")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<SpamShieldActivity>(requireContext()) {
                putExtra("type", "word")
            }
            true
        }

        findPreference<Preference>("spamUser")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<SpamShieldActivity>(requireContext()) {
                putExtra("type", "user")
            }
            true
        }

        findPreference<Preference>("spamNode")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<SpamShieldActivity>(requireContext()) {
                putExtra("type", "node")
            }
            true
        }

        findPreference<Preference>("fontScale")?.setOnPreferenceClickListener {
            val view = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_font_scale, null, false)
            val slider: Slider = view.findViewById(R.id.slider)
            val fontScale: TextView = view.findViewById(R.id.fontScale)
            slider.apply {
                valueFrom = 0.80f
                valueTo = 1.30f
                value = PrefManager.FONTSCALE.toFloat()
                setLabelFormatter { value ->
                    String.format("%.2f", value)
                }
            }
            slider.addOnChangeListener { _, value, _ ->
                fontScale.text = "字体大小: ${String.format("%.2f", value)}"
                fontScale.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * value)
            }
            fontScale.text = "字体大小: ${PrefManager.FONTSCALE}"
            fontScale.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * PrefManager.FONTSCALE.toFloat())
            MaterialAlertDialogBuilder(requireContext()).apply {
                setView(view)
                setTitle(R.string.font_scale)
                setNegativeButton(android.R.string.cancel, null)
                setNeutralButton("重置") { _, _ ->
                    PrefManager.FONTSCALE = "1.00"
                    (requireActivity() as? MainActivity)?.recreate()
                }
                setPositiveButton(android.R.string.ok) { _, _ ->
                    PrefManager.FONTSCALE = String.format("%.2f", slider.value)
                    (requireActivity() as? MainActivity)?.recreate()
                }
                show()
            }
            true
        }


        // 首页工具栏原来那个「编辑Tab」按钮，位置让给消息入口后挪到了这里
        findPreference<Preference>("editTab")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<CopyActivity>(requireContext()) {
                putExtra("type", "homeMenu")
            }
            true
        }


        // ── HTTP 请求日志（2026-10-18）──────────────────
        // 开关走 PreferenceDataStore（见 getBoolean 分支 ✓）；这三项是「动作」
        fun httpLogSummary(): String {
            val (bytes, files, lineCount) = HttpLog.stats(requireContext())
            if (bytes <= 0L) return getString(R.string.settings_http_log_empty)
            val kb = bytes / 1024.0
            val size = if (kb < 1024) String.format(Locale.US, "%.1f KB", kb)
            else String.format(Locale.US, "%.2f MB", kb / 1024)
            return "$size · $files 个文件 · $lineCount 条"
        }

        findPreference<Preference>("httpLogStats")?.apply {
            summary = httpLogSummary()
            setOnPreferenceClickListener {
                summary = httpLogSummary()
                true
            }
        }

        findPreference<Preference>("httpLogExport")?.apply {
            setOnPreferenceClickListener {
                val (bytes, _, _) = HttpLog.stats(requireContext())
                if (bytes <= 0L) {
                    Toast.makeText(requireContext(), R.string.settings_http_log_empty, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                // 导出前必须说清会包含什么 ✓
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.settings_http_log_export_title)
                    setMessage(R.string.settings_http_log_export_message)
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        val (name, content) = HttpLog.exportAll(requireContext())
                        pendingExport = name to content
                        startActivityForResult(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TITLE, name)
                            },
                            REQ_EXPORT_HTTP_LOG
                        )
                    }
                    show()
                }
                true
            }
        }

        findPreference<Preference>("httpLogClear")?.apply {
            setOnPreferenceClickListener {
                val (bytes, files, lineCount) = HttpLog.stats(requireContext())
                if (bytes <= 0L) {
                    Toast.makeText(requireContext(), R.string.settings_http_log_empty, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.settings_http_log_clear)
                    setMessage("将删除 $files 个文件、$lineCount 条记录。此操作不可撤销。")
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        val n = HttpLog.clear(requireContext())
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.settings_http_log_cleared, n),
                            Toast.LENGTH_SHORT
                        ).show()
                        findPreference<Preference>("httpLogStats")?.summary = httpLogSummary()
                    }
                    show()
                }
                true
            }
        }

        findPreference<Preference>("clearCache")?.apply {
            summary = CacheDataManager.getTotalCacheSize(requireContext())
            setOnPreferenceClickListener {
                val currentSize = CacheDataManager.getTotalCacheSize(requireContext())
                summary = currentSize
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle("确定清除缓存吗？")
                    setMessage("当前缓存$currentSize")
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        CacheDataManager.clearAllCache(requireContext())
                        summary = "刚刚清理"
                    }
                    show()
                }
                true
            }
        }

        findPreference<Preference>("imageQuality")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle("图片画质")
                val items = arrayOf("网络自适应", "原图", "普清")
                val index = when (PrefManager.imageQuality) {
                    "auto" -> 0
                    "origin" -> 1
                    else -> 2
                }
                setSingleChoiceItems(
                    items,
                    index
                ) { dialog: DialogInterface, position: Int ->
                    when (position) {
                        0 -> PrefManager.imageQuality = "auto"

                        1 -> PrefManager.imageQuality = "origin"

                        2 -> PrefManager.imageQuality = "thumbnail"
                    }
                    dialog.dismiss()
                }
                show()
            }
            true
        }

        findPreference<MaterialSwitchPreference>("isColorFilter")?.setOnPreferenceChangeListener { _, _ ->
            if (ResourceUtils.isNightMode(requireContext().resources.configuration))
                activity?.recreate()
            true
        }

        // 「校验 SSL 证书」与「网络传输调试模式」互斥：
        // 打开严格校验时强制关掉调试模式，并刷新调试开关的可用状态
        findPreference<MaterialSwitchPreference>("verifySsl")?.setOnPreferenceChangeListener { _, value ->
            if (value == true && PrefManager.isSslDebug) {
                PrefManager.isSslDebug = false
            }
            syncSslDebugState()
            true
        }

        // 「网络传输调试模式」：开启要二次确认 + 倒计时，关闭直接生效（两者都需重启应用）
        syncSslDebugState()
        findPreference<MaterialSwitchPreference>("sslDebug")?.setOnPreferenceChangeListener { _, value ->
            when {
                // 严格校验开着时不可开启（开关本应是禁用态，这里兜底拦截）
                value == true && PrefManager.isVerifySsl -> false

                // 开启：先弹二次确认，确认后再落库并刷新开关
                value == true -> {
                    showSslDebugConfirmDialog()
                    false
                }

                // 关闭：直接落库 + 提示
                else -> {
                    PrefManager.isSslDebug = false
                    syncSslDebugState()
                    Toast.makeText(
                        requireContext(),
                        R.string.ssl_debug_disabled_toast,
                        Toast.LENGTH_LONG
                    ).show()
                    false
                }
            }
        }

    }

    /** 按「校验 SSL 证书」的当前值刷新调试开关：互斥时禁用并换成说明文案 */
    private fun syncSslDebugState() {
        val debug = findPreference<MaterialSwitchPreference>("sslDebug") ?: return
        val strict = PrefManager.isVerifySsl
        if (strict && PrefManager.isSslDebug) {
            PrefManager.isSslDebug = false
        }
        debug.isChecked = PrefManager.isSslDebug
        debug.isEnabled = !strict
        debug.summary = getString(
            if (strict) R.string.settings_ssl_debug_locked_summary
            else R.string.settings_ssl_debug_summary
        )
    }

    /**
     * 开启「网络传输调试模式」前的二次确认：正向按钮先禁用并倒计时，
     * 防止误触一键放开全部 SSL 校验。
     */
    private fun showSslDebugConfirmDialog() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ssl_debug_confirm_title)
            .setMessage(R.string.ssl_debug_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ssl_debug_confirm_ok, null)
            .create()

        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val handler = Handler(Looper.getMainLooper())
            var remain = 5
            confirm.isEnabled = false
            confirm.text = getString(R.string.ssl_debug_confirm_ok_wait, remain)

            val tick = object : Runnable {
                override fun run() {
                    remain--
                    if (remain > 0) {
                        confirm.text = getString(R.string.ssl_debug_confirm_ok_wait, remain)
                        handler.postDelayed(this, 1000L)
                    } else {
                        confirm.isEnabled = true
                        confirm.text = getString(R.string.ssl_debug_confirm_ok)
                    }
                }
            }
            handler.postDelayed(tick, 1000L)

            confirm.setOnClickListener {
                handler.removeCallbacks(tick)
                PrefManager.isSslDebug = true
                syncSslDebugState()
                dialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    R.string.ssl_debug_enabled_toast,
                    Toast.LENGTH_LONG
                ).show()
                showSslDebugRestartDialog()
            }
        }
        dialog.show()
    }

    /** 开启后提示需要重启（网络栈在 Application 启动时构建，只有整个进程重启才生效） */
    private fun showSslDebugRestartDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ssl_debug_restart_title)
            .setMessage(R.string.ssl_debug_restart_message)
            .setNegativeButton(R.string.ssl_debug_restart_later, null)
            .setPositiveButton(R.string.ssl_debug_restart_now) { _, _ -> restartApp() }
            .show()
    }

    /**
     * 立即重启应用：用 AlarmManager 预约拉起一个新的启动 Intent，再杀掉当前进程
     * （直接杀进程系统不会自动拉起）。正式发布版本同样适用。
     */
    private fun restartApp() {
        val appContext = requireContext().applicationContext
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        if (intent == null) {
            // 拿不到启动 Intent（极罕见）：退化为重建当前 Activity，至少刷新界面
            activity?.recreate()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return@runCatching
            val triggerAt = System.currentTimeMillis() + 200L
            // Android 12+ 未获精确闹钟授权时 setExact 会抛异常，此时退化为普通 set
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExact(AlarmManager.RTC, triggerAt, pendingIntent)
            } else {
                am.set(AlarmManager.RTC, triggerAt, pendingIntent)
            }
        }
        exitProcess(0)
    }

    companion object {
        /** rootKey = 某个二级 PreferenceScreen 的 key，为空则是完整的一级设置页 */
        fun newInstance(rootKey: String?) = SettingsPreferenceFragment().apply {
            arguments = Bundle().apply {
                putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, rootKey)
            }
        }
    }

    /**
     * 用户选好保存位置 ⇒ 把日志写进去。
     *
     * 用 SAF（`ACTION_CREATE_DOCUMENT`）而不是直接写公共目录 ✓
     * ⇒ 不需要任何存储权限 ✓（Android 10+ 分区存储下这是唯一干净的做法）
     *
     * ⚠️ 写失败要明确告诉用户 ✗（不能静默 ✗ 否则用户以为导出成功了 ✗）
     */
    @Deprecated("PreferenceFragmentCompat 里没有 ActivityResult API 的注册点")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT_HTTP_LOG) return
        val pending = pendingExport
        pendingExport = null
        if (resultCode != AppCompatActivity.RESULT_OK) return  // 用户取消 ✓
        val uri = data?.data ?: return
        if (pending == null) return
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                out.write(pending.second.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(
                requireContext(),
                R.string.settings_http_log_exported,
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Throwable) {
            Toast.makeText(
                requireContext(),
                getString(
                    R.string.settings_http_log_export_failed,
                    e.message ?: e.javaClass.simpleName
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

}