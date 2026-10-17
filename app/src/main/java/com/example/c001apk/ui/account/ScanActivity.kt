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
 * ## ⚠️ 第 4 步现在是**占位**（`submitLogin`）
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
    private fun onScanned(text: String) {
        val uri = runCatching { android.net.Uri.parse(text) }.getOrNull()
        val requestId = uri?.getQueryParameter("r")
        val appTicket = uri?.getQueryParameter("t")
        if (requestId.isNullOrEmpty() || appTicket.isNullOrEmpty()) {
            Toast.makeText(this, R.string.scanInvalid, Toast.LENGTH_LONG).show()
            handled = false
            return
        }

        // ★ 设备信息 + IP 现在拿不到真实值
        //   —— `scan` 接口会回「设备名 + IP」，服务端部署后填进来 ✓
        //   现在先显示占位，并明确标注「服务端未部署」，不假装能用 ✗
        val device = getString(R.string.scanConfirmTitle)
        val ip = "服务端未部署"

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scanConfirmTitle)
            .setMessage(getString(R.string.scanConfirmMessage, device, ip))
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                handled = false
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                submitLogin(requestId, appTicket)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 上报登录（第 ⑤ 步）。
     *
     * ⚠️ **占位实现**：服务端 `scan` / `confirm` 还没部署 ⇒ 只提示，不假装成功 ✗
     * 部署后要做：
     *   1. 用当前凭据换 refreshToken（`GStorage`/`PrefManager` 里已有 ✓）
     *   2. 随机 32 字节 AES 密钥；用二维码里的**桌面端公钥**做 RSA(PKCS#1 v1.5) 加密
     *   3. 用 AES-CBC(PKCS7) 加密 refreshToken（IV 拼在密文前 16 字节）
     *   4. POST `confirm`：appTicket + encKey + encToken + duid(明文) + device(明文)
     */
    private fun submitLogin(requestId: String, appTicket: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scanLogin)
            .setMessage("服务端尚未部署，无法完成登录。\n（requestId=$requestId）")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                handled = false
            }
            .show()
    }

    override fun onDestroy() {
        executor.shutdown()
        reader.reset()
        super.onDestroy()
    }

    private companion object {
        const val REQ_CAMERA = 1001
    }
}
