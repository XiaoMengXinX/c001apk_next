package com.example.c001apk.util

import java.util.Locale
import java.util.UUID

/**
 * 酷安 API 使用的 iOS 设备身份。
 *
 * 设备 ID 分两种状态：
 * - **未登录**：随机 UUID。读接口可以正常使用，写接口（点赞/回复）会被风控拦截；
 * - **登录后**：向 CDID 服务（见 [CdidClient]）换一个服务端登记过的固定 ID，
 *   之后所有请求的 `X-App-Device` / `X-App-Token` 都用它。写接口只认这种 ID。
 *
 * 机型档案（iPhone 型号、硬件 ID、销售型号、iOS 版本）持久化，
 * 保证 `X-App-Device`、UA、`X-App-Token` 在多次启动之间自洽。
 * 用户可在「设置 → 参数」里修改机型字段或重新生成机型档案。
 */
object IosDeviceUtils {

    private val lock = Any()

    @Volatile
    private var cached: IosDevice? = null

    /** 当前设备身份；首次调用会创建并落盘 */
    fun current(): IosDevice {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            return loadOrCreate().also { cached = it }
        }
    }

    fun deviceId(): String = current().id

    /** 重新生成机型档案；设备 ID 不变（随机 ID 会让写操作被风控） */
    fun regenerate(): IosDevice = synchronized(lock) {
        createAndPersist(current().id).also { cached = it }
    }

    /**
     * 修改机型档案（设备 ID 不变）。
     *
     * 必填字段传空串时保留原值；`salesModel` 允许置空（为空时设备串里不写 `/销售型号`）。
     * 字段里的 `;` 会被去掉，避免破坏设备串的分段结构。
     */
    fun updateProfile(
        model: String = current().model,
        hardwareId: String = current().hardwareId,
        salesModel: String = current().salesModel,
        systemVersion: String = current().systemVersion,
    ): IosDevice = synchronized(lock) {
        val base = current()
        val updated = IosDevice(
            id = base.id,
            model = sanitize(model).ifBlank { base.model },
            hardwareId = sanitize(hardwareId).ifBlank { base.hardwareId },
            salesModel = sanitize(salesModel),
            systemVersion = sanitize(systemVersion).ifBlank { base.systemVersion },
        )
        persist(updated)
        cached = updated
        updated
    }

    /**
     * 登录成功后写入 CDID 服务下发的固定设备 ID。
     *
     * 只写 `ios_cdid` 与当前身份；之后所有 API 请求立即改用这个 ID。
     */
    fun setCdid(cdid: String): IosDevice = synchronized(lock) {
        PrefManager.iosCdid = cdid
        val updated = current().copy(id = cdid)
        persist(updated)
        cached = updated
        updated
    }

    /**
     * 手动重新生成设备 ID：清掉 CDID 与随机设备信息，换一个新 UUID。
     *
     * 登录状态下调用方应随后重新申请 CDID（见 [CdidClient.fetch]），
     * 否则新的随机 UUID 会让点赞/回复被风控拦截。
     */
    fun resetDeviceId(): IosDevice = synchronized(lock) {
        PrefManager.iosCdid = ""
        PrefManager.iosIdfv = ""
        PrefManager.iosInstallId = ""
        val updated = current().copy(id = newRandomUuid())
        persist(updated)
        cached = updated
        updated
    }

    /** 退出登录：清掉 CDID，回到随机 UUID（下次登录会重新换取） */
    fun onLogout(): IosDevice = synchronized(lock) {
        PrefManager.iosCdid = ""
        val updated = current().copy(id = newRandomUuid())
        persist(updated)
        cached = updated
        updated
    }

    /** 换取 CDID 时上报的随机 IDFV；首次生成后持久化，重试沿用同一份 */
    fun idfv(): String = synchronized(lock) {
        PrefManager.iosIdfv.ifBlank {
            newRandomUuid().also { PrefManager.iosIdfv = it }
        }
    }

    /** 换取 CDID 时上报的随机安装 ID */
    fun installId(): String = synchronized(lock) {
        PrefManager.iosInstallId.ifBlank {
            newRandomUuid().also { PrefManager.iosInstallId = it }
        }
    }

    fun appDevice(device: IosDevice = current()): String = IosHeaders.appDevice(device)

    fun userAgent(device: IosDevice = current()): String = IosHeaders.userAgent(device)

    fun appToken(
        timestampSeconds: Long = nowSeconds(),
        device: IosDevice = current(),
    ): String = IosHeaders.appToken(device, timestampSeconds)

    fun headers(
        timestampSeconds: Long = nowSeconds(),
        device: IosDevice = current(),
    ): Map<String, String> = IosHeaders.headers(device, timestampSeconds)

    fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    /**
     * 读取已落盘身份；任一组缺失/损坏则补一组随机档案（设备 ID 不变）。
     *
     * 登录状态下优先使用已换到的 `ios_cdid`；未登录时强制使用随机 UUID，
     * 避免把登录前的匿名请求和 CDID 绑定在一起。
     */
    private fun loadOrCreate(): IosDevice {
        val storedId = PrefManager.iosDeviceId
        val id = chooseId(storedId)
        val model = PrefManager.iosModel
        val hardwareId = PrefManager.iosHardwareId
        val systemVersion = PrefManager.iosSystemVersion
        if (model.isNotBlank() && hardwareId.isNotBlank() && systemVersion.isNotBlank()) {
            val device = IosDevice(
                id = id,
                model = model,
                hardwareId = hardwareId,
                salesModel = PrefManager.iosSalesModel,
                systemVersion = systemVersion,
            )
            // 切换身份（登录/退出/迁移）时立刻落盘
            if (id != storedId) persist(device)
            return device
        }
        return createAndPersist(id)
    }

    private fun chooseId(storedId: String): String {
        if (PrefManager.isLogin) {
            return PrefManager.iosCdid
                .ifBlank { storedId }
                .ifBlank { newRandomUuid() }
        }
        // 未登录：只认随机 UUID；旧版本留下的 CDID / 硬编码 ID 一律换掉
        return if (storedId.isRandomUuid()) storedId else newRandomUuid()
    }

    private fun createAndPersist(existingId: String): IosDevice {
        val profile = PROFILES.random()
        val device = IosDevice(
            id = existingId,
            model = profile.model,
            hardwareId = profile.hardwareId,
            salesModel = profile.salesModel,
            systemVersion = VERSIONS.random(),
        )
        persist(device)
        return device
    }

    private fun persist(device: IosDevice) {
        PrefManager.saveIosDevice(
            id = device.id,
            model = device.model,
            hardwareId = device.hardwareId,
            salesModel = device.salesModel,
            systemVersion = device.systemVersion,
        )
    }

    private fun newRandomUuid(): String = UUID.randomUUID().toString().uppercase(Locale.US)

    /** 本应用未登录时使用的 36 位 UUID（8-4-4-4-12） */
    private fun String.isRandomUuid(): Boolean =
        length == 36 && this.count { it == '-' } == 4

    private fun sanitize(value: String): String = value.trim().replace(";", "")

    private data class Profile(
        val model: String,
        val hardwareId: String,
        val salesModel: String,
    )

    /** 与参考实现 `token.NewMockDevice` 相同的 iPhone 机型/销售型号组合（中国大陆 CH/A） */
    private val PROFILES = listOf(
        Profile("iPhone 15", "iPhone15,4", "MTLD3CH/A"),
        Profile("iPhone 15", "iPhone15,4", "MTLG3CH/A"),
        Profile("iPhone 16", "iPhone17,3", "MYEV3CH/A"),
        Profile("iPhone 16", "iPhone17,3", "MYEW3CH/A"),
        Profile("iPhone 17", "iPhone18,3", "MG6W4CH/A"),
        Profile("iPhone 17", "iPhone18,3", "MG704CH/A"),
    )

    private val VERSIONS = listOf("26.7", "26.7.1", "27.0.1")
}
