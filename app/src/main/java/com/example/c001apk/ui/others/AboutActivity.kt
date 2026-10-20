package com.example.c001apk.ui.others

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.example.c001apk.BuildConfig
import com.example.c001apk.R
import com.example.c001apk.databinding.ActivityAboutBinding
import com.example.c001apk.databinding.ItemAboutEntryBinding
import com.example.c001apk.databinding.ItemAboutLinkBinding
import com.example.c001apk.ui.base.BaseActivity
import com.example.c001apk.util.GitHubProfile
import com.example.c001apk.util.NetWorkUtil
import com.example.c001apk.util.PrefManager
import com.example.c001apk.util.UpdateChecker
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch

/**
 * 关于本应用（M3E 风格，整页骨架在 activity_about.xml）。
 *
 * 这页原来是第三方库 about-page 的 AbsAboutActivity，形态配色都跟不上现在的 M3E 基线，
 * Beta 通道下线时索性整页重写（2026-10-07）。这里只负责按数据往四个容器里填条目：
 * 前辈、维护者、反馈、开源许可证。
 */
class AboutActivity : BaseActivity<ActivityAboutBinding>() {

    /**
     * 维护者。头像与 bio 都从 GitHub 拉，内置图只作为加载前的占位
     * （离线 / 限流时就停在占位图上，不影响其它内容）。
     *
     * 这个项目从 kongwufang 的仓库 fork 出来，现在由下面这些人共同维护。
     */
    private val maintainers = listOf(
        Maintainer("kongwufang"),
        Maintainer("XiaoMengXinX"),
    )

    private data class Maintainer(val user: String)

