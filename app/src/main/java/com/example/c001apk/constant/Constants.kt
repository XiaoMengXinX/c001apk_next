package com.example.c001apk.constant

import com.example.c001apk.util.PrefManager

object Constants {

    /**
     * `userAction.followAuthor` 的"还没拿到"哨兵值。
     *
     * 列表接口不下发这个字段（详情 `/v6/feed/detail` 才有），而详情页现在拿列表项先出首屏，
     * 所以首屏这段时间它是未知的。不能兜底成 0，否则已关注的作者会先错显成「关注」
     * （点下去还会发反请求）；用这个值让关注按钮位显示转圈，详情回来再补上真值。
     */
    const val FOLLOW_AUTHOR_UNKNOWN = -2

    // 这里原本还有一整套 Android 身份用的常量：REQUEST_WITH / LOCALE / APP_ID /
    // DARK_MODE / CHANNEL / MODE / APP_LABEL / VERSION_NAME / VERSION_CODE / API_VERSION。
    // 改用 iOS 身份后请求头由 IosHeaders.headers() 统一生成，这些值都无人读取，已删除。

    /**
     * 默认设备串的骨架：解出来是 `; ; ; ; 厂商; 品牌; 型号; 版本号; 尾部64hex`。
     *
     * ⚠️ **首字段 szlmId 是空的**，这是有意的：它由 [PrefManager.SZLMID] 在
     * `TokenDeviceUtils.buildDeviceCode()` 里注入，默认值是本机随机生成的一份，
     * 不再是某个具体设备的真实 ID（历史版本曾把真实 szlmId 写死在这里分发出去，
     * 导致大量真实账号被服务端算到「同一台设备」上，触发 `-415 账号过多`）。
     *
     * 这里只保留服务端认可的那几个结构性字段（机型 / 尾部 64hex），
     * 因为实测（`_rev/diff_headers.py` / `_rev/test_device_format.py`）：
     * 保留这些字段、只换 szlmId 时，`main/indexV8`、`user/profile`、`search`、
     * `feed/replyList` 都正常；而连机型/尾字段一起新造的整串会被风控
     * 要求人机验证（`err_request_captcha_v2`），详情页等接口直接加载失败。
     *
     * 注意：改用 iOS 身份后这套设备串已不参与任何请求头，只在 szlmId 相关设置里
     * 用于重建 [PrefManager.xAppDevice] 供展示。
     */
    const val DEFAULT_DEVICE_CODE =
        "lVDMjRWN2IzYjVTN3MmN2EDOiNWZjdjNhJTNkNTO3EWR4UzQzIkQ1EzN1YTMERTQFdjQ0cTMCF0QxYjNEJzM4AyOpEDMONEKzAzNuUjLw4iNx8FMxEjWKBFI7ATMxolSQByOzVHbQVmbPByOzVHbQVmbPByOgsDI7AyO"
    const val DEFAULT_MANUFACTURER = "OnePlus"
    const val DEFAULT_BRAND = "OnePlus"
    const val DEFAULT_MODEL = "PJZ110"
    const val DEFAULT_BUILDNUMBER = "PJZ110_16.0.5.703(CN01)"
    const val DEFAULT_ANDROID_VERSION = "16"

    const val LOADING_FAILED = "加载失败"
    const val LOADING_EMPTY = "什么也没有"
    const val LOADING_END = "没有更多了"
}