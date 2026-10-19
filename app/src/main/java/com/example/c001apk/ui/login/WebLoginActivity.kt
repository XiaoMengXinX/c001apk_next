package com.example.c001apk.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.net.http.SslError
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.c001apk.R
import com.example.c001apk.databinding.ActivityWebViewBinding
import com.example.c001apk.ui.base.BaseActivity
import com.example.c001apk.ui.main.MainActivity
import com.example.c001apk.util.ActivityCollector
import com.example.c001apk.util.CdidClient
import com.example.c001apk.util.IosDeviceUtils
import com.example.c001apk.util.PrefManager
import com.example.c001apk.util.SslErrorPrompter
import com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 网页登录：用 WebView 打开酷安官方登录页，登录成功后把 cookie 中的
 * uid / username / token 写进 PrefManager。
 *
 * 与原来的表单登录落地方式完全一致（见 AddCookiesInterceptor：
 * 登录态请求靠 uid / username / token 这三个 cookie），因此不必自己复刻官方登录表单：
 * 短信验证码、图形验证码、二次验证、第三方登录等步骤都由官方页面负责，
 * 也就不会再出现「验证码发出去了却没有输入框」的问题。
 *
 * 注意：**不要**把本 Activity 放进 `:webview` 独立进程（如 WebViewActivity 那样），
 * 因为 PrefManager 与 ActivityCollector 都是进程内的，跨进程写 SharedPreferences 不可靠。
 */
class WebLoginActivity : BaseActivity<ActivityWebViewBinding>() {

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isLogged = false

    private var elapsed = 0

    /** 登录成功后每隔 1s 检查一次 cookie，最多等 10 分钟 */
    private val cookieChecker = object : Runnable {
        override fun run() {
            if (isLogged) return
            if (checkLoginCookie()) return
            if (++elapsed > TIMEOUT_SECONDS) return
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(binding.toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.loginWeb)

        initWebView()

        webView?.loadUrl(LOGIN_URL)
        handler.postDelayed(cookieChecker, CHECK_INTERVAL_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val view = WebView(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.MATCH_PARENT,
                CoordinatorLayout.LayoutParams.MATCH_PARENT
            ).apply {
                behavior = ScrollingViewBehavior()
            }
        }
        binding.root.addView(view)
        webView = view

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            blockNetworkImage = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            defaultTextEncodingName = "UTF-8"
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            // 故意不覆盖 userAgentString：保留系统 WebView 的移动 UA，
            // 让酷安网页按移动浏览器渲染，才能拿到完整的登录表单
            if (SDK_INT >= 32) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
                }
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                val nightModeFlags =
                    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    WebSettingsCompat.setForceDark(this, WebSettingsCompat.FORCE_DARK_ON)
                }
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
            // 清掉旧 cookie 再进登录页：避免“残留的旧登录态”被误判成本次登录成功
            removeAllCookies(null)
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.isVisible = newProgress < 100
                binding.progressBar.progress = newProgress
            }
        }

        view.webViewClient = object : WebViewClient() {
            /** SSL 证书校验不过（如抓包/中间人）：阻止加载并弹风险警告 */
            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: SslError?
            ) {
                // 网络传输调试模式：直接放行（不校验证书链/域名），方便抓包调试
                if (PrefManager.isSslDebug) {
                    handler?.proceed()
                    return
                }
                SslErrorPrompter.onSslFailure(
                    error?.let { java.security.cert.CertificateException(it.toString()) }
                )
                handler?.cancel()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkLoginCookie()
            }
        }
    }

    /**
     * 从 WebView 的 cookie 里取登录态。
     * @return true 表示已拿到 uid + token 并完成登录
     */
    private fun checkLoginCookie(): Boolean {
        val cookieManager = CookieManager.getInstance()
        var uid: String? = null
        var token: String? = null
        var username: String? = null

        for (url in COOKIE_URLS) {
            val raw = cookieManager.getCookie(url) ?: continue
            for (pair in raw.split(";")) {
                val index = pair.indexOf('=')
                if (index <= 0) continue
                val key = pair.substring(0, index).trim()
                val value = pair.substring(index + 1).trim()
                if (value.isEmpty()) continue
                when (key) {
                    "uid" -> if (uid == null) uid = value
                    "token" -> if (token == null) token = value
                    "username" -> if (username == null) username = value
                }
            }
        }

        if (uid.isNullOrEmpty() || token.isNullOrEmpty()) return false
        saveLogin(uid, token, username)
        return true
    }

    private fun saveLogin(uid: String, token: String, username: String?) {
        if (isLogged) return
        isLogged = true
        handler.removeCallbacks(cookieChecker)

        PrefManager.isLogin = true
        PrefManager.uid = uid
        PrefManager.token = token
        if (!username.isNullOrEmpty()) {
            // cookie 里的用户名是 URL 编码的；统一规范化成“编码一次”的形式，
            // 与 MainViewModel.checkLoginInfo() 的写法保持一致（避免二次编码）
            PrefManager.username = runCatching {
                URLEncoder.encode(URLDecoder.decode(username, "UTF-8"), "UTF-8")
            }.getOrDefault(username)
        }

        Toast.makeText(this, "登录成功，正在初始化设备…", Toast.LENGTH_SHORT).show()
        finishLogin()
    }

    /**
     * 登录后先换到 CDID，再回主界面。
     *
     * 随机 UUID 只能读，写接口会被风控拦截；而主界面重建时
     * MainViewModel.isInit 会随 ViewModel 保留，getCheckLoginInfo() 不会重跑，
     * 不在这里换的话整个进程会一直用登录前的 UUID。
     */
    private fun finishLogin() {
        if (PrefManager.iosCdid.isNotBlank()) {
            completeLogin()
            return
        }

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("正在初始化设备")
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    CdidClient.fetch(
                        IosDeviceUtils.current(),
                        IosDeviceUtils.idfv(),
                        IosDeviceUtils.installId(),
                    )
                }.onSuccess { IosDeviceUtils.setCdid(it) }
                    .onFailure { it.printStackTrace() }
            }
            if (!isFinishing && !isDestroyed) dialog.dismiss()
            completeLogin()
        }
    }

    private fun completeLogin() {
        setResult(Activity.RESULT_OK)
        // 登录态变化后重建主界面（与原来表单登录的收尾逻辑一致）
        ActivityCollector.recreateActivity(MainActivity::class.java.name)
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView?.canGoBack() == true) {
            webView?.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.apply {
            stopLoading()
            // 避免 WebView 泄漏（本 Activity 与 app 同进程）
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL = "https://account.coolapk.com/auth/login?type=mobile"
        private const val CHECK_INTERVAL_MS = 1000L
        private const val TIMEOUT_SECONDS = 600

        private val COOKIE_URLS = listOf(
            "https://www.coolapk.com",
            "https://coolapk.com",
            "https://account.coolapk.com",
            "https://m.coolapk.com"
        )
    }
}
