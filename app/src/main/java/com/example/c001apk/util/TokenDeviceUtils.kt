package com.example.c001apk.util

import android.content.Context
import com.example.c001apk.constant.Constants
import java.util.Random


object TokenDeviceUtils {

    fun randHexString(@Suppress("SameParameterValue") n: Int): String {
        Random().setSeed(System.currentTimeMillis())
        return (0 until n).joinToString("") {
            Random().nextInt(256).toString(16)
        }.uppercase()
    }

    /** 本机真实机型；[deviceCode] 即上报给服务器的设备串 */
    data class RealDevice(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val buildNumber: String,
        val androidVersion: String,
        val sdkInt: String,
        val deviceCode: String,
    )

    /** 读系统属性得到本机机型（机型检测的入口） */
    fun detectRealDevice(): RealDevice {
        val manufacturer = sanitize(android.os.Build.MANUFACTURER, Constants.DEFAULT_MANUFACTURER)
        val brand = sanitize(android.os.Build.BRAND, Constants.DEFAULT_BRAND)
        val model = sanitize(android.os.Build.MODEL, Constants.DEFAULT_MODEL)
        val buildNumber = sanitize(android.os.Build.DISPLAY, Constants.DEFAULT_BUILDNUMBER)
        val androidVersion = sanitize(
            android.os.Build.VERSION.RELEASE, Constants.DEFAULT_ANDROID_VERSION
        )
        val sdkInt = android.os.Build.VERSION.SDK_INT.toString()
        return RealDevice(
            manufacturer = manufacturer,
            brand = brand,
            model = model,
            buildNumber = buildNumber,
            androidVersion = androidVersion,
            sdkInt = sdkInt,
            deviceCode = buildDeviceCode(manufacturer, brand, model, buildNumber),
        )
    }

    /** 字段里不能出现分隔符 `;`，空值回落到默认值 */
    private fun sanitize(value: String?, fallback: String): String =
        value?.trim()?.replace(";", "")?.takeIf { it.isNotEmpty() } ?: fallback

    /**
     * 用给定的机型字段 + szlmId 重建设备串。
     *
     * - **首字段 szlmId** 写入 [szlmId]，默认取 [PrefManager.SZLMID]
     *   （未配置时是本机随机生成的一份，不是任何具体设备的真实 ID）；
     * - **厂商/品牌/型号/版本号** 写入给定机型，服务端按这四个字段认机型，
     *   帖子/回复下方的 `device_title`（「来自 xxx」）就是它的映射结果；
     * - **MAC、尾部 64hex** 沿用 [base] 里的值：实测（`_rev/diff_headers.py`）
     *   连这些结构性字段一起新造的整串会被风控要求验证码（`err_request_captcha_v2`），
     *   只换 szlmId + 机型则 `main/indexV8`、`user/profile`、`search`、
     *   `feed/replyList` 均正常。
     */
    fun buildDeviceCode(
        manufacturer: String,
        brand: String,
        model: String,
        buildNumber: String,
        base: String = Constants.DEFAULT_DEVICE_CODE,
        szlmId: String = PrefManager.SZLMID,
    ): String {
        val parts = DeviceCode.decode(base).split(";").toMutableList()
        if (parts.size < 8) return base
        parts[0] = szlmId
        parts[4] = " $manufacturer"
        parts[5] = " $brand"
        parts[6] = " $model"
        parts[7] = " $buildNumber"
        return DeviceCode.encode(parts.joinToString(";"))
    }

    /**
     * 用当前 [PrefManager] 里的机型字段 + szlmId 重建设备串。
     *
     * 只随「机型字段」；szlmId 由 [PrefManager.SZLMID] 注入，MAC/尾字段沿用骨架里的值。
     * 这里不再顺带拼 Android UA —— 请求头 UA 已统一由 [IosHeaders.userAgent] 生成。
     */
    fun getDeviceCode(): String = buildDeviceCode(
        PrefManager.MANUFACTURER,
        PrefManager.BRAND,
        PrefManager.MODEL,
        PrefManager.BUILDNUMBER
    )

    fun getLastingInstallTime(context: Context): String {
        val sp = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
        return sp.getString("INSTALL_TIME", null).let {
            it ?: System.currentTimeMillis().toString().apply {
                sp.edit().putString("INSTALL_TIME", this).apply()
            }
        }
    }

}