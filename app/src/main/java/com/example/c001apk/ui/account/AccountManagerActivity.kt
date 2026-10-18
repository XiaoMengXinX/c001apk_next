package com.example.c001apk.ui.account

import android.content.Intent
import android.os.Bundle
import com.example.c001apk.R
import com.example.c001apk.databinding.ActivityAccountManagerBinding
import com.example.c001apk.ui.base.BaseActivity
import com.example.c001apk.ui.main.MainActivity
import com.example.c001apk.util.IosDeviceUtils
import com.example.c001apk.util.PrefManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 账号管理页。
 *
 * 入口：「我的」页工具栏右上角的图标（原来是「退出登录」，改成 [R.drawable.ic_mine_selector] 后跳这里）。
 *
 * ## 页面结构（用户指定，见 `activity_account_manager.xml`）
 * - 上方 16 宫格（4×4）：1 个「扫码登录」有功能，其余 15 个「敬请期待」
 * - 最底部：红色长条形「退出登录」
 *
 * ## 当前实现范围（**不假装能用**）
 * - **扫码登录**：阶段 ④ 还没做 ⇒ 现在点了弹「敬请期待」✗
 * - **其余 15 格**：布局里就是 `enabled="false"`（点不动）
 * - **退出登录**：**能真用** ✓ —— 逻辑与 `MineFragment.doLogout()` 一致：
 *   确认对话框 → 清 PrefManager → 回 MainActivity
 *
 * ⚠️ 退出时**一并清掉 refreshToken**（阶段 ① 新加的凭据），
 *    否则下次启动会拿着已失效的 refreshToken 去请求 ✗
 */
class AccountManagerActivity : BaseActivity<ActivityAccountManagerBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 返回箭头（MaterialToolbar 自带，BaseActivity 不管这个）
        binding.toolbar.setNavigationOnClickListener { finish() }

        // ① 扫码登录 —— 调摄像头扫桌面端二维码（阶段 ④）
        binding.cellScanLogin.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        // ② 底部红色退出登录
        binding.btnLogout.setOnClickListener { confirmLogout() }
    }

    /**
     * 退出登录。
     *
     * 与 `MineFragment.doLogout()` 行为一致（那边是「我的」页里原来的入口），
     * 差别只在于退出后回到 [MainActivity] 而不是 `requireActivity().recreate()`。
     */
    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logoutTitle)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PrefManager.isLogin = false
                PrefManager.uid = ""
                PrefManager.username = ""
                PrefManager.token = ""
                // ★ 阶段 ① 新加的第二凭据，退出时一并清掉
                PrefManager.refreshToken = ""
                PrefManager.userAvatar = ""
                // 退出后回到随机 UUID；下次登录会重新换取固定 CDID
                IosDeviceUtils.onLogout()

                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                finish()
            }
            .show()
    }
}
