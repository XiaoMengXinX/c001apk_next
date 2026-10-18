package com.example.c001apk.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 与 `CoolapkApi-Go` 参考实现逐字节比对的 golden 测试。
 *
 * 期望值由 Go 代码生成（固定 UUID / 机型 / 时间戳 1700000000），任何一处改动
 * 导致与 iOS 实现不一致时这里会立即失败。
 */
class IosHeadersTest {

    private val device = IosDevice(
        id = "A1B2C3D4-E5F6-4789-8ABC-DEF012345678",
        model = "iPhone 15",
        hardwareId = "iPhone15,4",
        salesModel = "MTLD3CH/A",
        systemVersion = "26.7",
    )

    private val timestamp = 1_700_000_000L

    @Test
    fun appDeviceMatchesReference() {
        assertEquals(
            "E0LIN0MExEVN9CNsUTMl52boBVagsTNxASZu9GaQlGI7UGbwBXQgsTZsBHcBByOgsDI7AyO4cjN1QzMyEDMGVERtMkQBhTL5gzN00iNGVTRtQDRzMkMCFTQ",
            IosHeaders.appDevice(device)
        )
    }

    @Test
    fun userAgentMatchesReference() {
        assertEquals(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 " +
                "(#Build; Apple; iPhone15,4; iPhone 15; iOS_26.7) " +
                "+iCoolMarket/16.6.1-2610151",
            IosHeaders.userAgent(device)
        )
    }

    @Test
    fun appTokenMatchesReference() {
        assertEquals(
            "6ff6898e9469e3fe8ce07b390f0e523b" +
                "A1B2C3D4-E5F6-4789-8ABC-DEF012345678" +
                "0x6553f100",
            IosHeaders.appToken(device, timestamp)
        )
    }

    @Test
    fun headersMatchReference() {
        assertEquals(
            linkedMapOf(
                "Accept" to "*/*",
                "Accept-Language" to "zh-Hans-HK;q=1.0, zh-Hant-HK;q=0.9, en-HK;q=0.8",
                "User-Agent" to IosHeaders.userAgent(device),
                "X-Requested-With" to "XMLHttpRequest",
                "X-Sdk-Int" to "26.7",
                "X-Sdk-Locale" to "zh-HK",
                "X-App-Id" to "com.coolapk.app",
                "X-App-Version" to "16.6.1",
                "X-App-Code" to "2610151",
                "X-Api-Version" to "16",
                "X-App-Device" to IosHeaders.appDevice(device),
                "X-App-Token" to IosHeaders.appToken(device, timestamp),
            ),
            IosHeaders.headers(device, timestamp)
        )
    }
}
