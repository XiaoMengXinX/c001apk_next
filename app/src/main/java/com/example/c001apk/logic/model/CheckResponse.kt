package com.example.c001apk.logic.model

import com.google.gson.annotations.SerializedName

data class CheckResponse(
    val status: Int?,
    val message: String?,
    val messageStatus: String?,
    val data: Data?
) {

    data class Data(
        val id: String?,
        val status: Int,
        @SerializedName("message_status") val messageStatus: Int?,
        val uid: String,
        val username: String,
        val token: String,
        // iOS 接口的 checkLoginInfo 不返回 refreshToken（字段缺失），Gson 会写 null；
        // 业务请求侧本来就有 `refreshToken.ifEmpty { token }` 兜底，这里保持可空即可
        val refreshToken: String?,
        val userAvatar: String,
        val notifyCount: NotifyCount,
    )

    data class NotifyCount(
        val notification: Int,
        @SerializedName("contacts_follow") val contactsFollow: Int,
        val message: Int,
        val atme: Int,
        val atcommentme: Int,
        val commentme: Int,
        val feedlike: Int,
        val badge: Int,
        val dateline: String,
        /** 登录响应与 checkCount 一样，未读总数在 v18 字段上 */
        @SerializedName("badge_v18") val badgeV18: Int? = null,
        @SerializedName("notification_v18") val notificationV18: Int? = null
    ) {
        val unreadBadge: Int get() = badgeV18 ?: badge
        val unreadNotification: Int get() = notificationV18 ?: notification
    }

}

