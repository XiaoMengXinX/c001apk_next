package com.example.c001apk.util

import android.util.Log
import com.example.c001apk.MyApplication
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * SSL 证书校验（「设置 - 高级 - 校验 SSL 证书」开关，默认开）。
 *
 * 开启时的校验策略（两道关，都要过）：
 *
 * 1. **平台校验**：走系统证书库做标准 PKIX 校验（签名链、有效期在平台侧校验，
 *    域名由 OkHttp 的 hostnameVerifier 校验）。这一步保证与系统一致，
 *    能正常处理 SHA-1 遗留根等历史包袱（如 api.coolapk.com 的
 *    DigiCert Global Root CA —— Conscrypt 只允许**系统库**里的锚点使用 SHA-1，
 *    所以这一步不能省，否则服务器塞的老根会让整条链被拒）。
 *
 * 2. **锚点白名单**：证书链的信任锚必须落在应用内置的 CA 库里
 *    （res/raw/cacert_000.pem ~ cacert_121.pem，122 张 = Mozilla CA 全集
 *    https://curl.se/ca/cacert.pem + 追加的 DigiCert Global Root CA 历史根。
 *    酷安全站（api/www/m/account.coolapk.com）的证书链都锚在这张 SHA-1 老根上，
 *    Mozilla 政策已把它除名，但系统/Chrome 仍信任，必须显式补上才能过白名单）。
 *    用户自己装的抓包证书（Charles/Fiddler/mitmproxy）、厂商预置的私有根、
 *    被劫持后替换的 CA，都不在库里 → 一律拒绝。
 *
 * 另外：平台校验失败时（例如旧系统缺新根证书），回退到「只用内置 CA 做完整 PKIX 校验」，
 * 内置库齐全，所以老系统也不会掉链子。
 *
 * 关闭开关则回落到系统默认校验（OkHttp 层不再做白名单限制）。
 * 注意：WebView / Glide 等系统默认网络栈走的是 res/xml/network_security_config.xml
 * （信任锚 = 仅内置 Mozilla CA，不受本开关控制，修改 NSC 需重新构建安装）。
 *
 * 「网络传输调试模式」（[PrefManager.isSslDebug]，设置里紧跟本开关的第二个开关，
 * 与严格校验互斥）开启后**彻底跳过校验**，方便抓包调试：
 * - OkHttp：[apply] 装全放行 TrustManager + 全放行 hostnameVerifier；
 * - HttpsURLConnection 默认栈（Glide 默认图片栈等）：[applyDebugGlobally]；
 * - WebView：onReceivedSslError 里直接 handler.proceed()。
 *
 * 注意：开关修改后需**重启应用**才生效（OkHttpClient 是单例，构建时固化了 TrustManager）。
 */
object SslVerify {

    private const val TAG = "SslVerify"

    /** 内置 Mozilla CA 根证书列表（懒加载，首次网络请求时构建一次） */
    private val bundledAnchors: List<X509Certificate> by lazy {
        val certFactory = CertificateFactory.getInstance("X.509")
        val context = MyApplication.context
        val resources = context.resources
        val anchors = mutableListOf<X509Certificate>()
        var index = 0
        while (true) {
            // 资源名固定三位数：cacert_000 ~ cacert_121
            val resId = resources.getIdentifier(
                "cacert_%03d".format(index), "raw", context.packageName
            )
            if (resId == 0) break
            resources.openRawResource(resId).use { input ->
                certFactory.generateCertificates(input).forEach { cert ->
                    (cert as? X509Certificate)?.let { anchors.add(it) }
                }
            }
            index++
        }
        check(anchors.isNotEmpty()) { "内置 CA 证书库为空，请检查 res/raw/cacert_*.pem" }
        Log.i(TAG, "内置 Mozilla CA 库加载完成：${anchors.size} 张根证书")
        anchors
    }

