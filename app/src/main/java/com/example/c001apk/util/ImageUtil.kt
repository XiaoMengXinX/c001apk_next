package com.example.c001apk.util

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.transition.Transition
import com.example.c001apk.R
import com.example.c001apk.util.ClipboardUtil.copyText
import com.example.c001apk.util.FileUtil.copyFile
import com.example.c001apk.util.FileUtil.createFileByDeleteOldFile
import com.example.c001apk.view.FileTarget
import com.example.c001apk.view.ninegridimageview.NineGridImageView
import com.example.c001apk.view.ninegridimageview.indicator.CircleIndexIndicator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import jp.wasabeef.glide.transformations.ColorFilterTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mikaelzero.mojito.Mojito
import net.mikaelzero.mojito.ext.mojito
import net.mikaelzero.mojito.impl.DefaultPercentProgress
import net.mikaelzero.mojito.impl.DefaultTargetFragmentCover
import net.mikaelzero.mojito.impl.SimpleMojitoViewCallback
import rikka.core.util.ResourceUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest


object ImageUtil {

    /** 私信气泡图片的显示上限（dp）。量到真实像素时由 [chatImageSize] 用，量不到时当取图的目标框用。 */
    private const val CHAT_MAX_WIDTH_DP = 220f
    private const val CHAT_MAX_HEIGHT_DP = 260f

    private var filename = ""
    private lateinit var imagesDir: File
    private lateinit var imageCheckDir: File

    /**
     * 取图时带的 UA。
     *
     * 必须与 API 请求头里的 UA 一致（都是 iOS 身份，见 [IosDeviceUtils.coolapkUserAgent]）：
     * 图片 CDN 前面的 EdgeOne 按 UA 放行，不带或带错会回 567，表现是图片全黑。
     * 每次现取而不是缓存常量，设备档案变更（重新生成机型）后立即跟上。
     */
    private fun imageHeaders(): LazyHeaders =
        LazyHeaders.Builder()
            .addHeader("User-Agent", IosDeviceUtils.coolapkUserAgent())
            .build()

    @SuppressLint("CheckResult")
    fun showIMG(view: ImageView, url: String?, isCover: Boolean = false) {
        if (!url.isNullOrEmpty()) {
            // 离线存档的图是本机文件（见 UrlUtil.LOCAL_IMAGE_SCHEME）：交给 Glide 的 File
            // 加载器。不能喂 GlideUrl —— 那一路是 OkHttp，只认 http(s)，见 file:// 直接抛
            val localPath = url.localImagePath
            val model: Any = if (localPath != null) File(localPath) else GlideUrl(
                url.http2https,
                imageHeaders()
            )
            Glide
                .with(view).apply {
                    if (localPath == null && url.endsWith(".gif"))
                        asGif()
                }
                .load(model).apply {
                    if (isCover) {
                        transform(ColorFilterTransformation(Color.parseColor("#8A000000")))
                    } else if (ResourceUtils.isNightMode(view.context.resources.configuration)
                        && PrefManager.isColorFilter
                    ) {
                        transform(
                            CenterCrop(),
                            ColorFilterTransformation(Color.parseColor("#2D000000"))
                        )
                    } else {
                        transform(CenterCrop())
                    }
                }
                .transition(withCrossFade())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .into(view)
        }
    }

