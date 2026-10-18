package com.example.c001apk.ui.settings.params

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.c001apk.R
import com.example.c001apk.util.CdidClient
import com.example.c001apk.util.IosDeviceUtils
import com.example.c001apk.util.PrefManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「设置 → 参数」：展示/编辑酷安 API 使用的 iOS 设备身份。
 *
 * 设备 ID 是持久化随机 UUID，只读展示（需要换身份时点「重新生成」）；
 * 机型字段可编辑，保存后立即影响 `X-App-Device` 与 UA。
 */
class ParamsPreferenceFragment : PreferenceFragmentCompat() {

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView =
            super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        recyclerView.apply {
            isVerticalScrollBarEnabled = false
        }
        return recyclerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(resources.getDrawable(R.drawable.divider, requireContext().theme))
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.params, null)
        refreshSummaries()

        // 设备 ID：只读，点击查看完整值
        // （必须是服务端登记过的 ID，随机 UUID 只能读、点赞/回复会被风控）
        findPreference<Preference>("iosDeviceId")?.setOnPreferenceClickListener {
            showReadOnlyDialog("IOS_DEVICE_ID", PrefManager.iosDeviceId)
            true
        }

        bindProfileField("iosModel", "IOS_MODEL") { value ->
            IosDeviceUtils.updateProfile(model = value)
        }
        bindProfileField("iosHardwareId", "IOS_HARDWARE_ID") { value ->
            IosDeviceUtils.updateProfile(hardwareId = value)
        }
        bindProfileField("iosSalesModel", "IOS_SALES_MODEL") { value ->
            IosDeviceUtils.updateProfile(salesModel = value)
        }
        bindProfileField("iosSystemVersion", "IOS_SYSTEM_VERSION") { value ->
            IosDeviceUtils.updateProfile(systemVersion = value)
        }

        findPreference<Preference>("xAppDevice")?.setOnPreferenceClickListener {
            showReadOnlyDialog("X-App-Device", IosDeviceUtils.appDevice())
            true
        }

        // token 与时间戳绑定，每次点击都重新生成一个可对照抓包的值
        findPreference<Preference>("xAppToken")?.setOnPreferenceClickListener {
            val token = IosDeviceUtils.appToken()
            findPreference<Preference>("xAppToken")?.summary = token
            showReadOnlyDialog("X-App-Token", token)
            true
        }

        findPreference<Preference>("regenerate")?.setOnPreferenceClickListener {
            val device = IosDeviceUtils.regenerate()
            refreshSummaries()
            Snackbar.make(
                requireView(),
                "已重新生成机型档案（设备 ID 保持不变）",
                Snackbar.LENGTH_LONG
            ).show()
            showReadOnlyDialog("IOS_DEVICE_ID", device.id)
            true
        }

        findPreference<Preference>("regenerateDeviceId")?.setOnPreferenceClickListener {
            regenerateDeviceId()
            true
        }
    }

    /**
     * 重新生成设备 ID。
     *
     * 未登录时只换随机 UUID；登录状态下必须立刻向 CDID 服务申请新 ID，
     * 否则新的随机 UUID 会让点赞/回复被风控拦截。
     */
    private fun regenerateDeviceId() {
        IosDeviceUtils.resetDeviceId()
        refreshSummaries()
        if (!PrefManager.isLogin) {
            snack("已生成新的随机设备 ID（未登录；登录后会自动换取 CDID）")
            return
        }
        snack("正在向 CDID 服务申请新的设备 ID…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CdidClient.fetch(
                        IosDeviceUtils.current(),
                        IosDeviceUtils.idfv(),
                        IosDeviceUtils.installId(),
                    )
                }
            }
            result.onSuccess { cdid ->
                IosDeviceUtils.setCdid(cdid)
                refreshSummaries()
                snack("设备 ID 已更新")
            }.onFailure { error ->
                refreshSummaries()
                snack("CDID 申请失败，当前为随机 UUID：${error.message}")
            }
        }
    }

    private fun snack(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    override fun onResume() {
        super.onResume()
        // token 随时间变化，回到页面时刷新一下摘要
        refreshSummaries()
    }

    private fun bindProfileField(key: String, title: String, onSave: (String) -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener {
            val current = when (key) {
                "iosModel" -> PrefManager.iosModel
                "iosHardwareId" -> PrefManager.iosHardwareId
                "iosSalesModel" -> PrefManager.iosSalesModel
                else -> PrefManager.iosSystemVersion
            }
            showEditDialog(title, current) {
                onSave(it)
                refreshSummaries()
            }
            true
        }
    }

    private fun refreshSummaries() {
        findPreference<Preference>("iosDeviceId")?.summary = PrefManager.iosDeviceId +
            when {
                PrefManager.iosCdid.isNotBlank() -> "（CDID）"
                PrefManager.isLogin -> "（随机 UUID，待换取 CDID）"
                else -> "（随机 UUID）"
            }
        findPreference<Preference>("iosModel")?.summary = PrefManager.iosModel
        findPreference<Preference>("iosHardwareId")?.summary = PrefManager.iosHardwareId
        findPreference<Preference>("iosSalesModel")?.summary =
            PrefManager.iosSalesModel.ifEmpty { "（未设置）" }
        findPreference<Preference>("iosSystemVersion")?.summary = PrefManager.iosSystemVersion
        findPreference<Preference>("xAppDevice")?.summary = IosDeviceUtils.appDevice()
        findPreference<Preference>("xAppToken")?.summary = IosDeviceUtils.appToken()
    }

    private fun showReadOnlyDialog(title: String, content: String) {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_x_app_token, null, false)
        val editText: EditText = view.findViewById(R.id.editText)
        editText.setText(content)
        editText.isFocusable = false
        MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setTitle(title)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showEditDialog(title: String, current: String, onSave: (String) -> Unit) {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_x_app_token, null, false)
        val editText: EditText = view.findViewById(R.id.editText)
        editText.setText(current)
        MaterialAlertDialogBuilder(requireContext()).apply {
            setView(view)
            setTitle(title)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(editText.text.toString())
            }
        }.create().apply {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            editText.requestFocus()
        }.show()
    }
}
