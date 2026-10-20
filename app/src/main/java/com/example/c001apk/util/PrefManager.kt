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

    /**
     * 用户是否**显式**指定过自定义 X-App-Device。
     *
     * 「设置 - 参数」里手填 X-App-Device、点「重新生成」、或改厂商/品牌/型号/版本号任一项都会置位。
     * 改用 iOS 身份后它只剩一个用途：决定 [SzlmIdPrompt] 换 szlmId 时要不要就地重建设备串。
     */
    var customFingerprint: Boolean
        get() = pref.getBoolean("customFingerprint", false)
        set(value) = pref.edit().putBoolean("customFingerprint", value).apply()

    // reportRealDevice 与 DEVICE_FINGERPRINT_VERSION 已删除：
    // 前者只服务于「上报真实机型」（Android 设备串），后者只服务于 getLastingDeviceCode()
    // 的指纹版本迁移。两者都随 Android 身份一起退出，改用 iOS 身份后无人读取。

    var xAppDevice: String
        get() = pref.getString("xAppDevice", "")!!
        set(value) = pref.edit().putString("xAppDevice", value).apply()

    // ===== iOS 设备身份（酷安 API 当前使用这一组，见 IosDeviceUtils） =====
    // 键统一加 ios_ 前缀，避免和上面历史遗留的 Android 机型字段互相覆盖。

    var iosDeviceId: String
        get() = pref.getString("ios_device_id", "")!!
        set(value) = pref.edit().putString("ios_device_id", value).apply()

    var iosModel: String
        get() = pref.getString("ios_model", "")!!
        set(value) = pref.edit().putString("ios_model", value).apply()

    var iosHardwareId: String
        get() = pref.getString("ios_hardware_id", "")!!
        set(value) = pref.edit().putString("ios_hardware_id", value).apply()

    /** 销售型号（如 `MTLD3CH/A`）；允许为空，为空时 `X-App-Device` 只写硬件 ID */
    var iosSalesModel: String
        get() = pref.getString("ios_sales_model", "")!!
        set(value) = pref.edit().putString("ios_sales_model", value).apply()

    var iosSystemVersion: String
        get() = pref.getString("ios_system_version", "")!!
        set(value) = pref.edit().putString("ios_system_version", value).apply()

    /**
     * 登录后从 CDID 服务换到的固定设备 ID（`D` 开头、48 字符）。
     *
     * 写接口（点赞/回复）只认这种服务端登记过的 ID；未登录时为空，此时用随机 UUID。
     * 只在登录成功后写入一次，之后长期复用（见 [IosDeviceUtils]）。
     */
    var iosCdid: String
        get() = pref.getString("ios_cdid", "")!!
        set(value) = pref.edit().putString("ios_cdid", value).apply()

    /** 换取 CDID 时上报的随机 IDFV；持久化后同一安装的重试使用同一份设备信息 */
    var iosIdfv: String
        get() = pref.getString("ios_idfv", "")!!
        set(value) = pref.edit().putString("ios_idfv", value).apply()

    /** 换取 CDID 时上报的随机安装 ID */
    var iosInstallId: String
        get() = pref.getString("ios_install_id", "")!!
        set(value) = pref.edit().putString("ios_install_id", value).apply()

    /**
     * 一次性写入整组 iOS 设备身份。
     *
     * 用 `commit()` 而不是多次 `apply()`：首次生成 UUID 时若进程在写入完成前被杀，
     * 下次启动会读到空值再生成第二个 UUID，导致同一安装出现两个设备身份。
     */
    internal fun saveIosDevice(
        id: String,
        model: String,
        hardwareId: String,
        salesModel: String,
        systemVersion: String,
    ) {
        pref.edit()
            .putString("ios_device_id", id)
            .putString("ios_model", model)
            .putString("ios_hardware_id", hardwareId)
            .putString("ios_sales_model", salesModel)
            .putString("ios_system_version", systemVersion)
            .commit()
    }

    var customToken: Boolean
        get() = pref.getBoolean("customToken", false)
        set(value) = pref.edit().putBoolean("customToken", value).apply()

    // VERSION_NAME / VERSION_CODE / API_VERSION 已删除：它们原本给 Android 身份的请求头
    // 与 UA 用，改用 iOS 身份后版本号来自 IosHeaders.APP_VERSION / APP_CODE，
    // 这几个字段再没有读取方（继续留着会让后来人以为还在用）。

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

    // SDK_INT / ANDROID_VERSION / USER_AGENT 已删除：同上，Android 身份与 UA 都不再使用。
    // 尤其是 USER_AGENT —— 它曾被 MainViewModel 用这几个空字段拼成
    // `Dalvik/2.1.0 (Linux; U; Android ;  )` 这种极好识别的假 UA 发给图片 CDN。

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

    /**
     * 系统派发给**本应用**的设备标识（`Settings.Secure.ANDROID_ID`，即 SSAID）。
     *
     * ## 为什么加它（2026-10-18）
     * `userRandomId` 是首启随机生成的 ✗ **清数据 / 重装就会变成新的一串** ✗
     * ⇒ 统计里同一个人会被重复计数 ✗
     *
     * SSAID 在 **Android 8.0+** 是按「应用签名密钥 + 用户 + 设备」派生的：
     *   - 清应用数据 ⇒ **不变** ✓
     *   - 卸载重装   ⇒ **不变** ✓
     *   - 恢复出厂   ⇒ 变 ✗（可接受）
     *   - **别的应用拿到的值不同** ✓ ⇒ 不构成跨应用关联，隐私性质和随机 ID 相近 ✓
     *     （Android 8 之前它是设备级共享的，那条隐私担忧已过时）
     *
     * ## 上报方式
     * 与 `userRandomId` **同时**上报（客户端不替服务端做去重 ✗）：
     *   X-App-userrandomid : 老的随机 ID（兼容历史数据 ✓）
     *   X-Client-SSaid     : 新增的 SSAID ✓
     * 云端用 `COALESCE(ssaid, uid)` 去重 ✓ —— 老客户端不带 ssaid 就回落随机 ID ✓
     */
    var ssaid: String
        get() = pref.getString("SSAID", "") ?: ""
        set(value) = pref.edit().putString("SSAID", value).apply()

    /**
     * 是否记录 HTTP 请求日志（设置 → 高级，**默认关闭** ✓）
     *
     * 打开后 `HttpLog.Interceptor` 会把每个请求的方法/地址/状态码/耗时/
     * 响应体（前 2 KB）写到 `filesDir/http_logs/YYYYMMDD.log` ✓
     * 敏感字段在本机脱敏（只留长度）✓ 见 `util/HttpLog.kt`
     *
     * ⚠️ 关着时拦截器只做一次 bool 判断 ✓ **零开销** ✓
     */
    var recordHttpLog: Boolean
        get() = pref.getBoolean("recordHttpLog", false)
        set(value) = pref.edit().putBoolean("recordHttpLog", value).apply()
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