package com.example.c001apk.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GitHub Release 解析的单元测试。
 *
 * 夹具取自仓库真实的 `releases/latest` 响应（只留解析用得上的字段），
 * CI 改产物命名时这里会先失败。
 */
class UpdateCheckerTest {

    private val release = JSONObject(
        """
        {
          "tag_name": "V1.0.9",
          "body": "版本归档：V1.0.9（versionCode 10009）。",
          "assets": [
            {
              "name": "c001apk_next-V1.0.9-release.10009.apk",
              "browser_download_url": "https://github.com/XiaoMengXinX/c001apk_next/releases/download/V1.0.9/c001apk_next-V1.0.9-release.10009.apk"
            }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun versionCodeFromAssetName() {
        assertEquals(
            10009L,
            UpdateChecker.versionCodeOf("c001apk_next-V1.0.9-release.10009.apk")
        )
        assertEquals(
            10000L,
            UpdateChecker.versionCodeOf("c001apk_next-V1.0.0-release.10000.apk")
        )
    }

    /** debug 包不该被当成可下载的正式版 */
    @Test
    fun versionCodeIgnoresDebugAsset() {
        assertNull(UpdateChecker.versionCodeOf("c001apk_next-V1.0.9-debug.10009.apk"))
        assertNull(UpdateChecker.versionCodeOf("mappings.zip"))
    }

    @Test
    fun parseReleaseReadsTagCodeAndBody() {
        val info = UpdateChecker.parseRelease(release)!!
        assertEquals("V1.0.9", info.versionName)
        assertEquals(10009L, info.versionCode)
        assertEquals("版本归档：V1.0.9（versionCode 10009）。", info.changelog)
        assertEquals(1, info.lines.size)
        assertEquals(
            "https://github.com/XiaoMengXinX/c001apk_next/releases/download/V1.0.9/" +
                "c001apk_next-V1.0.9-release.10009.apk",
            info.lines[0].url
        )
    }

    /** 没有正式包的 Release（只有 debug / 源码）不产生更新提示 */
    @Test
    fun parseReleaseWithoutReleaseAsset() {
        val onlyDebug = JSONObject(
            """
            {
              "tag_name": "V1.0.9",
              "assets": [
                {"name": "c001apk_next-V1.0.9-debug.10009.apk",
                 "browser_download_url": "https://example.com/a.apk"}
              ]
            }
            """.trimIndent()
        )
        assertNull(UpdateChecker.parseRelease(onlyDebug))
    }

    @Test
    fun parseReleaseWithoutTagOrAssets() {
        assertNull(UpdateChecker.parseRelease(JSONObject("""{"assets": []}""")))
        assertNull(UpdateChecker.parseRelease(JSONObject("""{"tag_name": "V1.0.9"}""")))
        assertNull(UpdateChecker.parseRelease(JSONObject("{}")))
    }

    /**
     * JSON `null` 不能被读成字符串 "null"：
     * `optString` 会这么做，界面上就会出现一行字面的 null（实测踩过）。
     */
    @Test
    fun parseReleaseTreatsJsonNullAsEmpty() {
        val release = JSONObject(
            """
            {
              "tag_name": "V1.0.9",
              "body": null,
              "assets": [
                {"name": "c001apk_next-V1.0.9-release.10009.apk",
                 "browser_download_url": "https://example.com/a.apk"}
              ]
            }
            """.trimIndent()
        )
        val info = UpdateChecker.parseRelease(release)!!
        assertEquals("", info.changelog)
    }

    /** tag_name 为 null 时不该把 "null" 当成版本名 */
    @Test
    fun parseReleaseRejectsNullTag() {
        assertNull(
            UpdateChecker.parseRelease(
                JSONObject("""{"tag_name": null, "assets": []}""")
            )
        )
    }
}
