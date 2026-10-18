package com.example.c001apk.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.example.c001apk.MyApplication.Companion.context
import com.example.c001apk.R
import com.example.c001apk.ui.app.AppActivity
import com.example.c001apk.ui.carousel.CarouselActivity
import com.example.c001apk.ui.coolpic.CoolPicActivity
import com.example.c001apk.ui.dyh.DyhActivity
import com.example.c001apk.ui.event.EventDetailActivity
import com.example.c001apk.ui.feed.FeedActivity
import com.example.c001apk.ui.others.WebViewActivity
import com.example.c001apk.ui.topic.TopicActivity
import com.example.c001apk.ui.user.UserActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder


object NetWorkUtil {

    /** 剪贴板里的文本超过这个长度就别扫了（可能是整页日志，不是分享链接） */
    private const val MAX_LINK_TEXT_LENGTH = 4096

    /**
     * http(s) 分享链接认这几个域名，与 [openLink] 能处理的（manifest 里 AppLinkActivity 声明的）一致。
     * `m.coolapk.com` 是手机浏览器里的移动站，地址栏复制出来的就是它，也一并认。
     */
    private val LINK_HOSTS = setOf(
        "coolapk.com", "www.coolapk.com", "m.coolapk.com",
        "coolapk1s.com", "www.coolapk1s.com", "m.coolapk1s.com"
    )

    /** coolmarket:// 的 host 名单 */
    private val LINK_SCHEME_HOSTS =
        setOf("feed", "u", "apk", "live", "com.coolapk.market", "www.coolapk.com", "www.coolapk1s.com")

    private val SHARE_LINK_PATTERN =
        Regex("""(?:https?|coolmarket)://[^\s"'<>）)、，。；;】]+""")

    private val WHITESPACE = Regex("""\s+""")