    /**
     * 私信图片。跟 [showIMG] 只差一处：**不做 http2https**。
     *
     * 私信图片固定落在 `message-pic2.coolapk.com`，这个 CDN 不支持 https
     * （实测 TLS 握手直接失败 `sslv3 alert handshake failure`），改成 https 就加载不出来；
     * 明文访问本身没问题，`network_security_config` 里 `cleartextTrafficPermitted="true"`。
     *
     * 传入的必须是 `showImage` 302 出来的**带 auth_key 的完整地址**，裸地址会被 CDN 挡。
     */
    @SuppressLint("CheckResult")
    fun showChatIMG(view: ImageView, url: String?, width: Int = 0, height: Int = 0) {
        if (url.isNullOrEmpty()) return
        val newUrl = GlideUrl(
            url,
            imageHeaders()
        )
        val request = Glide.with(view).load(newUrl)
        if (width > 0 && height > 0) {
            // 气泡按图片真实比例算好了尺寸（见 [chatImageSize]），按同一个尺寸取图，
            // 免得 Glide 按 ImageView 的当前尺寸(可能还是占位的 72dp)降采样后又被放大糊掉
            request.override(width, height).transform(CenterCrop())
        } else {
            // 没量到尺寸（CDN 那边解不出宽高）时**不能**瞎给方形：只给一个上限框 +
            // FitCenter，位图会保持自身比例缩放到框内（FitCenter 的输出就是等比缩放后的
            // 尺寸，不是框的尺寸），布局里的 adjustViewBounds 再跟着这个位图定气泡大小。
            val density = view.resources.displayMetrics.density
            request.override(
                (CHAT_MAX_WIDTH_DP * density).toInt(),
                (CHAT_MAX_HEIGHT_DP * density).toInt()
            ).transform(FitCenter())
        }
        request
            .transition(withCrossFade())
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)
            .into(view)
    }

    /**
     * 量一下图片的原始像素宽高（只解 bounds，不整张解码，几十 KB 的头部就够）。
     * 先把文件拉进 Glide 的磁盘缓存再读，[showChatIMG] 接着用同一份缓存，不会重复下载。
     */
    suspend fun loadImageSize(context: Context, url: String): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            try {
                val file = Glide.with(context.applicationContext)
                    .asFile()
                    .load(
                        GlideUrl(
                            url,
                            imageHeaders()
                        )
                    )
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .submit()
                    .get()
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                if (options.outWidth > 0 && options.outHeight > 0)
                    options.outWidth to options.outHeight
                else null
            } catch (e: Exception) {
                null
            }
        }

    /**
     * 把一张图下到指定文件，返回文件绝对路径，失败返回 null。
     *
     * 走 Glide 的 `submit().get()` 而不是自己开 OkHttp：离线缓存要下的图，用户刚才在详情页
     * 多半已经看过，直接命中 Glide 磁盘缓存就不用再走一次网络（[DiskCacheStrategy.ALL] 保证
     * 源文件也被缓存）。`get()` 会阻塞当前线程，必须切到 IO。
     */
    suspend fun downloadToFile(context: Context, url: String, target: File): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = Glide.with(context.applicationContext)
                    .asFile()
                    .load(
                        GlideUrl(
                            url.http2https,
                            imageHeaders()
                        )
                    )
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .submit()
                    .get()
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
                target.absolutePath
            }.getOrNull()
        }

    /**
     * 算私信气泡里的图片该显示多大（px）。
     *
     * 之前的写法是死宽 180dp + CenterCrop，横图竖图都挤成同一条，这里改成按比例：
     * 宽度铺到 220dp 封顶、高度 260dp 封顶，比 220dp 还窄的小图不放大（原始像素 1:1）。
     * 极端比例（细长条 / 全景）先夹到 0.6~2.2，免得气泡被拉成一根面条（配合 CenterCrop 裁切）。
     *
     * 单位说明：入参 imgWidth/imgHeight 是**原始像素**，返回的也是 px。
     * （之前这里写的是 `minOf(imgWidth / density, maxWidth)` —— 拿"像素除密度"得到的 dp
     * 去跟 px 的 maxWidth 比，量纲混了，结果所有图都偏小，一并修掉。）
     *
     * 宽高有一边 <= 0（没量出来）时返回 72dp 的方形占位，调用方**不要**拿它当结果用
     * ——[MessageDetailAdapter] 只在量到真实像素时才按这个尺寸定死气泡，量不到就走
     * layout 里的 adjustViewBounds。
     */
    fun chatImageSize(context: Context, imgWidth: Int, imgHeight: Int): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val maxWidth = CHAT_MAX_WIDTH_DP * density
        val maxHeight = CHAT_MAX_HEIGHT_DP * density
        val minWidth = 72f * density
        if (imgWidth <= 0 || imgHeight <= 0) return minWidth.toInt() to minWidth.toInt()
        val ratio = (imgWidth.toFloat() / imgHeight.toFloat()).coerceIn(0.6f, 2.2f)
        // 只比像素：小图不放大，大图压到 220dp
        var width = minOf(imgWidth.toFloat(), maxWidth)
        var height = width / ratio
        if (height > maxHeight) {
            height = maxHeight
            width = height * ratio
        }
        if (width < minWidth) {
            width = minWidth
            height = width / ratio
        }
        return width.toInt() to height.toInt()
    }

    /**
     * 私信图片点开全屏。跟 [startBigImgViewSimple] 的区别是**不做 http2https**：
     * message-pic2 这个 CDN 不支持 https（见 [showChatIMG]），换了协议就加载不出来。
     *
     * 两个已知取舍：
     * 1. 没挂长按存图（走 [showSaveImgDialog] → `downloadPicture` 会把地址升成 https，必失败）；
     * 2. Mojito 用的是它自带那套 Glide 加载器（`GlideImageLoader`），**不带酷安 UA**，
     *    这里赌 CDN 只认 auth_key（签名地址）。要是全屏加载不出来，就得给 Mojito 的图片
     *    客户端补上 UA（见 MyApplication 里的 `Mojito.initialize`）。
     */
    fun startChatBigImgViewSimple(imageView: ImageView, url: String) {
        imageView.mojito(url) {
            progressLoader {
                DefaultPercentProgress()
            }
        }
    }

    private suspend fun saveImageToGallery(ctx: Context, url: String): Boolean =
        withContext(Dispatchers.IO) {
            var success = false

            if (!imagesDir.exists())
                imagesDir.mkdirs()

            try {
                val file = Glide.with(ctx)
                    .asFile()
                    // url.imageUrl：本机图原样（file://），网络图才做 http2https
                    .load(url.imageUrl)
                    .submit()
                    .get()
                val image = File(imagesDir, filename)
                file.copyTo(image, overwrite = true)

                // 将图片插入图库
//                MediaStore.Images.Media.insertImage(
//                    ctx.contentResolver,
//                    image.absolutePath,
//                    image.name,
//                    image.name
//                )

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.TITLE, image.name)
                    put(MediaStore.Images.Media.DESCRIPTION, image.name)
                    // 在 Android 10 及更高版本，需要使用 MediaStore.Images.Media.RELATIVE_PATH
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
                    }
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") // 根据实际情况设置 MIME 类型
                }
                // 将图像插入 MediaStore，并获取插入后的 URI
                ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

                success = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
            success
        }

    private suspend fun saveImage(context: Context, url: String, isEnd: Boolean) {
        val index = url.lastIndexOf('/')
        filename = url.substring(index + 1)
        imagesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            context.getString(R.string.app_name)
        )
        imageCheckDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "${context.getString(R.string.app_name)}/$filename"
        )
        if (imageCheckDir.exists()) {
            if (isEnd)
                withContext(Dispatchers.Main) {
                    context.makeToast("文件已存在")
                }
        } else {
            downloadPicture(context, url.imageUrl, filename, isEnd)
        }
    }

    private fun showSaveImgDialog(context: Context, url: String, urlList: List<String>?) {
        MaterialAlertDialogBuilder(context).apply {
            val items = arrayOf("保存图片", "保存全部图片", "图片分享", "复制图片地址")
            setItems(items) { _: DialogInterface?, position: Int ->
                when (position) {
                    0 -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            saveImage(context, url, true)
                        }
                    }

                    1 -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            if (urlList.isNullOrEmpty()) {
                                saveImage(context, url, true)
                            } else {
                                var isEnd = false
                                urlList.forEach {
                                    if (urlList.indexOf(it) == urlList.size - 1)
                                        isEnd = true
                                    saveImage(context, it, isEnd)
                                }
                            }
                        }
                    }

                    2 -> {
                        CoroutineScope(Dispatchers.IO).launch {
                            val index = url.lastIndexOf('/')
                            filename = url.substring(index + 1)
                            imagesDir = File(context.externalCacheDir, "imageShare")
                            imageCheckDir = File(context.externalCacheDir, "imageShare/$filename")
                            if (imageCheckDir.exists()) {
                                withContext(Dispatchers.Main) {
                                    shareImage(
                                        context, File(
                                            context.externalCacheDir,
                                            "imageShare/$filename",
                                        ), null
                                    )
                                }
                            } else if (saveImageToGallery(context, url)) {
                                withContext(Dispatchers.Main) {
                                    shareImage(
                                        context, File(
                                            context.externalCacheDir,
                                            "imageShare/$filename",
                                        ), null
                                    )
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    context.makeToast("分享失败")
                                }
                            }
                        }
                    }

                    3 -> {
                        copyText(context, url)
                    }
                }
            }
            show()
        }
    }

    fun startBigImgView(
        nineGridView: NineGridImageView,
        imageView: ImageView,
        urlList: List<String>,
        position: Int
    ) {
        val thumbList: MutableList<String> = ArrayList()
        val originList: MutableList<String> = ArrayList()
        urlList.forEach {
            // 本机图（离线存档）交给 imageUrl：原样 file:// 并把尺寸提示 / `.s.jpg` 摘掉；
            // 网络图维持原样（原图摘掉缩略后缀，缩略图直接给带后缀的那条）
            originList.add(it.removeSuffix(".s.jpg").imageUrl)
            thumbList.add(if (it.isLocalImage) it.imageUrl else it.http2https)
        }
        Mojito.start(imageView.context) {
            urls(thumbList, originList)
            position(position)
            progressLoader {
                DefaultPercentProgress()
            }
            if (urlList.size != 1)
                setIndicator(CircleIndexIndicator())
            views(nineGridView.getImageViews().toTypedArray())
            when (PrefManager.imageQuality) {
                "auto" ->
                    if (NetWorkUtil.isWifiConnected())
                        autoLoadTarget(true)
                    else
                        autoLoadTarget(false)

                "origin" -> autoLoadTarget(true)

                "thumbnail" -> autoLoadTarget(false)
            }
            fragmentCoverLoader {
                DefaultTargetFragmentCover()
            }
            setOnMojitoListener(object : SimpleMojitoViewCallback() {
                override fun onStartAnim(position: Int) {
                    nineGridView.getImageViewAt(position)?.apply {
                        postDelayed({
                            this.isVisible = false
                        }, 200)
                    }
                }

                override fun onMojitoViewFinish(pagePosition: Int) {
                    nineGridView.getImageViews().forEach {
                        it.isVisible = true
                    }
                }

                override fun onViewPageSelected(position: Int) {
                    nineGridView.getImageViews().forEachIndexed { index, imageView ->
                        imageView.isVisible = position != index
                    }
                }

                override fun onLongClick(
                    fragmentActivity: FragmentActivity?,
                    view: View,
                    x: Float,
                    y: Float,
                    position: Int
                ) {
                    if (fragmentActivity != null) {
                        showSaveImgDialog(fragmentActivity, originList[position], originList)
                    } else {
                        Log.i("Mojito", "fragmentActivity is null, skip save image")
                    }
                }
            })
        }

    }

    fun startBigImgViewSimple(
        context: Context,
        imgList: List<String>
    ) {
        val thumbList = ArrayList<String>()
        val originList = ArrayList<String>()
        imgList.forEach {
            thumbList.add(it.thumbImageUrl)
            originList.add(it.imageUrl)
        }
        Mojito.start(context) {
            urls(thumbList, originList)
            when (PrefManager.imageQuality) {
                "auto" ->
                    if (NetWorkUtil.isWifiConnected())
                        autoLoadTarget(true)
                    else
                        autoLoadTarget(false)

                "origin" -> autoLoadTarget(true)

                "thumbnail" -> autoLoadTarget(false)
            }
            fragmentCoverLoader {
                DefaultTargetFragmentCover()
            }
            progressLoader {
                DefaultPercentProgress()
            }
            if (imgList.size > 1) {
                setIndicator(CircleIndexIndicator())
            }
            setOnMojitoListener(object : SimpleMojitoViewCallback() {
                override fun onLongClick(
                    fragmentActivity: FragmentActivity?,
                    view: View,
                    x: Float,
                    y: Float,
                    position: Int
                ) {
                    if (fragmentActivity != null) {
                        showSaveImgDialog(fragmentActivity, originList[position], originList)
                    } else {
                        Log.i("Mojito", "fragmentActivity is null, skip save image")
                    }
                }
            })
        }
    }

    fun startBigImgViewSimple(
        imageView: ImageView,
        url: String
    ) {
        imageView.mojito(url.imageUrl) {
            progressLoader {
                DefaultPercentProgress()
            }
            setOnMojitoListener(object : SimpleMojitoViewCallback() {
                override fun onLongClick(
                    fragmentActivity: FragmentActivity?,
                    view: View,
                    x: Float,
                    y: Float,
                    position: Int
                ) {
                    if (fragmentActivity != null) {
                        showSaveImgDialog(fragmentActivity, url.imageUrl, null)
                    } else {
                        Log.i("Mojito", "fragmentActivity is null, skip save image")
                    }
                }
            })
        }
    }

    fun startBigImgViewSimple(
        context: Context,
        url: String
    ) {
        startBigImgViewSimple(context, listOf(url.imageUrl))
    }


    // 将File 转化为 content://URI
    private fun getFileProvider(context: Context, file: File): Uri {
        val authority = context.packageName + ".fileprovider"
        return FileProvider.getUriForFile(
            context, authority,
            file
        )
    }

    private fun shareImage(context: Context, file: File, title: String?) {
        val contentUri = getFileProvider(context, file)
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_STREAM, contentUri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, title))
    }

    private fun downloadPicture(context: Context, url: String?, fileName: String, isEnd: Boolean) {
        // 本机图（离线存档）不用再下载：直接 load 一个 File，Glide 会把源文件原样交回来
        val localPath = url?.localImagePath
        val model: Any = if (localPath != null) File(localPath) else GlideUrl(
            url?.imageUrl,
            imageHeaders()
        )
        Glide.with(context).downloadOnly().load(model).into(object : FileTarget() {
            override fun onLoadFailed(errorDrawable: Drawable?) {
                super.onLoadFailed(errorDrawable)
                Toast.makeText(context, "download failed", Toast.LENGTH_SHORT).show()
            }

            override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                super.onResourceReady(resource, transition)
                save(context, resource, fileName, isEnd)
            }
        })
    }

    //from BigImageViewPager
    private fun save(context: Context, resource: File, fileName: String, isEnd: Boolean) {
        val mimeType = getImageTypeWithMime(resource.absolutePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 大于等于29版本的保存方法
            val resolver = context.contentResolver
            // 设置文件参数到ContentValues中
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            values.put(MediaStore.Images.Media.DESCRIPTION, fileName)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/$mimeType")
            values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/c001apk/"
            )
            val insertUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            var inputStream: BufferedInputStream? = null
            var os: OutputStream? = null
            try {
                inputStream = BufferedInputStream(FileInputStream(resource.absolutePath))
                os = insertUri?.let { resolver.openOutputStream(it) }
                os?.let {
                    val buffer = ByteArray(1024 * 4)
                    var len: Int
                    while (inputStream.read(buffer).also { len = it } != -1) {
                        os.write(buffer, 0, len)
                    }
                    os.flush()
                }
                if (isEnd)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                e.printStackTrace()
                if (isEnd)
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            } finally {
                try {
                    os?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                try {
                    inputStream?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        } else {
            // 低于29版本的保存方法
            val path = Environment.getExternalStorageDirectory()
                .toString() + "/c001apk/"
            createFileByDeleteOldFile(path + filename)
            val result = copyFile(resource, path, filename)
            if (result) {
                if (isEnd)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            } else {
                if (isEnd)
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getImageTypeWithMime(path: String): String {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, options)
        var type = options.outMimeType
        Log.d("getImageTypeWithMime", "getImageTypeWithMime: path = $path, type1 = $type")
        // ”image/png”、”image/jpeg”、”image/gif”
        type = if (TextUtils.isEmpty(type)) {
            ""
        } else {
            type.substring(6)
        }
        Log.d("getImageTypeWithMime", "getImageTypeWithMime: path = $path, type2 = $type")
        return type
    }

    fun getImageLp(url: String): Pair<Int, Int> {
        var imgWidth = 1
        var imgHeight = 1
        val at = url.lastIndexOf("@")
        val x = url.lastIndexOf("x")
        val dot = url.lastIndexOf(".")
        if (at != -1 && x != -1 && dot != -1) {
            imgWidth = url.substring(at + 1, x).toInt()
            imgHeight = url.substring(x + 1, dot).toInt()
        }
        return Pair(imgWidth, imgHeight)
    }

    fun ByteArray.toHex(): String {
        val hexString = StringBuilder()
        for (byte in this) {
            hexString.append(String.format("%02x", byte))
        }
        return hexString.toString()
    }

    fun getImageDimensionsAndMD5(
        contentResolver: ContentResolver,
        uri: Uri
    ): Pair<Triple<Int, Int, String>?, ByteArray?> {
        var dimensions: Triple<Int, Int, String>? = null
        var md5Hash: ByteArray? = null

        try {
            dimensions = contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                var width = options.outWidth
                var height = options.outHeight
                with(getRotation(contentResolver, uri)) {
                    if (this == 90 || this == 270) {
                        width = height.apply {
                            height = width
                        }
                    }
                }
                Triple(width, height, options.outMimeType)
            }
            md5Hash = contentResolver.openInputStream(uri)?.use { inputStream ->
                calculateMD5(inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(dimensions, md5Hash)
    }

    private fun getRotation(
        contentResolver: ContentResolver,
        uri: Uri
    ): Int {
        var rotation = 0
        try {
            val exif = contentResolver.openInputStream(uri)?.let { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return rotation
    }

    private fun calculateMD5(input: InputStream): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        var bytesRead = input.read(buffer)
        while (bytesRead > 0) {
            md.update(buffer, 0, bytesRead)
            bytesRead = input.read(buffer)
        }
        return md.digest()
    }

}