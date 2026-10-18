package com.example.c001apk.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.alibaba.sdk.android.oss.ClientConfiguration
import com.alibaba.sdk.android.oss.ClientException
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.ServiceException
import com.alibaba.sdk.android.oss.callback.OSSCompletedCallback
import com.alibaba.sdk.android.oss.common.OSSLog
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider
import com.alibaba.sdk.android.oss.model.ObjectMetadata
import com.alibaba.sdk.android.oss.model.PutObjectRequest
import com.alibaba.sdk.android.oss.model.PutObjectResult
import com.example.c001apk.logic.model.OSSUploadPrepareResponse
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun ossUpload(
    context: Context,
    responseData: OSSUploadPrepareResponse.Data,
    uriList: List<Uri>,
    typeList: List<String>,
    md5List: List<ByteArray?>,
    iOnSuccess: (Int) -> Unit,
    iOnFailure: () -> Unit,
    closeDialog: () -> Unit
) {
    runCatching {
        val accessKeyId = responseData.uploadPrepareInfo.accessKeyId
        val accessKeySecret = responseData.uploadPrepareInfo.accessKeySecret
        val securityToken = responseData.uploadPrepareInfo.securityToken

        val endPoint = responseData.uploadPrepareInfo.endPoint.replace("https://", "")
        val bucket = responseData.uploadPrepareInfo.bucket
        val callbackUrl = responseData.uploadPrepareInfo.callbackUrl

        val conf = ClientConfiguration()
        conf.connectionTimeout = 5 * 60 * 1000
        conf.socketTimeout = 5 * 60 * 1000
        conf.maxConcurrentRequest = 5
        conf.maxErrorRetry = 2
        OSSLog.enableLog()
        val credentialProvider = OSSStsTokenCredentialProvider(
            accessKeyId,
            accessKeySecret,
            securityToken
        )
        val oss = OSSClient(context, endPoint, credentialProvider, conf)

        uriList.forEachIndexed { index, uri ->
            val put = PutObjectRequest(
                bucket,
                responseData.fileInfo[index].uploadFileName,
                uri
            )
            val metadata = ObjectMetadata()
            metadata.contentType = typeList[index]
            metadata.contentMD5 = Base64.encodeToString(md5List[index], Base64.DEFAULT).trim()
            // versionCode 必须与请求头 X-App-Code 一致（服务端校验）；当前 API 走 iOS 实现
            val versionCode = IosHeaders.APP_CODE
            val ossCallbackHeader = buildString {
                append("{\"callbackBodyType\":\"application/json\",")
                append("\"callbackHost\":\"api.coolapk.com\",")
                append("\"callbackUrl\":\"https://api.coolapk.com/v6/callback/mobileOssUploadSuccessCallback?checkArticleCoverResolution=0&versionCode=")
                append(versionCode)
                append("\",")
                append("\"callbackBody\":\"{\\\"bucket\\\":\${bucket},\\\"object\\\":\${object},\\\"hasProcess\\\":\${x:var1}}\"}")
            }
            metadata.setHeader(
                "x-oss-callback",
                Base64.encodeToString(ossCallbackHeader.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            metadata.setHeader("x-oss-callback-var", "eyJ4OnZhcjEiOiJmYWxzZSJ9")
            put.metadata = metadata
            // 不要设 put.callbackParam：SDK 会用它自己生成一个 x-oss-callback 头盖掉上面手写的那个，
            // 而官方抓包（以及已验证跑通的流程）发的是上面那个带 {"bucket":..,"object":..} 的
            // callbackBody，服务端据此回 {data:{url:..}}。callbackUrl 只用来打日志。
            Log.i(
                "CoverDbg",
                "oss put start: endPoint=" + endPoint + " bucket=" + bucket +
                    " object=" + responseData.fileInfo[index].uploadFileName +
                    " contentType=" + typeList[index] +
                    " md5Null=" + (md5List[index] == null) +
                    " callbackUrl=" + callbackUrl
            )
            oss.asyncPutObject(put, OSSCallBack(
                iOnSuccess = {
                    iOnSuccess(index)
                },
                iOnFailure = {
                    iOnFailure()
                }
            ))
        }
    }.onFailure {
        closeDialog()
        withContext(Dispatchers.Main) {
            MaterialAlertDialogBuilder(context)
                .setTitle("图片上传失败")
                .setMessage(it.message)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton("Log") { _, _ ->
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Log")
                        .setMessage(it.stackTraceToString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                .show()
        }
    }
}

class OSSCallBack(
    private val iOnSuccess: () -> Unit,
    private val iOnFailure: () -> Unit,
) : OSSCompletedCallback<PutObjectRequest?, PutObjectResult?> {
    override fun onSuccess(
        request: PutObjectRequest?,
        result: PutObjectResult?
    ) {
        iOnSuccess()
    }

    override fun onFailure(
        request: PutObjectRequest?,
        clientException: ClientException?,
        serviceException: ServiceException?
    ) {
        iOnFailure()
        // Request exception
        if (clientException != null) {
            // Local exception, such as a network exception
            Log.i("OSSUpload", "uploadFailed: clientException: ${clientException.message}")
            clientException.printStackTrace()
        }
        if (serviceException != null) {
            // Service exception
            Log.i("OSSUpload", "OSSUpload: serviceException: ${serviceException.message}")
            Log.i("OSSUpload", "ErrorCode=" + serviceException.errorCode)
            Log.i("OSSUpload", "RequestId=" + serviceException.requestId)
            Log.i("OSSUpload", "HostId=" + serviceException.hostId)
            Log.i("OSSUpload", "RawMessage=" + serviceException.rawMessage)
        }
    }
}