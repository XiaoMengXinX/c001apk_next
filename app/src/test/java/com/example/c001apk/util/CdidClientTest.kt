package com.example.c001apk.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 与 `cdid_client.py`（数美 / IUNI SDK 逆向）逐字节比对的 golden 测试。
 *
 * 期望值由 Python 参考实现在固定时间戳下生成；任何一处改动导致与参考实现
 * 不一致时这里会立即失败。
 */
class CdidClientTest {

    private val timestamp = 1_700_000_000L

    @Test
    fun encodeFieldMatchesReference() {
        assertEquals("4o-p9i2Qfj", CdidClient.encodeField("iPhone18,3", timestamp))
        assertEquals("CDB", CdidClient.encodeField("ABC", timestamp))
        assertEquals("", CdidClient.encodeField("", timestamp))
    }

    @Test
    fun encodeUuidMatchesReference() {
        val (encoded, checksum) =
            CdidClient.encodeUuid("A1B2C3D4-E5F6-4789-8ABC-DEF012345678", timestamp)
        assertEquals(":8978655.473G261FG.F5EE.4DDC3BC92.B", encoded)
        assertEquals("d79c625", checksum)
    }

    @Test
    fun aesMatchesReference() {
        // openssl enc -aes-128-ecb -K 36453438413744320000000000000000 <<< "hello"
        assertEquals("d07a6c9df48edb486d4d97fed380e89b", CdidClient.aesEncrypt("hello".toByteArray()).hex())
    }

    @Test
    fun aesRoundTrip() {
        val plain = """{"err":"0","rid":"D2JWPUVVa+nT7zkKGnfpAYIytASAD1efdYZOmSL0/UTnI285"}"""
            .toByteArray()
        assertArrayEquals(plain, CdidClient.aesDecrypt(CdidClient.aesEncrypt(plain)))
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
