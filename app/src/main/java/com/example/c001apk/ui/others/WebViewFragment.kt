package com.example.c001apk.ui.others

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslError
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.c001apk.util.IosDeviceUtils
import com.example.c001apk.util.PrefManager
import com.example.c001apk.util.SslErrorPrompter
import java.net.URISyntaxException

/**
 * 内嵌 WebView 的 Fragment，用于渲染话题页 / 产品页里 url 为 H5 的 tab
 * （如「活动详情」tab 的 url = https://m.coolapk.com/activity/<name>）。
 * 与 WebViewActivity（:webview 进程）不同，本 Fragment 运行在主进程，
 * 复用默认 WebView 数据目录，不影响 :webview 进程。
 */
class WebViewFragment : Fragment() {

    private val url: String? by lazy { arguments?.getString(ARG_URL) }
    private var webView: WebView? = null

    companion object {
        private const val ARG_URL = "url"

        fun newInstance(url: String): WebViewFragment = WebViewFragment().apply {
            arguments = Bundle().apply { putString(ARG_URL, url) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val wv = WebView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        webView = wv
        setupWebView(wv)
        url?.let { loadUrlInWebView(wv, it) }
        return wv
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.apply {
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
            userAgentString = IosDeviceUtils.coolapkUserAgent()
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadUrlInWebView(webView: WebView, url: String) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            applyCoolapkCookies(url)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null) applyCoolapkCookies(url)
            }

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
                        // intent 协议（如打开 app）
                        if (request.url.toString().startsWith("intent://")) {
                            try {
                                val intent = Intent.parseUri(
                                    request.url.toString(), Intent.URI_INTENT_SCHEME
                                )
                                intent.addCategory("android.intent.category.BROWSABLE")
                                intent.component = null
                                intent.selector = null
                                if (context?.packageManager
                                        ?.queryIntentActivities(intent, 0)?.isNotEmpty() == true
                                ) {
                                    startActivity(intent)
                                }
                            } catch (e: URISyntaxException) {
                                e.printStackTrace()
                            }
                            return true
                        }
                        // 非 http 自定义协议交给系统
                        if (!request.url.toString().startsWith("http")) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url.toString())))
                            } catch (e: Exception) {
                                e.printStackTrace()
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
        // 注意：不能带 X-Requested-With: com.coolapk.market，否则 H5 返回 JSON 片段
        webView.loadUrl(url)
    }

    private fun isCoolapkHost(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "coolapk.com" || host.endsWith(".coolapk.com")
    }

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
            cookieManager.setCookie(url, "$value; path=/")
            if (host != "m.coolapk.com") {
                cookieManager.setCookie(
                    "https://m.coolapk.com/",
                    "$value; domain=.coolapk.com; path=/"
                )
            }
        }
    }

    override fun onDestroyView() {
        try {
            webView?.apply {
                loadUrl("about:blank")
                parent?.let { (it as ViewGroup).removeView(this) }
                stopLoading()
                settings.javaScriptEnabled = false
                clearHistory()
                removeAllViews()
                webChromeClient = null
                onPause()
                destroy()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        webView = null
        super.onDestroyView()
    }
}
