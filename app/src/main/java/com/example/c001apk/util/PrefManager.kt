package com.example.c001apk.util

import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.example.c001apk.MyApplication.Companion.context
import com.example.c001apk.constant.Constants

object PrefManager {

    private const val PREF_DARK_THEME = "dark_theme"
    private const val PREF_BLACK_DARK_THEME = "black_dark_theme"
    private const val PREF_FOLLOW_SYSTEM_ACCENT = "follow_system_accent"
    private const val PREF_THEME_COLOR = "theme_color"
    private const val SHOW_EMOJI = "show_emoji"
    private const val UID = "uid"
    private const val NAME = "name"
    private const val TOKEN = "token"
    private const val REFRESH_TOKEN = "refreshToken"

    private val pref = context.getSharedPreferences("settings", MODE_PRIVATE)

    var darkTheme: Int
        get() = pref.getInt(PREF_DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(PREF_DARK_THEME, value).apply()

    var blackDarkTheme: Boolean
        get() = pref.getBoolean(PREF_BLACK_DARK_THEME, false)
        set(value) = pref.edit().putBoolean(PREF_BLACK_DARK_THEME, value).apply()

    var followSystemAccent: Boolean
        get() = pref.getBoolean(PREF_FOLLOW_SYSTEM_ACCENT, true)
        set(value) = pref.edit().putBoolean(PREF_FOLLOW_SYSTEM_ACCENT, value).apply()

    var themeColor: String
        get() = pref.getString(PREF_THEME_COLOR, "MATERIAL_DEFAULT")!!
        set(value) = pref.edit().putString(PREF_THEME_COLOR, value).apply()

    var showEmoji: Boolean
        get() = pref.getBoolean(SHOW_EMOJI, true)
        set(value) = pref.edit().putBoolean(SHOW_EMOJI, value).apply()

    var isLogin: Boolean
        get() = pref.getBoolean("isLogin", false)
        set(value) = pref.edit().putBoolean("isLogin", value).apply()

    var uid: String
        get() = pref.getString(UID, "")!!
        set(value) = pref.edit().putString(UID, value).apply()

    var username: String
        get() = pref.getString(NAME, "")!!
        set(value) = pref.edit().putString(NAME, value).apply()

    var token: String
        get() = pref.getString(TOKEN, "")!!
        set(value) = pref.edit().putString(TOKEN, value).apply()

    /**
     * 第二凭据：启动时用 [token] 调 `checkLoginInfo` 换来的（响应里带 `refreshToken`）。
     *
     * 早期研究结论（`_rev/win_ref/verify_token_live.py` + `DETAIL_5902.md:145`）：
     * `login token` 与 `refreshToken` 在业务接口上**表现逐项一致** ——
     * 它不是 OAuth 那种刷新令牌，而是**第二个等效凭据** ✓
     *
     * 所以非空时业务请求优先用它（见 `AddCookiesInterceptor`），生命周期 = 本次进程。
     */
    var refreshToken: String
        get() = pref.getString(REFRESH_TOKEN, "")!!
        set(value) = pref.edit().putString(REFRESH_TOKEN, value).apply()

    var userAvatar: String
        get() = pref.getString("userAvatar", "")!!
        set(value) = pref.edit().putString("userAvatar", value).apply()

    var level: String
        get() = pref.getString("level", "")!!
        set(value) = pref.edit().putString("level", value).apply()

    var experience: String
        get() = pref.getString("experience", "")!!
        set(value) = pref.edit().putString("experience", value).apply()

    var nextLevelExperience: String
        get() = pref.getString("nextLevelExperience", "")!!
        set(value) = pref.edit().putString("nextLevelExperience", value).apply()

    var xAppToken: String
        get() = pref.getString("xAppToken", "")!!
        set(value) = pref.edit().putString("xAppToken", value).apply()

    /** 设备指纹版本：低于 TokenDeviceUtils.FINGERPRINT_VERSION 时会被强制重置为默认指纹 */
    var DEVICE_FINGERPRINT_VERSION: Int
        get() = pref.getInt("DEVICE_FINGERPRINT_VERSION", 0)
        set(value) = pref.edit().putInt("DEVICE_FINGERPRINT_VERSION", value).apply()

    /**
     * 用户是否**显式**指定过自定义 X-App-Device。
     *
     * 「设置 - 参数」里手填 X-App-Device、点「重新生成」、或改厂商/品牌/型号/版本号任一项都会置位；
     * 未置位时 [com.example.c001apk.util.TokenDeviceUtils.getLastingDeviceCode] 会把设备串
     * 强制拉回官方认可的那一组（否则详情页会被风控要求验证码）。
     */
    var customFingerprint: Boolean
        get() = pref.getBoolean("customFingerprint", false)
        set(value) = pref.edit().putBoolean("customFingerprint", value).apply()

    /**
     * 是否把**本机真实机型**上报给服务器（默认开）。
     *
     * 服务端用 `X-App-Device` 里的「厂商/品牌/型号」认机型，决定帖子/回复下方那行
     * 「来自 xxx」。开启后每台手机上报自己的型号，不会再人人都是「一加13」；
     * 关掉则回落到 [com.example.c001apk.constant.Constants.DEFAULT_DEVICE_CODE]。
     */
    var reportRealDevice: Boolean
        get() = pref.getBoolean("reportRealDevice", true)
        set(value) = pref.edit().putBoolean("reportRealDevice", value).apply()

    var xAppDevice: String
        get() = pref.getString("xAppDevice", "")!!
        set(value) = pref.edit().putString("xAppDevice", value).apply()

    var customToken: Boolean
        get() = pref.getBoolean("customToken", false)
        set(value) = pref.edit().putBoolean("customToken", value).apply()

    var VERSION_NAME: String
        get() = pref.getString("VERSION_NAME", Constants.VERSION_NAME)!!
        set(value) = pref.edit().putString("VERSION_NAME", value).apply()

    var API_VERSION: String
        get() = pref.getString("API_VERSION", Constants.API_VERSION)!!
        set(value) = pref.edit().putString("API_VERSION", value).apply()

    var VERSION_CODE: String
        get() = pref.getString("VERSION_CODE", Constants.VERSION_CODE)!!
        set(value) = pref.edit().putString("VERSION_CODE", value).apply()

    var MANUFACTURER: String
        get() = pref.getString("MANUFACTURER", "")!!
        set(value) = pref.edit().putString("MANUFACTURER", value).apply()

    var BRAND: String
        get() = pref.getString("BRAND", "")!!
        set(value) = pref.edit().putString("BRAND", value).apply()

    var MODEL: String
        get() = pref.getString("MODEL", "")!!
        set(value) = pref.edit().putString("MODEL", value).apply()

    var BUILDNUMBER: String
        get() = pref.getString("BUILDNUMBER", "")!!
        set(value) = pref.edit().putString("BUILDNUMBER", value).apply()

    var SDK_INT: String
        get() = pref.getString("SDK_INT", "")!!
        set(value) = pref.edit().putString("SDK_INT", value).apply()

    var ANDROID_VERSION: String
        get() = pref.getString("ANDROID_VERSION", "")!!
        set(value) = pref.edit().putString("ANDROID_VERSION", value).apply()

    var USER_AGENT: String
        get() = pref.getString("USER_AGENT", "")!!
        set(value) = pref.edit().putString("USER_AGENT", value).apply()

    /**
     * 数字联盟 ID：设备串（`X-App-Device`）的首字段，同时用作 WebView 的 `DID` cookie。
     *
     * **默认为空** —— 客户端**不生成、也不内置**任何值。
     *
     * 它是数字联盟(SZLM) SDK 在真机上签发的设备标识：客户端既无从获得，凭空造一个
     * 也不被服务端认（详情页会被要求人机验证，且过码后同一设备串仍被拒，
     * 实测见 `_rev/probe_trust.py`）。所以「填随机值」并不能绕过去，只是换一种被拦的方式。
     *
     * 历史版本曾把某个真实设备的 szlmId 写死后分发出去，导致大量真实账号被服务端
     * 算到「同一台设备」上并触发 `-415 账号过多`（连 `feed/replyList` 都被拒）。
     * 留空后服务端只把本机当陌生设备：首页 / 搜索 / 个人页 / 回复列表正常，
     * 仅 `feed/detail` 一类接口可能要求验证码。
     *
     * 手头有自己设备那份 ID 的用户可以填进来（[szlmIdConfigured] 随之置位，说明弹窗不再出现）。
     */
    var SZLMID: String
        get() = pref.getString("SZLMID", "") ?: ""
        set(value) = pref.edit().putString("SZLMID", value).apply()

    /** 用户是否在设置里显式填过自己的数字联盟 ID（留空时为 false） */
    var szlmIdConfigured: Boolean
        get() = pref.getBoolean("szlmIdConfigured", false)
        set(value) = pref.edit().putBoolean("szlmIdConfigured", value).apply()

    /** 本次安装是否已经告知过「设备标识」的说明，避免反复打扰 */
    var szlmIdNoticed: Boolean
        get() = pref.getBoolean("szlmIdNoticed", false)
        set(value) = pref.edit().putBoolean("szlmIdNoticed", value).apply()

    var isRecordHistory: Boolean
        get() = pref.getBoolean("isRecordHistory", true)
        set(value) = pref.edit().putBoolean("isRecordHistory", value).apply()

    var FONTSCALE: String
        get() = pref.getString("FONTSCALE", "1.00")!!
        set(value) = pref.edit().putString("FONTSCALE", value).apply()

    var isIconMiniCard: Boolean
        get() = pref.getBoolean("isIconMiniCard", true)
        set(value) = pref.edit().putBoolean("isIconMiniCard", value).apply()

    var isOpenLinkOutside: Boolean
        get() = pref.getBoolean("isOpenLinkOutside", false)
        set(value) = pref.edit().putBoolean("isOpenLinkOutside", value).apply()

    var FOLLOWTYPE: String
        get() = pref.getString("FOLLOWTYPE", "all")!!
        set(value) = pref.edit().putString("FOLLOWTYPE", value).apply()

    var imageQuality: String
        get() = pref.getString("imageQuality", "auto")!!
        set(value) = pref.edit().putString("imageQuality", value).apply()

    var isColorFilter: Boolean
        get() = pref.getBoolean("isColorFilter", true)
        set(value) = pref.edit().putBoolean("isColorFilter", value).apply()

    /**
     * 水平转场动画曲线（实验项，见 [com.example.c001apk.util.TransitionAnim]）。
     * 取值 linear / m2 / std / emph，默认 `emph` 与已改造的正式资源一致。
     */
    var animCurve: String
        get() = pref.getString("animCurve", TransitionAnim.CURVE_M3_EMPHASIZED)!!
        set(value) = pref.edit().putString("animCurve", value).apply()

    /**
     * 水平转场动画类型（实验项）：slide 水平滑动 / fade 淡入淡出 / none 无动画。
     * `parallax` 已从设置里摘掉（旧页退半屏，和 slide 观感重复），
     * 兜底把存过的旧值折成 slide，否则下拉框会显示不出当前项。
     */
    var animType: String
        get() = pref.getString("animType", TransitionAnim.TYPE_SLIDE)!!
            .let { if (it == TransitionAnim.TYPE_PARALLAX) TransitionAnim.TYPE_SLIDE else it }
        set(value) = pref.edit().putString("animType", value).apply()

    /** 水平转场进入时长(ms)，实验项；退出固定为进入 - 50ms。存成字符串以便设置页用下拉框。 */
    var animDuration: Int
        get() = pref.getString("animDuration", "300")!!.toIntOrNull() ?: 300
        set(value) = pref.edit().putString("animDuration", value.toString()).apply()

    /** 启动时检查本应用正式版更新（升级信息走自建接口，默认开） */
    var isCheckUpdateStable: Boolean
        get() = pref.getBoolean("isCheckUpdateStable", true)
        set(value) = pref.edit().putBoolean("isCheckUpdateStable", value).apply()

    /**
     * 用户随机 ID（上报头 `X-App-userrandomid`；Prefs 键沿用早期的 `useradomid` 不改，
     * 改了会让已装用户被当成新用户重算）：本应用自己造的匿名标识，首次启动随机生成一份
     * 32 位 hex 落盘（[com.example.c001apk.MyApplication] 启动时初始化），
     * 之后只要不清应用数据就一直是这个值。
     *
     * 为什么不拿数字联盟 ID（DUID）去统计：DUID 是设备级实名标识，签发方（数字联盟）
     * 能把它反查回具体设备，上报它等于把用户的真实设备交给统计接口。换成本机自造的
     * 随机串后，上报走自更新接口的 `X-App-userrandomid` 请求头（见 [UpdateChecker]），
     * 服务端拿到的只是一个跟账号、跟设备都无关的随机值：
     * 换机 / 重装会变成新号，够用来数活跃设备与留存，但追不到人。
     */
    var userRandomId: String
        get() {
            pref.getString("useradomid", null)?.takeIf { it.isNotEmpty() }?.let { return it }
            // 老版本把这个值存在 updateUnionId 下，沿用一次，免得已统计过的用户被算成新用户
            pref.getString("updateUnionId", null)?.takeIf { it.isNotEmpty() }?.let {
                pref.edit().putString("useradomid", it).apply()
                return it
            }
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            pref.edit().putString("useradomid", id).apply()
            return id
        }
        set(value) = pref.edit().putString("useradomid", value).apply()

    /**
     * SSL 证书校验（默认开）。
     *
     * 开启时走 [SslVerify] 的双重校验（平台 PKIX + 信任锚必须落在内置 CA 库），
     * 可挡住抓包工具/厂商私有根证书做中间人；关闭则回落成系统默认校验。
     */
    var isVerifySsl: Boolean
        get() = pref.getBoolean("verifySsl", true)
        set(value) = pref.edit().putBoolean("verifySsl", value).apply()

    /**
     * 网络传输调试模式（默认关，仅供抓包调试）。
     *
     * 开启后 [SslVerify] 彻底跳过 SSL 校验：既不校验证书链也不校验域名，
     * 系统校验一并绕过（OkHttp / WebView / HttpsURLConnection 三层都放开）。
     *
     * 与 [isVerifySsl] 互斥：`校验 SSL 证书` 开着时本项被强制关闭；
     * 且开关修改后需**重启应用**才生效（客户端实例在启动时构建）。
     */
    var isSslDebug: Boolean
        get() = pref.getBoolean("sslDebug", false)
        set(value) = pref.edit().putBoolean("sslDebug", value).apply()

    /** 其他屏蔽项（关键字/用户/节点）的服务端配置缓存，离线也能先过滤 */
    var spamConfig: String
        get() = pref.getString("spamConfig", "")!!
        set(value) = pref.edit().putString("spamConfig", value).apply()

    /**
     * 用户认证表缓存（userverify 接口的原始响应，见 [RemoteConfig]）。
     * 每次启动拉一次新表覆盖；拉不到就用这份，所以离线也能看到认证标。
     */
    var userVerifyConfig: String
        get() = pref.getString("userVerifyConfig", "")!!
        set(value) = pref.edit().putString("userVerifyConfig", value).apply()

    /** 可信链接白名单缓存（reliablelink 接口的原始响应），同上 */
    var reliableLinkConfig: String
        get() = pref.getString("reliableLinkConfig", "")!!
        set(value) = pref.edit().putString("reliableLinkConfig", value).apply()

    var recentIds: String
        get() = pref.getString("recentIds", "")!!
        set(value) = pref.edit().putString("recentIds", value).apply()

    fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        pref.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        pref.unregisterOnSharedPreferenceChangeListener(listener)
    }
}