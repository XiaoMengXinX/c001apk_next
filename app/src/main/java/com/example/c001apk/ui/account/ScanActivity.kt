package com.example.c001apk.ui.account

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.c001apk.R
import com.example.c001apk.databinding.ActivityScanBinding
import com.example.c001apk.ui.base.BaseActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import android.util.Base64
import com.example.c001apk.util.PrefManager
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger

/**
 * 扫码页（阶段 ④）。
 *
 * 入口：账号管理页的「扫码登录」格子。
 *
 * ## 做什么
 * 1. CameraX 后置摄像头预览（`PreviewView`）
 * 2. `ImageAnalysis` 逐帧把 Y 平面交给 ZXing 解码（**不需要** GMS / ML Kit）
 * 3. 解出二维码内容后：校验是不是本项目的登录 URL → 弹确认框
 *    「您正在尝试登录「设备名」，IP 地址位于 x.x.x.x。确认登录吗？」
 * 4. 用户确认 → 换 refreshToken / AES / 公钥加密 → 上报
 *
 * ## 第 4 步（`doConfirm`）已接真实接口 ✓
 * 服务端 `scan` / `confirm` 两个接口还没部署 ⇒ 明确提示，不假装能用 ✗
 *
 * ## 线程
 * CameraX 的 `analyze` 在单线程 executor 上跑；解码成功后 `runOnUiThread` 弹框。
 * 用 [handled] 标志防止连续帧重复弹框。
 */
