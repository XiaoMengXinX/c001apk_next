package com.example.c001apk.ui.others

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslError
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Environment
import android.graphics.Bitmap
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.c001apk.R
import com.example.c001apk.databinding.ActivityWebViewBinding
import com.example.c001apk.ui.base.BaseActivity
import com.example.c001apk.util.ClipboardUtil.copyText
import com.example.c001apk.util.IosDeviceUtils
import com.example.c001apk.util.LinkGuard
import com.example.c001apk.util.PrefManager
import com.example.c001apk.util.SslErrorPrompter
import com.example.c001apk.util.http2https
import com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.net.URISyntaxException
import java.net.URLDecoder
import kotlin.system.exitProcess


class WebViewActivity : BaseActivity<ActivityWebViewBinding>() {

    private val link: String? by lazy { intent.getStringExtra("url") }

    /**
     * 可选的「编辑」目标地址。传入后工具栏右上角出现一个编辑按钮，
     * 点击即在当前 WebView 里加载该地址（例：我的装备页 → 编辑装备页）。
     */
    private val editUrl: String? by lazy { intent.getStringExtra("editUrl") }
    private var webView: WebView? = null

    /** 用户点过「知道了」的 host：同一次浏览里不再重复提示，换个域名再提示 */
    private var riskDismissedHost: String? = null