    /** 句末标点跟着链接一起被复制过来是常态，校验前先削掉 */
    private val TRAILING_PUNCTUATION = charArrayOf(
        '.', ',', '。', '，', '、', ')', '）', ']', '】',
        // 用 "'"[0] 而不是 '\''：kapt 生成 Java 桩时会把转义单引号写坏（Kotlin 1.9 kapt bug）
        '"', "'"[0], '!', '！'
    )

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network: Network? = cm.activeNetwork
        if (network != null) {
            val nc = cm.getNetworkCapabilities(network)
            nc?.let {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return true
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return false
                }
            }
        }
        return false
    }

    /**
     * 从任意文本里揪出第一段能打开的酷安链接（剪贴板、搜索框共用），找不到返回 null。
     *
     * 只认 [LINK_HOSTS] / [LINK_SCHEME_HOSTS] 这几个域名，也就是 [openLink] 真能处理的那些：
     * 剪贴板里躺着的大多是随手一段话，「看着像链接就弹」会变成骚扰。
     *
     * 微信 / QQ 的分享文案会把链接和说明黏在一起（「分享自酷安https://www.coolapk.com/feed/1?s=abc」），
     * 所以先扫带 scheme 的子串，再退回按空白切词找裸域名（有些 App 复制时会把 `https://` 吃掉）。
     */
    fun findCoolapkLink(text: String): String? {
        val raw = text.trim()
        if (raw.isEmpty() || raw.length > MAX_LINK_TEXT_LENGTH) return null
        SHARE_LINK_PATTERN.findAll(raw).forEach {
            linkOfSchemeUrl(it.value)?.let { link -> return link }
        }
        for (token in raw.split(WHITESPACE)) {
            linkOfBareHost(token)?.let { link -> return link }
        }
        return null
    }

    /**
     * 带 scheme 的候选：域名得是我们能打开的，而且要落到具体路径上（只有域名首页不算分享链接）。
     *
     * scheme 和 host **故意不转小写**：下面的比对就是 [openLink] 的能力边界本身 ——
     * 那边是大小写敏感的字符串替换，`HTTPS://WWW.COOLAPK.COM/feed/1` 它一样打不开。
     * 这里若宽容地认下来，用户点「确认」只会换回一句 unsupported url。
     */
    private fun linkOfSchemeUrl(candidate: String): String? {
        val text = candidate.trimEnd(*TRAILING_PUNCTUATION)
        if (text.isEmpty()) return null
        val uri = Uri.parse(text)
        return when (uri.scheme) {
            "http", "https" -> {
                if (uri.host.orEmpty() in LINK_HOSTS && uri.path.orEmpty().length > 1) text else null
            }

            "coolmarket" -> if (uri.host.orEmpty() in LINK_SCHEME_HOSTS) text else null
            else -> null
        }
    }

    /**
     * 裸域名候选（`www.coolapk.com/feed/74194931?s=...`）：[openLink] 自己会补 scheme，
     * 这里只校验形状。必须带路径 —— 光粘个 `coolapk.com` 打不开任何具体页面，不该算分享链接。
     */
    private fun linkOfBareHost(candidate: String): String? {
        val text = candidate.trimEnd(*TRAILING_PUNCTUATION)
        if (!text.contains('/')) return null
        val host = text.substringBefore('/')
        val path = text.substringAfter('/', "")
        return if (path.isNotEmpty() && host in LINK_HOSTS) text else null
    }

    fun openLink(context: Context, url: String, title: String?) {
        val replace = url
            .replace("coolmarket://", "/")
            .replace("https://", "")
            .replace("http://", "")
            .replace("www.", "")
            .replace("coolapk1s", "coolapk")
            // 手机浏览器里的移动站地址（m.coolapk.com/feed/<id>）要先把前导的 m. 收掉：
            // 否则下面削掉 coolapk.com 之后只剩 "m./feed/<id>"，所有 startsWith 全部落空。
            // 只吃 "m.coolapk" 这个子串，www./api./image. 这些都不含它，互不影响。
            .replace("m.coolapk", "coolapk")
            .replace("coolapk.com", "")
            // coolmarket://<host>/... 这类要单独收尾，两个坑：
            // 1. 换 scheme 时留下了一个前导 "/"，再削掉 www. / coolapk.com 之后就成了
            //    `//feed/74194931`；多这一个斜杠会让下面所有 startsWith 全部落空，
            //    直接掉进 else 弹「unsupported url」——浏览器里「用 App 打开」给的正是
            //    这个形态（coolmarket://www.coolapk.com/feed/74194931?s=...）。
            //    注意只能压「两个及以上」的前导斜杠：`#/feed/xxx`（coolpic）那支不能被碰。
            // 2. com.coolapk.market 也是我们自己在 manifest 里声明支持的 host，
            //    不削掉同样会落到 else。
            .replace("com.coolapk.market", "")
            .replaceFirst(Regex("^//+"), "/")

        if (replace.startsWith("/feed/")) {
            with(replace.indexOfFirst { it == '?' }) {
                IntentUtil.startActivity<FeedActivity>(context) {
                    putExtra(
                        "id",
                        if (this@with != -1) replace.substring(6, this@with)
                        else replace.substring(6)
                    )
                    if (this@with != -1) {
                        replace.indexOf("rid=").let {
                            if (it != -1) {
                                putExtra("rid", replace.substring(it + 4))
                                putExtra("viewReply", true)
                            }
                        }
                    }
                }
            }
        } else if (replace.startsWith("/picture/")) {
            with(replace.indexOfFirst { it == '?' }) {
                IntentUtil.startActivity<FeedActivity>(context) {
                    putExtra(
                        "id",
                        if (this@with != -1) replace.substring(9, this@with)
                        else replace.substring(9)
                    )
                }
            }
        } else if (replace.startsWith("#/feed/")) { // iconLinkGridCard-coolpic
            IntentUtil.startActivity<CarouselActivity>(context) {
                putExtra("title", title)
                putExtra("url", url)
            }
        } else if (replace.startsWith("/apk/") || replace.startsWith("/game/")) {
            IntentUtil.startActivity<AppActivity>(context) {
                putExtra("id", replace.replace("/apk/", "").replace("/game/", ""))
            }
        } else if (replace.startsWith("/u/")) {
            IntentUtil.startActivity<UserActivity>(context) {
                putExtra("id", replace.substring(3))
            }
        } else if (replace.startsWith("/t/")) {
            if (replace.contains("?type=8")) {
                IntentUtil.startActivity<CoolPicActivity>(context) {
                    putExtra("title", replace.substring(3, replace.indexOfFirst { it == '?' }))
                }
            } else {
                with(replace.indexOfFirst { it == '?' }) {
                    val param = if (this@with != -1) replace.substring(3, this@with)
                    else replace.substring(3)
                    IntentUtil.startActivity<TopicActivity>(context) {
                        putExtra("type", "topic")
                        putExtra("url", param)
                        putExtra("title", param)
                    }
                }
            }
        } else if (replace.startsWith("/product/productList")) {
            // 机型对比列表页（同价位 / 同SoC / 同系列）：产品页「参数」tab 里 listCard 卡片
            // 的 url 是服务端下发的**列表 url**，形如
            //   /product/productList?type=series&id=1546&categoryId=1000&title=数字系列&entityTemplate=productSelect
            // 它必须整条交给 /v6/page/dataList（CarouselActivity 走的就是这条），
            // 单页时 CarouselPagerFragment 会自动隐藏 tab 栏、用 title 当标题。
            // 若落到下面的 /product/<id> 分支，substring(9) 会把 "productList?type=..." 整个
            // 当成机型 id 去请求 /v6/product/detail，服务端返回「手机吧ID不能为空」+ data=null，
            // 列表就永远是空的。
            IntentUtil.startActivity<CarouselActivity>(context) {
                putExtra("title", title)
                putExtra("url", replace)
            }
        } else if (replace.startsWith("/product/")) {
            IntentUtil.startActivity<TopicActivity>(context) {
                putExtra("type", "product")
                putExtra("title", title)
                putExtra("id", replace.substring(9))
            }
        } else if (replace.startsWith("/event/") || replace.contains("./event/")) {
            // 众测/活动详情没有 H5，www.coolapk.com/event/<id> 只是下载引导落地页。
            // 改为原生接口 /v6/event/detail（EventDetailActivity）。
            val id = replace.substring(replace.indexOf("/event/") + 7)
                .substringBefore('?')
                .substringBefore('/')
            IntentUtil.startActivity<EventDetailActivity>(context) {
                putExtra("id", id)
            }
        } else if (replace.startsWith("/activity/") || replace.contains("./activity/")) {
            // 活动 H5 落地页（m.coolapk.com/activity/<name>）
            IntentUtil.startActivity<WebViewActivity>(context) {
                putExtra(
                    "url",
                    "https://m.coolapk.com/activity/" + replace.substring(replace.indexOf("/activity/") + 10)
                )
            }
        } else if (replace.startsWith("#/page?url=") || replace.startsWith("/page?url=")) {
            IntentUtil.startActivity<CarouselActivity>(context) {
                putExtra("url", replace.replace("#/page?url=", "").replace("/page?url=", ""))
                putExtra("title", title)
            }
        } else if (replace.startsWith("#/topic/") || replace.startsWith("/topic/")) {
            // 服务端下发的「栏目」url 必须整条交给 /v6/page/dataList，sort / keywords /
            // ratingUI 这些参数才带得过去（和 #/topic/userFollowTagList 同一条链路）。
            // 游戏频道卡片右上角的「榜单」给的就是这种：
            //   #/topic/tagList?keywords=2025游戏%2c…&sort=hot_num&ratingUI=1&title=🎮 热门新游
            // 之前落到末尾的 else，只会弹「unsupported url」。
            // withConfigCard 那对参数是让服务端多下发一张空的 configCard（「默认配置」），
            // 通用列表渲染不了它、会多出一条空白卡片，所以剥掉（实测剥掉后正好 20 条榜单项）。
            IntentUtil.startActivity<CarouselActivity>(context) {
                putExtra(
                    "url",
                    replace
                        .replace(Regex("[&?]withConfigCard=[^&]*"), "")
                        .replace(Regex("[&?]configCardExtraData=[^&]*"), "")
                )
                putExtra("title", title)
            }
        } else if (replace.startsWith("image.coolapk.com")) {
            ImageUtil.startBigImgViewSimple(context, url.http2https)
        } else if (url.startsWith("https://") || url.startsWith("http://")) {
            val trusted = LinkGuard.isTrusted(url)
            if (PrefManager.isOpenLinkOutside) {
                // 外跳是不可逆的（离开应用、换到浏览器），可信域名直接走，
                // 不在白名单里的先弹一次确认，看好域名再决定
                if (trusted) openOutside(context, url) else confirmLeaveApp(context, url)
            } else {
                // 应用内 WebView：不在白名单里也不拦，由 WebViewActivity 在页面底部
                // 挂一条风险提示（网页内部继续跳转还会重新判定）
                IntentUtil.startActivity<WebViewActivity>(context) {
                    putExtra("url", url)
                }
            }
        } else {
            Toast.makeText(context, "unsupported url: $url", Toast.LENGTH_SHORT).show()
            ClipboardUtil.copyText(context, url, false)
        }
    }

    /** 交给系统浏览器 / 其它 App 打开 */
    private fun openOutside(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "打开失败", Toast.LENGTH_SHORT).show()
            Log.w("error", "Activity was not found for intent, $intent")
        }
    }

    /**
     * 非可信链接外跳前的二次确认。
     * 弹窗只能挂在 Activity 上，而 [openLink] 的调用方偶尔会传 Application context，
     * 所以这里解包一次；实在拿不到就退化成 Toast（至少别静默什么都不做）。
     */
    private fun confirmLeaveApp(context: Context, url: String) {
        val activity = context.findActivity()
        val message = "你即将离开 c001apk next，前往 ${LinkGuard.hostOf(url)}"
        if (activity == null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_external_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.link_external_confirm) { _, _ -> openOutside(activity, url) }
            .show()
    }

    /** 顺着 ContextWrapper 一路找到 Activity，找不到返回 null */
    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    fun openLinkDyh(type: String, mContext: Context, url: String, id: String, title: String?) {
        when (type) {
            "feedRelation" -> {
                IntentUtil.startActivity<DyhActivity>(mContext) {
                    putExtra("id", id)
                    putExtra("title", title)
                }
            }

            "topic", "product" -> {
                IntentUtil.startActivity<TopicActivity>(mContext) {
                    putExtra("type", type)
                    putExtra("title", title)
                    putExtra("url", url)
                    putExtra("id", id)
                }
            }

            else -> openLink(mContext, url, title)
        }
    }

}