class ScanActivity : BaseActivity<ActivityScanBinding>() {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
        )
    }
    private val executor = Executors.newSingleThreadExecutor()

    /** 已经处理过一帧就不再重复弹框 */
    @Volatile
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @Suppress("UnsafeOptInUsageError")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor, ::analyze)
                }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /** 逐帧解码。只取 Y 平面（灰度），这正是 ZXing 需要的。 */
    private fun analyze(image: ImageProxy) {
        if (handled) {
            image.close()
            return
        }
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val source = PlanarYUVLuminanceSource(
                data,
                image.width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            val text = result?.text
            if (!text.isNullOrBlank()) {
                handled = true
                runOnUiThread { onScanned(text) }
            }
        } catch (_: Throwable) {
            // 大部分帧解不出属于正常情况（没对准、太暗），忽略
        } finally {
            image.close()
        }
    }

    /**
     * 解出内容后的处理。
     *
     * 二维码里是客户端与服务端约定的 URL（`requestId` + `appTicket` + 公钥）。
     * 不是本项目格式的，直接提示「这不是有效的 c001apk 登录二维码」✓
     */
    /**
     * 解出二维码后：先 POST `scan` 拿「桌面端设备名 + IP」，再弹确认框。
     *
     * ★ 顺序很重要：协议第 ⑥ 步要求确认框显示的是**真实**设备名和 IP，
     *   不是占位 ✗ —— 所以必须先调 scan（服务端把 `REMOTE_ADDR` 记进会话 ✓）
     */
    private fun onScanned(text: String) {
        val uri = runCatching { android.net.Uri.parse(text) }.getOrNull()
        val requestId = uri?.getQueryParameter("r")
        val appTicket = uri?.getQueryParameter("t")
        // ★ 公钥**不再**从二维码里取 —— 那会让 QR 膨胀到 ~580 字符（手机难扫 ✗）
        //   现在从 `scan` 的返回里拿（服务端 register 时已经存了那一份 ✓）
        if (requestId.isNullOrEmpty() || appTicket.isNullOrEmpty()) {
            Toast.makeText(this, R.string.scanInvalid, Toast.LENGTH_LONG).show()
            handled = false
            return
        }

        // 后台线程做网络；UI 线程弹框
        executor.execute {
            val scanned = runCatching {
                httpPost("scan", mapOf(
                    "appTicket" to appTicket,
                    "device" to myDeviceName(),
                ))
            }
            val device = scanned.getOrNull()?.optString("device").orEmpty()
            val ip = scanned.getOrNull()?.optString("ip").orEmpty()
            val pubKeyB64 = scanned.getOrNull()?.optString("publicKey").orEmpty()
            runOnUiThread {
                if (!scanned.isSuccess || device.isEmpty() || pubKeyB64.isEmpty()) {
                    Toast.makeText(this, R.string.scanInvalid, Toast.LENGTH_LONG).show()
                    handled = false
                    return@runOnUiThread
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.scanConfirmTitle)
                    .setMessage(getString(R.string.scanConfirmMessage, device, ip))
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        handled = false
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        doConfirm(appTicket, pubKeyB64)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /** 本机机型（上报用，明文 ✓ 协议允许） */
    private fun myDeviceName(): String {
        val m = android.os.Build.MANUFACTURER ?: ""
        val d = android.os.Build.MODEL ?: ""
        return if (m.isEmpty()) d else "$m $d"
    }

    /**
     * 用户确认后：加密并上报 `confirm`。
     *
     * ## 加密（与桌面端 `qr_crypto.dart` 的约定严格一致）
     * - 随机 32 字节 **AES 密钥**
     * - 用二维码里的**桌面端公钥**做 RSA **PKCS#1 v1.5** 加密该 AES 密钥 → `encKey`
     * - 用 AES **CBC + PKCS5/7** 加密 `refreshToken`，**IV 拼在密文前 16 字节** → `encToken`
     * - `duid` 与 `device` 明文 ✓（协议允许）
     *
     * ## 硬约束
     * 只加密 `refreshToken`，服务端拿到的永远是密文 ✓
     */
    private fun doConfirm(appTicket: String, pubKeyB64: String) {
        val token = PrefManager.refreshToken.ifEmpty { PrefManager.token }
        if (token.isEmpty()) {
            Toast.makeText(this, "本机未登录，无法授权", Toast.LENGTH_LONG).show()
            handled = false
            return
        }
        Toast.makeText(this, "正在授权…", Toast.LENGTH_SHORT).show()

        executor.execute {
            val ok = runCatching {
                // ① 随机 AES 密钥（32 字节 = AES-256）
                val aesKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }

                // ② 把「refreshToken + uid + username」打包成 JSON，整体 AES 加密
                //
                //   ★ 用户要求 uid / username 也**加密传输** ✓
                //   ⇒ 服务端只能看到密文（它本来也不解析 encToken ✓）
                //   ⇒ 桌面端用同一个 AES 密钥解开，再 JSON.parse 取三个字段 ✓
                val payload = JSONObject().apply {
                    put("token", token)
                    put("uid", PrefManager.uid)
                    put("username", PrefManager.username)
                }.toString()
                val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
                aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
                val encTokenBytes = iv + aes.doFinal(payload.toByteArray(Charsets.UTF_8))

                // ③ 用桌面端公钥 RSA(PKCS#1 v1.5) 加密 AES 密钥
                //
                //   ★ `scan` 返回的 `publicKey` 就是**原始 JSON 字符串** ✓
                //     （桌面端 `jsonEncode({'m':..,'e':..})` 后直接上报，服务端原样存/回）
                //   ⚠️ 别再 base64 解码 ✗ —— 旧版协议里二维码的 `k` 才是 base64(JSON)，
                //      把 JSON 当 base64 解会直接抛 IllegalArgumentException ⇒「授权失败」
                val pubJson = JSONObject(pubKeyB64)
                val modulus = BigInteger(1, Base64.decode(pubJson.getString("m"), Base64.DEFAULT))
                val exponent = BigInteger(1, Base64.decode(pubJson.getString("e"), Base64.DEFAULT))
                val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
                val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                rsa.init(Cipher.ENCRYPT_MODE, pub)
                val encKeyBytes = rsa.doFinal(aesKey)

                // ④ 上报
                httpPost("confirm", mapOf(
                    "appTicket" to appTicket,
                    "encKey" to Base64.encodeToString(encKeyBytes, Base64.NO_WRAP),
                    "encToken" to Base64.encodeToString(encTokenBytes, Base64.NO_WRAP),
                    "duid" to PrefManager.SZLMID,
                    "device" to myDeviceName(),
                ))
                true
            }.getOrElse { e ->
                // ★ 加密类异常的 `message` 经常是 null ✗ ⇒ 必须带上**类名**才查得动
                //   （2026-10-18 就吃过一次「授权失败：null」的亏 ✗）
                val detail = buildString {
                    append(e.javaClass.simpleName)
                    e.message?.let { append(": ").append(it) }
                    var c = e.cause
                    var depth = 0
                    while (c != null && depth < 2) {
                        append(" ← ").append(c.javaClass.simpleName)
                        c.message?.let { append(": ").append(it) }
                        c = c.cause
                        depth++
                    }
                }
                android.util.Log.e("ScanActivity", "confirm 失败", e)
                runOnUiThread {
                    Toast.makeText(this, "授权失败：$detail", Toast.LENGTH_LONG).show()
                    handled = false
                }
                false
            }
            if (ok) {
                runOnUiThread {
                    Toast.makeText(this, "已授权，请在电脑上确认登录", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    /**
     * 极简 form POST（不引新依赖，用 `HttpURLConnection`）。
     *
     * 服务端读 `$_POST` 或 `php://input`，这里发 `x-www-form-urlencoded` ✓
     * 返回 `data` 字段（`{"data":{...}}`），失败抛异常。
     */
    private fun httpPost(action: String, params: Map<String, String>): JSONObject {
        val url = URL(QR_BASE + "?action=" + URLEncoder.encode(action, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        val body = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val root = JSONObject(text)
        if (root.has("data") && !root.isNull("data")) {
            return root.getJSONObject("data")
        }
        throw IllegalStateException(root.optString("message", "服务端返回异常"))
    }

    override fun onDestroy() {
        executor.shutdown()
        reader.reset()
        super.onDestroy()
    }

    private companion object {
        const val REQ_CAMERA = 1001

        /** 扫码接口基址（只写基址 + 动作名，不放服务端实现细节） */
        const val QR_BASE = "https://service.houlangs.cn/c001apk/qr/"
    }
}
