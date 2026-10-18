package com.example.c001apk.ui.message

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.c001apk.R
import com.example.c001apk.adapter.HeaderAdapter
import com.example.c001apk.adapter.LoadingState
import com.example.c001apk.databinding.FragmentMineBinding
import com.example.c001apk.ui.account.AccountManagerActivity
import com.example.c001apk.ui.base.BaseFragment
import com.example.c001apk.ui.login.WebLoginActivity
import com.example.c001apk.ui.main.MainActivity
import com.example.c001apk.ui.settings.SettingsActivity
import com.example.c001apk.ui.user.UserActivity
import com.example.c001apk.util.CookieUtil.atcommentme
import com.example.c001apk.util.CookieUtil.atme
import com.example.c001apk.util.CookieUtil.contacts_follow
import com.example.c001apk.util.CookieUtil.feedlike
import com.example.c001apk.util.ImageUtil
import com.example.c001apk.util.IosDeviceUtils
import com.example.c001apk.util.VerifyBadge
import com.example.c001apk.util.IntentUtil
import com.example.c001apk.util.PrefManager
import com.example.c001apk.util.dp
import com.example.c001apk.view.LinearItemDecoration
import com.example.c001apk.view.MessStaggerItemDecoration
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.net.URLDecoder

/**
 * 「我的」页（2026-10-05 按桌面版重排）：头部用户卡 + 动态/关注/粉丝统计卡 + 功能宫格。
 *
 * 通知列表和消息入口已经搬去 MessageCenterActivity，这里只剩个人中心本身，
 * 页面结构因此简化成三段静态卡片，不再有分页和 Footer。
 */
@AndroidEntryPoint
class MineFragment : BaseFragment<FragmentMineBinding>() {

    private val viewModel by viewModels<MineViewModel>()
    private val messageFirstAdapter by lazy { MessageFirstAdapter() }
    private val mineMenuAdapter by lazy { MineMenuAdapter() }
    private lateinit var mLayoutManager: LinearLayoutManager
    private lateinit var sLayoutManager: StaggeredGridLayoutManager
    private val isPortrait by lazy {
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }
    private val isLogin by lazy { PrefManager.isLogin }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!viewModel.isInit) {
            initView()
            initLogin()
            initScroll()
            initRefresh()
            initObserve()
        }
    }

    private fun initView() {
        binding.recyclerView.apply {
            adapter = ConcatAdapter(
                HeaderAdapter(),
                messageFirstAdapter,
                mineMenuAdapter
            )
            layoutManager =
                if (isPortrait) {
                    mLayoutManager = LinearLayoutManager(requireContext())
                    mLayoutManager
                } else {
                    sLayoutManager =
                        StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                    sLayoutManager
                }
            if (isPortrait)
                addItemDecoration(LinearItemDecoration(10.dp))
            else
                addItemDecoration(MessStaggerItemDecoration(10.dp))
        }
    }

    private fun initLogin() {
        binding.isLogin = isLogin
        messageFirstAdapter.isLogin = isLogin
        // 工具栏（含设置图标）不管登没登录都要显示，只有「退出登录」跟着登录状态走
        initMenu()
        if (isLogin) {
            // 头部整块（头像 + 昵称 + 等级 + 进度）点击进入自己的主页
            binding.profileLayout.setOnClickListener {
                if (PrefManager.uid.isNotEmpty())
                    IntentUtil.startActivity<UserActivity>(requireContext()) {
                        putExtra("id", PrefManager.uid)
                    }
            }
            if (viewModel.initLogin) {
                viewModel.initLogin = false
                showProfile()
                getData()
            }
        } else {
            binding.clickToLogin.setOnClickListener {
                IntentUtil.startActivity<WebLoginActivity>(requireContext()) {}
            }
        }
    }

    private fun initObserve() {
        viewModel.countList.observe(viewLifecycleOwner) {
            messageFirstAdapter.setFFFList(it)
        }

        viewModel.loadingState.observe(viewLifecycleOwner) {
            when (it) {
                is LoadingState.LoadingDone -> {
                    binding.swipeRefresh.isRefreshing = false
                    showProfile()
                }

                is LoadingState.LoadingFailed -> {
                    binding.swipeRefresh.isRefreshing = false
                }

                else -> {}
            }
        }
    }

    private fun initScroll() {
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0) {
                    (activity as? MainActivity)?.hideNavigationView()
                } else if (dy < 0) {
                    (activity as? MainActivity)?.showNavigationView()
                }
            }
        })
    }

    private fun initRefresh() {
        binding.swipeRefresh.apply {
            setColorSchemeColors(
                MaterialColors.getColor(
                    requireContext(),
                    androidx.appcompat.R.attr.colorPrimary,
                    0
                )
            )
            setOnRefreshListener {
                if (isLogin) {
                    binding.swipeRefresh.isRefreshing = true
                    getData()
                } else
                    binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun getData() {
        viewModel.fetchProfile()
    }

    private fun doLogout() {
        MaterialAlertDialogBuilder(requireContext()).apply {
            setTitle(R.string.logoutTitle)
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.countList.value = emptyList()
                atme = null
                atcommentme = null
                feedlike = null
                contacts_follow = null
                PrefManager.isLogin = false
                PrefManager.uid = ""
                PrefManager.username = ""
                PrefManager.token = ""
                PrefManager.userAvatar = ""
                // 退出后回到随机 UUID；下次登录会重新换取固定 CDID
                IosDeviceUtils.onLogout()
                (requireActivity() as? MainActivity)?.recreate()
            }
            show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 编辑资料（头像）返回后同步头部
        if (isLogin) showProfile()
        if (viewModel.isInit) {
            viewModel.isInit = false
            initView()
            initLogin()
            initScroll()
            initRefresh()
            initObserve()
        }
    }

    private fun initMenu() {
        binding.toolBar.apply {
            // onResume 里也会走到这里，先清空避免菜单项被重复 inflate
            menu.clear()
            inflateMenu(R.menu.message_menu)
            // 未登录也能进设置，只是没有「退出登录」可按
            menu.findItem(R.id.logout)?.isVisible = isLogin
            setOnMenuItemClickListener {
                when (it.itemId) {
                    // 账号管理（图标已改成 ic_mine_selector）：
                    // 进账号管理页，退出登录按钮挪到那一页的最底下 ✗
                    R.id.logout -> {
                        IntentUtil.startActivity<AccountManagerActivity>(requireContext()) {}
                        true
                    }

                    R.id.settings -> {
                        IntentUtil.startActivity<SettingsActivity>(requireContext()) {}
                        true
                    }

                    else -> false
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showProfile() {
        binding.name.text = URLDecoder.decode(PrefManager.username, "UTF-8")
        binding.level.text = "Lv.${PrefManager.level}"
        binding.exp.text = "${PrefManager.experience}/${PrefManager.nextLevelExperience}"
        binding.progress.max = PrefManager.nextLevelExperience.toIntOrNull() ?: -1
        binding.progress.progress = PrefManager.experience.toIntOrNull() ?: -1
        if (PrefManager.userAvatar.isNotEmpty())
            ImageUtil.showIMG(binding.avatar, PrefManager.userAvatar)
        // 自己的认证信息本地没存 status，只有维护者那条特例能认出来（uid 判定）
        VerifyBadge.applyTo(binding.verifyBadge, PrefManager.uid, null, null)
    }

}