    companion object {
        // 每个进程只允许设置一次 WebView 数据目录后缀
        private var dataDirSuffixSet = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (SDK_INT >= 28 && !dataDirSuffixSet) {
            dataDirSuffixSet = true
            runCatching { WebView.setDataDirectorySuffix("webview") }
        }

        runCatching {
            webView = WebView(this).apply {
                layoutParams = CoordinatorLayout.LayoutParams(
                    CoordinatorLayout.LayoutParams.MATCH_PARENT,
                    CoordinatorLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    behavior = ScrollingViewBehavior()
                }
            }
            binding.root.addView(webView)
            // WebView 是代码 addView 的，z 序在 XML 子 View 之后，会把风险提示条盖住
            binding.riskBanner.bringToFront()
        }.onFailure {
            MaterialAlertDialogBuilder(this)
                .setTitle("Failed to init WebView")
                .setMessage(it.message)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton("Log") { _, _ ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Log")
                        .setMessage(it.stackTraceToString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                .show()
        }

        bindRiskBanner()

        link?.let {
            loadUrlInWebView(it)
        }
    }

    /**
     * 风险提示条：贴底，得自己让开系统导航栏（根布局是 edgeToEdge 的）。
     * 「知道了」只关掉当前 host 的提示，页面内跳到别的域名会再提示一次。
     */
    private fun bindRiskBanner() {
        // 基准 padding 先存下来：insets 会分发多次，直接累加会越垫越高
        val basePaddingBottom = binding.riskBanner.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.riskBanner) { view, insets ->
            view.updatePadding(
                bottom = basePaddingBottom +
                        insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            )
            insets
        }
        binding.riskDismiss.setOnClickListener {
            riskDismissedHost = LinkGuard.hostOf(webView?.url)
            binding.riskBanner.isVisible = false
        }
    }

    /** 当前页不在可信链接白名单里才提示；提示内容带上具体域名 */
    private fun updateRiskBanner(url: String?) {
        val text = url?.trim().orEmpty()
        val risky = (text.startsWith("http://") || text.startsWith("https://")) &&
                !LinkGuard.isTrusted(text) &&
                LinkGuard.hostOf(text) != riskDismissedHost
        binding.riskBanner.isVisible = risky
        if (risky) {
            binding.riskText.text = getString(R.string.link_risk_tip, LinkGuard.hostOf(text))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadUrlInWebView(url: String) {
        webView?.let {
            it.settings.apply {
                javaScriptEnabled = true
                blockNetworkImage = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                domStorageEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                defaultTextEncodingName = "UTF-8"
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                javaScriptCanOpenWindowsAutomatically = true
                loadsImagesAutomatically = true
                allowFileAccess = false
                // 酷安域名用 iOS UA（与 API/图片一致）；第三方链接沿用系统 WebView 默认 UA
                IosDeviceUtils.webUserAgentFor(url)?.let { userAgentString = it }
                if (SDK_INT >= 32) {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
                    }
                } else {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                        val nightModeFlags =
                            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                            WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
                        }
                    }
                }
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
                // 跨子域（m/www/api 等）注入登录态 cookie；具体逻辑见 applyCoolapkCookies
                applyCoolapkCookies(url)
            }
            it.apply {
                setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                    val fileName = URLDecoder.decode(
                        URLUtil.guessFileName(url, contentDisposition, mimetype),
                        "UTF-8"
                    )
                    MaterialAlertDialogBuilder(this@WebViewActivity).apply {
                        setTitle("确定下载文件吗？")
                        setMessage(fileName)
                        setNeutralButton("外部打开") { _, _ ->
                            try {
                                this@WebViewActivity.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(this@WebViewActivity, "打开失败", Toast.LENGTH_SHORT)
                                    .show()
                                copyText(this@WebViewActivity, url)
                                e.printStackTrace()
                            }
                        }
                        setNegativeButton(android.R.string.cancel, null)
                        setPositiveButton(android.R.string.ok) { _, _ ->
                            try {
                                val request = DownloadManager.Request(Uri.parse(url))
                                    .setMimeType(mimetype)
                                    .addRequestHeader(
                                        "cookie",
                                        CookieManager.getInstance().getCookie(url)
                                    )
                                    .addRequestHeader("User-Agent", userAgent)
                                    .setTitle(fileName)
                                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    .setDestinationInExternalPublicDir(
                                        Environment.DIRECTORY_DOWNLOADS,
                                        fileName
                                    )
                                val downloadManager =
                                    getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                downloadManager.enqueue(request)
                            } catch (e: Exception) {
                                Toast.makeText(this@WebViewActivity, "下载失败", Toast.LENGTH_SHORT)
                                    .show()
                                copyText(this@WebViewActivity, url)
                                e.printStackTrace()
                            }
                        }
                        show()
                    }
                }
                webViewClient = object : WebViewClient() {
                    /**
                     * WebView 内部每次开始加载新页面时触发。
                     * 当目标 host 是 coolapk 任意子域时，重新注入登录态 cookie，
                     * 覆盖从 m.coolapk.com 跳转到 www / api 等子域时丢失登录态的情况。
                     */
                    override fun onPageStarted(
                        view: WebView?, url: String?, favicon: Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        if (url != null) {
                            applyCoolapkCookies(url)
                            // 网页内部继续跳转也会走到这里，所以提示条跟着当前地址走
                            updateRiskBanner(url)
                        }
                    }

                    /** SSL 证书校验不过（如抓包/中间人）：阻止加载并弹风险警告 */
                    override fun onReceivedSslError(
                        view: WebView?, handler: SslErrorHandler?, error: SslError?
                    ) {
                        // 网络传输调试模式：直接放行（不校验证书链/域名），方便抓包调试
                        if (PrefManager.isSslDebug) {
                            handler?.proceed()
                            return
                        }
                        SslErrorPrompter.onSslFailure(error?.let {
                            java.security.cert.CertificateException(it.toString())
                        })
                        handler?.cancel()
                    }

                    override fun shouldOverrideUrlLoading(
                        webView: WebView?, request: WebResourceRequest?
                    ): Boolean {
                        request?.let {
                            try {
                                //处理intent协议
                                if (request.url.toString().startsWith("intent://")) {
                                    val intent: Intent
                                    try {
                                        intent = Intent.parseUri(
                                            request.url.toString(), Intent.URI_INTENT_SCHEME
                                        )
                                        intent.addCategory("android.intent.category.BROWSABLE")
                                        intent.component = null
                                        intent.selector = null
                                        val resolves =
                                            context.packageManager.queryIntentActivities(intent, 0)
                                        if (resolves.size > 0) {
                                            startActivityIfNeeded(intent, -1)
                                        }
                                        return true
                                    } catch (e: URISyntaxException) {
                                        e.printStackTrace()
                                    }
                                }
                                // 处理自定义scheme协议
                                if (!request.url.toString().startsWith("http")) {
                                    webView?.let {
                                        Snackbar.make(
                                            it,
                                            "当前网页将要打开外部链接，是否打开",
                                            Snackbar.LENGTH_SHORT
                                        ).setAction("打开") {
                                            try {
                                                val intent = Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse(request.url.toString())
                                                )
                                                intent.flags =
                                                    (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(
                                                    this@WebViewActivity,
                                                    "打开失败",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                e.printStackTrace()
                                            }
                                        }.show()
                                    }
                                    return true
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        return super.shouldOverrideUrlLoading(webView, request)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        super.onCloseWindow(window)
                        finish()
                    }
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (newProgress == 100) {
                            binding.progressBar.isVisible = false
                        } else {
                            binding.progressBar.isVisible = true
                            binding.progressBar.progress = newProgress
                        }
                    }

                    override fun onReceivedTitle(view: WebView, title: String) {
                        super.onReceivedTitle(view, title)
                        binding.toolBar.title = title
                    }
                }
                // 注意：这里**不能**带 X-Requested-With: com.coolapk.market。
                // 酷安 H5（m.coolapk.com）用它判定 ajax 请求，带上后整页会返回 JSON 片段
                // （实测 myDevice / editProductOwner / report 等页面都是如此），WebView 只能显示原始 JSON。
                loadUrl(url)
            }
        }
    }

    /**
     * 判断 url 是否命中 coolapk 主域（含所有子域）。
     * 用于决定是否需要注入登录态 cookie。
     */
    private fun isCoolapkHost(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "coolapk.com" || host.endsWith(".coolapk.com")
    }

    /**
     * 把登录态 cookie 写入 WebView 的 CookieManager。
     * - 当目标 host 是 coolapk 子域时生效；
     * - 同时写到当前 host 与 .coolapk.com 域，使 cookie 在所有子域之间共享，
     *   解决从 m.coolapk.com 跳到 www / api 等子域后显示未登录的问题。
     */
    private fun applyCoolapkCookies(url: String) {
        if (!isCoolapkHost(url)) return
        val cookieManager = CookieManager.getInstance()
        val host = Uri.parse(url).host?.lowercase() ?: return
        val cookies = listOf(
            "DID=${PrefManager.SZLMID}",
            "forward=https://www.coolapk.com",
            "displayVersion=v14",
            "uid=${PrefManager.uid}",
            "username=${PrefManager.username}",
            "token=${PrefManager.token}",
        )
        cookies.forEach { value ->
            // 写到当前 host，确保该子域请求能立刻带上 cookie
            cookieManager.setCookie(url, "$value; path=/")
            // 再以 .coolapk.com 域写一份，覆盖之后跳转到其它子域的场景
            if (host != "m.coolapk.com") {
                cookieManager.setCookie(
                    "https://m.coolapk.com/",
                    "$value; domain=.coolapk.com; path=/"
                )
            }
        }
    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.webview_menu, menu)
        // 只有显式传了 editUrl 的页面（如「我的装备」）才显示右上角编辑按钮
        menu?.findItem(R.id.editDevice)?.isVisible = editUrl != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()

            R.id.editDevice -> editUrl?.let { url ->
                applyCoolapkCookies(url)
                webView?.loadUrl(url)
            }

            R.id.refresh -> webView?.reload()

            R.id.copyLink -> {
                webView?.url?.let {
                    copyText(this, it.http2https)
                }
            }

            R.id.openInBrowser -> {
                webView?.url?.let {
                    val uri = Uri.parse(it.http2https)
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, "打开失败", Toast.LENGTH_SHORT).show()
                        Log.w("error", "Activity was not found for intent, $intent")
                    }
                }
            }

            R.id.clearCache -> {
                webView?.apply {
                    clearHistory()
                    clearCache(true)
                    clearFormData()
                    Toast.makeText(this@WebViewActivity, "清除缓存成功", Toast.LENGTH_SHORT).show()
                }
            }

        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView?.canGoBack() == true) {
            webView?.goBack() //返回上个页面
            return true
        }
        return super.onKeyDown(keyCode, event) //退出H5界面
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onDestroy() {
        try {
            webView?.apply {
                loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
                loadUrl("about:blank")
                parent?.let {
                    (it as ViewGroup).removeView(webView)
                }
                stopLoading()
                settings.javaScriptEnabled = false
                clearHistory()
                clearCache(true)
                removeAllViewsInLayout()
                removeAllViews()
                setOnTouchListener(null)
                setOnKeyListener(null)
                onFocusChangeListener = null
                webChromeClient = null
                onPause()
                destroy()
            }
            webView = null
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        super.onDestroy()
        exitProcess(0)
    }

}