    /** 只用内置 CA 做 PKIX 校验的 TrustManager */
    private val bundledTrustManager: X509TrustManager by lazy {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        bundledAnchors.forEachIndexed { i, cert ->
            keyStore.setCertificateEntry("bundled_ca_$i", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * 平台 TrustManager（系统证书库 AndroidCAStore = 系统 + 用户安装的 CA）。
     *
     * 显式 init(AndroidCAStore) 而不是 init(null)：network_security_config.xml 已把
     * NSC 信任锚改成「仅内置 Mozilla CA」（用于管住 WebView / Glide 等系统默认栈），
     * 显式指定证书库可以保证这里的平台校验语义不受 NSC 改动影响、保持稳定。
     */
    private val platformTrustManager: X509TrustManager by lazy {
        val systemKeyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(systemKeyStore)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * 证书链是否锚定在内置 Mozilla CA 库里。
     * - 服务器带了根证书：链中某张证书就是内置根；
     * - 服务器省略了根：链顶证书由内置根签发。
     */
    private fun anchoredInBundle(chain: Array<X509Certificate>): Boolean {
        for (cert in chain) {
            if (bundledAnchors.contains(cert)) return true
        }
        val top = chain.lastOrNull() ?: return false
        return bundledAnchors.any { anchor ->
            anchor.subjectX500Principal == top.issuerX500Principal &&
                    runCatching { top.verify(anchor.publicKey) }.isSuccess
        }
    }

    private val verifyingTrustManager: X509TrustManager by lazy {
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                platformTrustManager.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                // 第 1 关：平台校验（能兼容 SHA-1 遗留根等历史包袱）
                val platformOk = runCatching {
                    platformTrustManager.checkServerTrusted(chain, authType)
                }.isSuccess

                if (platformOk) {
                    // 第 2 关：锚点必须在内置 Mozilla CA 库里
                    if (!anchoredInBundle(chain)) {
                        throw CertificateException(
                            "证书链的信任锚不在内置 Mozilla CA 库中，疑似中间人证书"
                        )
                    }
                    return
                }

                // 平台校验收不了（旧系统缺新根证书等）：回退到只用内置 CA 的完整 PKIX 校验，
                // 校验不过会抛 CertificateException
                Log.w(TAG, "平台校验未通过，改用内置 Mozilla CA 库校验")
                bundledTrustManager.checkServerTrusted(chain, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                bundledTrustManager.acceptedIssuers
        }
    }

    /** 调试模式用的全放行 TrustManager：不校验证书链 */
    private val trustingTrustManager: X509TrustManager by lazy {
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    /** 调试模式用的全放行域名校验：证书里写的是哪个域名都放行 */
    private val trustingHostnameVerifier = HostnameVerifier { _, _ -> true }

    /** 调试模式：跳过全部校验（证书链 + 域名） */
    fun applyDebug(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        Log.w(TAG, "网络传输调试模式：OkHttp 跳过全部 SSL 校验")
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustingTrustManager), SecureRandom())
        }
        return builder
            .sslSocketFactory(sslContext.socketFactory, trustingTrustManager)
            .hostnameVerifier(trustingHostnameVerifier)
    }

    /**
     * 调试模式：把进程级 HttpsURLConnection 的默认校验也放开。
     *
     * WebView 走自己的网络栈（onReceivedSslError 放行）、OkHttp 走 [apply]，
     * 而 Glide 的默认图片栈等用 HttpsURLConnection 的库吃的是这个进程级默认值，
     * 不放开的话抓包时图片会全挂。开关本身要求重启应用，Application 启动调用一次即可。
     */
    fun applyDebugGlobally() {
        if (!PrefManager.isSslDebug) return
        runCatching {
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustingTrustManager), SecureRandom())
            }
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(trustingHostnameVerifier)
            Log.w(TAG, "网络传输调试模式：HttpsURLConnection 默认栈跳过全部 SSL 校验")
        }.onFailure { Log.w(TAG, "放开 HttpsURLConnection 默认校验失败", it) }
    }

    /**
     * 图片加载（Mojito 的 Glide 走 OkHttp）用的客户端，**必须始终给一个**。
     *
     * 为什么不能返回 null：模块默认拿到的客户端没有 User-Agent，而图片 CDN
     * （image.coolapk.com 前面的腾讯云 EdgeOne）会按 UA 放行，UA 不对直接回
     * 567 + 「请求已被站点的安全策略拦截」的 HTML 页 —— 表现就是点开大图黑屏。
     * 这里统一补上酷安 UA：与 [AddCookiesInterceptor] 同一份 iOS UA
     * （[IosDeviceUtils.coolapkUserAgent]），请求时才读值，设备档案变更后也能跟上。
     *
     * SSL 校验沿用系统默认（不套 [apply] 的白名单），与改动前的行为一致；
     * 只有开了「网络传输调试模式」才整体放行，方便抓包。
     */
    fun imageClient(): OkHttpClient {
        val builder = OkHttpClient.Builder().addInterceptor { chain ->
            val ua = IosDeviceUtils.coolapkUserAgent()
            chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build())
        }
        return if (PrefManager.isSslDebug) applyDebug(builder).build() else builder.build()
    }

    /** 应用到 OkHttp 客户端；开关关闭时原样返回（走系统默认校验） */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        // 调试模式优先：彻底跳过校验
        if (PrefManager.isSslDebug) return applyDebug(builder)
        if (!PrefManager.isVerifySsl) return builder

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(verifyingTrustManager), SecureRandom())
        }
        return builder.sslSocketFactory(sslContext.socketFactory, verifyingTrustManager)
    }
}