    /**
     * 装机量统计页。key 与 `_rev/stats_key.txt`、服务端 conf.php 的 STATS_KEY 同一份
     * —— 那个 key 本来就会随 conf.php 下发（自更新接口要用），放在这里不会多泄露什么。
     */
    private val statsUrl =
        "https://service.houlangs.cn/c001apk/stats.php?key=87cf6dccca45ab5ce25530c567026109ea063f29"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.toolBar.setNavigationOnClickListener { finish() }
        bindAppInfo()
        bindUpdate()
        bindDevelopers()
        bindFeedback()
        bindLicenses()
    }

    /** 关于页顶部的应用信息：图标 / 名称 / 版本号 / 编译时间 */
    private fun bindAppInfo() = with(binding) {
        appIcon.setImageResource(R.mipmap.ic_launcher)
        appName.text = applicationInfo.loadLabel(packageManager)
        appVersion.text = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
        appBuildTime.text = getString(R.string.about_build_time, BuildConfig.BUILD_TIME)
    }

    /** 更新：只剩正式版一条通道（Beta 开关 / 按钮连同逻辑一起删了） */
    private fun bindUpdate() = with(binding) {
        switchUpdate.isChecked = PrefManager.isCheckUpdateStable
        switchUpdate.setOnCheckedChangeListener { _, checked ->
            PrefManager.isCheckUpdateStable = checked
        }
        // 点整行等同于点开关，跟系统设置页的习惯一致
        rowUpdateSwitch.setOnClickListener { switchUpdate.toggle() }
        rowCheckNow.setOnClickListener { checkUpdateNow() }
    }

    private fun checkUpdateNow() {
        Toast.makeText(this, R.string.about_checking_update, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val info = UpdateChecker.fetchUpdate()
            when {
                info == null ->
                    Toast.makeText(
                        this@AboutActivity, R.string.about_check_update_failed, Toast.LENGTH_SHORT
                    ).show()

                info.isNewer ->
                    // 「不再提示」会改 PrefManager，回调里把开关同步回来
                    UpdateChecker.showUpdateDialog(this@AboutActivity, info) {
                        binding.switchUpdate.isChecked = PrefManager.isCheckUpdateStable
                    }

                else ->
                    Toast.makeText(
                        this@AboutActivity, R.string.about_is_latest, Toast.LENGTH_SHORT
                    ).show()
            }
        }
    }

    /**
     * 作者栏分两块：**前辈**（这个项目是 fork 来的，原先的作者 / 协作者都算前辈）
     * 和**维护者**（现在共同维护的几个人）。
     */
    private fun bindDevelopers() {
        addEntry(
            binding.groupPredecessor, R.drawable.cont_author, "bggRGjQaUbCoE",
            getString(R.string.about_role_developer_designer), "https://github.com/bggRGjQaUbCoE"
        )
        addDivider(binding.groupPredecessor)
        addEntry(
            binding.groupPredecessor, R.drawable.cont_klxiaoniu, "klxiaoniu",
            getString(R.string.about_role_developer_collaborator), "https://github.com/klxiaoniu"
        )

        // 每位维护者一条；头像与 bio 都从 GitHub 拉，先挂通用占位图
        maintainers.forEachIndexed { index, maintainer ->
            if (index > 0) addDivider(binding.groupMaintainer)
            val me = addEntry(
                binding.groupMaintainer, R.drawable.ic_account, maintainer.user,
                getString(R.string.about_role_maintainer),
                "https://github.com/${maintainer.user}"
            )
            lifecycleScope.launch {
                val profile = GitHubProfile.fetch(maintainer.user) ?: return@launch
                profile.avatarUrl?.let { url -> loadAvatar(me, url) }
                if (profile.bio.isNotEmpty()) {
                    me.bio.text = profile.bio
                    me.bio.visibility = View.VISIBLE
                }
            }
        }
    }

    /** 反馈：仓库地址（固定）+ 装机量统计 */
    private fun bindFeedback() {
        val repoUrl = getString(R.string.about_source_code_url)
        addLink(
            binding.groupFeedback,
            getString(R.string.about_view_source_code, "GitHub"),
            repoUrl,
        ) { UpdateChecker.openExternal(this, repoUrl) }

        if (statsUrl.isNotEmpty()) {
            addLink(
                binding.groupFeedback,
                getString(R.string.about_install_stats),
                getString(R.string.about_install_stats_desc),
            ) { NetWorkUtil.openLink(this, statsUrl, getString(R.string.about_install_stats)) }
        }
    }

    /** 开源许可证：条目多，全列在一张卡片里，副标题是「作者 · 许可证」，点了跳仓库 */
    private fun bindLicenses() {
        LICENSES.forEach { license ->
            addLink(
                binding.groupLicense,
                license.name,
                "${license.author} · ${license.license}",
            ) { UpdateChecker.openExternal(this, license.url) }
        }
    }

    /**
     * 加载 GitHub 头像。
     *
     * 不复用 [ImageUtil.showIMG]：那个是给酷安图片用的（会带酷安 UA、
     * 深色模式下还会压暗图片），头像既不该伪装成酷安客户端，也不该被压暗。
     * 这里只做普通的圆角裁切加载，失败时保留 [addEntry] 设好的内置占位图。
     */
    private fun loadAvatar(item: ItemAboutEntryBinding, url: String) {
        Glide.with(this)
            .load(url)
            .transform(CenterCrop())
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transition(withCrossFade())
            .into(item.avatar)
    }

    /** 往容器里塞一条「头像 + 标题 + 副标题 + 右箭头」，点了跳外部浏览器 */
    private fun addEntry(
        container: LinearLayout,
        avatar: Int,
        title: String,
        subtitle: String,
        url: String,
    ): ItemAboutEntryBinding {
        val item = ItemAboutEntryBinding.inflate(layoutInflater, container, false)
        item.avatar.setImageResource(avatar)
        item.title.text = title
        item.subtitle.text = subtitle
        item.root.setOnClickListener { UpdateChecker.openExternal(this, url) }
        container.addView(item.root)
        return item
    }

    /** 往容器里塞一条「标题 + 副标题 + 右箭头」 */
    private fun addLink(
        container: LinearLayout,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ) {
        val item = ItemAboutLinkBinding.inflate(layoutInflater, container, false)
        item.title.text = title
        item.subtitle.text = subtitle
        item.root.setOnClickListener { onClick() }
        container.addView(item.root)
    }

    /** 条目之间的 1px 分隔线，左端缩进到头像右侧（与标题文字对齐） */
    private fun addDivider(container: LinearLayout) {
        val divider = View(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        lp.marginStart = (72 * resources.displayMetrics.density).toInt()
        divider.layoutParams = lp
        divider.setBackgroundColor(
            MaterialColors.getColor(divider, com.google.android.material.R.attr.colorSurfaceVariant)
        )
        container.addView(divider)
    }

    private data class License(
        val name: String,
        val author: String,
        val license: String,
        val url: String,
    )

    private companion object {
        const val APACHE_2 = "Apache-2.0"
        const val MIT = "MIT"
        const val GPL_V3 = "GPL-3.0"

        val LICENSES = listOf(
            License("kotlin", "JetBrains", APACHE_2, "https://github.com/JetBrains/kotlin"),
            License("AndroidX", "Google", APACHE_2, "https://github.com/androidx/androidx"),
            License(
                "material-components-android", "Google", APACHE_2,
                "https://github.com/material-components/material-components-android"
            ),
            License("RikkaX", "RikkaApps", MIT, "https://github.com/RikkaApps/RikkaX"),
            License("LSPosed", "LSPosed", GPL_V3, "https://github.com/LSPosed/LSPosed"),
            License("LibChecker", "LibChecker", APACHE_2, "https://github.com/LibChecker/LibChecker"),
            License(
                "Hide-My-Applist", "Dr-TSNG", GPL_V3,
                "https://github.com/Dr-TSNG/Hide-My-Applist"
            ),
            License("okhttp", "square", APACHE_2, "https://github.com/square/okhttp"),
            License("retrofit", "square", APACHE_2, "https://github.com/square/retrofit"),
            License("glide", "bumptech", APACHE_2, "https://github.com/bumptech/glide"),
            License("jBCrypt", "jeremyh", APACHE_2, "https://github.com/jeremyh/jBCrypt"),
            License("flexbox-layout", "google", APACHE_2, "https://github.com/google/flexbox-layout"),
            License(
                "glide-transformations", "wasabeef", APACHE_2,
                "https://github.com/wasabeef/glide-transformations"
            ),
            License("jsoup", "jhy", MIT, "https://github.com/jhy/jsoup"),
            License(
                "NineGridImageView", "plain-dev", MIT,
                "https://github.com/plain-dev/NineGridImageView"
            ),
            License("mojito", "mikaelzero", APACHE_2, "https://github.com/mikaelzero/mojito"),
            License(
                "CircleIndicator", "ongakuer", APACHE_2,
                "https://github.com/ongakuer/CircleIndicator"
            ),
            License("libraries", "zhaobozhen", MIT, "https://github.com/zhaobozhen/libraries"),
            License("dagger", "google", APACHE_2, "https://github.com/google/dagger"),
            License(
                "SmoothInputLayout", "AlexMofer", APACHE_2,
                "https://github.com/AlexMofer/SmoothInputLayout"
            ),
        )
    }
}
