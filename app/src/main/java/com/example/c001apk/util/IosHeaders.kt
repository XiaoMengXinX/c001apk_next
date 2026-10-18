package com.example.c001apk.util

import java.security.MessageDigest
import java.util.Locale
import okio.ByteString.Companion.toByteString

/**
 * 酷安 iOS 客户端的请求头与签名实现。
 *
 * 与 `CoolapkApi-Go` 的 `token.Device` + `FuckCoolapkToken-iOS` 逐字节一致：
 * - [appDevice]：把 `设备ID; ; ; ; Apple; Apple; 机型; 硬件ID/销售型号` 做标准 Base64 后**整串反转**，
 *   再去掉反转后跑到开头的 `=` 填充；
 * - [appToken]：只依赖设备 ID 与秒级时间戳，不需要 versionCode / szlmId，因此不会随 Android
 *   versionCode 或设备标识变化而失效。
 *
 * 本文件刻意保持纯 JVM（不引用 Android Context / `Constants`），以便在本地单元测试中
 * 直接与 Go 参考实现比对 golden 值。
 */
data class IosDevice(
    /** 原始设备 ID（服务端登记的 48 字符串），不是编码后的 `X-App-Device` */
    val id: String,
    val model: String,
    val hardwareId: String,
    val salesModel: String,
    val systemVersion: String,
)

object IosHeaders {

    /** iOS 包名，同时用于 `X-App-Id` 与 token 材料 */
    const val BUNDLE_ID = "com.coolapk.app"

    const val APP_VERSION = "16.6.1"
    const val APP_CODE = "2610151"
    const val API_VERSION = "16"
    const val SDK_LOCALE = "zh-HK"
    const val ACCEPT_LANGUAGE = "zh-Hans-HK;q=1.0, zh-Hant-HK;q=0.9, en-HK;q=0.8"

    /** iOS token 固定前缀（逆向自官方客户端，跨版本稳定） */
    private const val TOKEN_PREFIX =
        "token://com.coolapk.app/1e7870f7312eb435aef6b59b211a9880?"

    /** 生成 `X-App-Device`：标准 Base64 → 反转 → 去掉开头的 `=` */
    fun appDevice(device: IosDevice): String {
        val identity = buildString {
            append(device.hardwareId)
            if (device.salesModel.isNotBlank()) append('/').append(device.salesModel)
        }
        val plain = listOf(
            device.id, "", "", "",
            "Apple", "Apple", device.model, identity
        ).joinToString("; ")
        return plain.toByteArray(Charsets.UTF_8)
            .toByteString()
            .base64()
            .reversed()
            .trimStart('=')
    }

    /** iOS 客户端 User-Agent（与参考实现一致，`iPhone OS 18_7` 为固定片段） */
    fun userAgent(device: IosDevice): String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 " +
            "(#Build; Apple; ${device.hardwareId}; ${device.model}; iOS_${device.systemVersion}) " +
            "+iCoolMarket/$APP_VERSION-$APP_CODE"

    /**
     * 生成 iOS `X-App-Token`。
     *
     * 末尾 `0x` 后是时间戳的低 32 位十六进制（与 Go `uint32(timestamp)` 一致）；
     * [timestampSeconds] 用秒级 Unix 时间，不要传毫秒。
     */
    fun appToken(device: IosDevice, timestampSeconds: Long): String {
        val timeMd5 = md5Hex(timestampSeconds.toString())
        val material = TOKEN_PREFIX + timeMd5 + "$" + device.id + "&" + BUNDLE_ID
        val digest = md5Hex(material.toByteArray(Charsets.UTF_8).toByteString().base64())
        return digest + device.id + "0x" + Integer.toHexString(timestampSeconds.toInt())
    }

    /** 一次生成全套 iOS 请求头；同一 [device] 快照保证 device / UA / token 三者自洽 */
    fun headers(device: IosDevice, timestampSeconds: Long): Map<String, String> =
        linkedMapOf(
            "Accept" to "*/*",
            "Accept-Language" to ACCEPT_LANGUAGE,
            "User-Agent" to userAgent(device),
            "X-Requested-With" to "XMLHttpRequest",
            "X-Sdk-Int" to device.systemVersion,
            "X-Sdk-Locale" to SDK_LOCALE,
            "X-App-Id" to BUNDLE_ID,
            "X-App-Version" to APP_VERSION,
            "X-App-Code" to APP_CODE,
            "X-Api-Version" to API_VERSION,
            "X-App-Device" to appDevice(device),
            "X-App-Token" to appToken(device, timestampSeconds),
        )

    private fun md5Hex(value: String): String = md5Hex(value.toByteArray(Charsets.UTF_8))

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") {
            "%02x".format(Locale.US, it.toInt() and 0xFF)
        }
}
