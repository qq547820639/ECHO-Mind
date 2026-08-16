package com.yunjue.echo.mind.wearable

/**
 * ERA 33 — Wearable capability 事实模型。
 *
 * 纪律：能力状态只能来自 `docs/wearable/XIAOMI_BAND10_CAPABILITY_MATRIX.md`
 * （官方文档核实结果）。绝不凭猜测声明 SUPPORTED。未真机验证的 vendor 信号保持
 * [WearCapabilityStatus.UNKNOWN]。
 *
 * # PHONE IS THE BRAIN. # WRIST IS THE BODY. # THERE IS ONLY ONE ECHO.
 */
enum class WearCapabilityStatus {
    /** 官方公开文档确认支持（仍需真机验证才算 Production Verified）。 */
    SUPPORTED,

    /** 官方文档确认不支持。 */
    UNSUPPORTED,

    /** 官方文档未确认 / 未真机验证。不猜。 */
    UNKNOWN,
}

/** 能力编号：与 Capability Matrix 一一对应，测试以 Matrix 为源。 */
enum class WearCapabilityId {
    /** Vela JS 快应用平台（RPK）本身。 */
    VELA_APP_PLATFORM,

    /** system.sensor.subscribeAccelerometer（仅前台）。 */
    ACCELEROMETER,

    /** system.sensor.subscribePressure（v1 仅 CAPABILITY/DIAGNOSTIC）。 */
    PRESSURE,

    /** system.sensor.subscribeCompass。 */
    COMPASS,

    /** system.vibrator.vibrate(mode: short|long)。 */
    VIBRATION_SHORT_LONG,

    /** system.vibrator.start/stop 任意 pattern。 */
    VIBRATION_PATTERN,

    /** system.interconnect：与配对手机 App 的消息通道。 */
    INTERCONNECT_MESSAGING,

    /** system.fetch：HTTP。 */
    NETWORK_FETCH,

    /** 通用 BLE 数据传输（用于第三方硬件）。 */
    GENERIC_BLE,

    /** 传感器后台持续订阅（24h sensor loop）。 */
    BACKGROUND_SENSOR_LOOP,

    /** Android 穿戴 SDK：连接状态。 */
    DEVICE_STATE_CONNECTION,

    /** Android 穿戴 SDK：电量（只查询）。 */
    DEVICE_STATE_BATTERY,

    /** Android 穿戴 SDK：充电状态。 */
    DEVICE_STATE_CHARGING,

    /** Android 穿戴 SDK：佩戴状态（OBSERVATION_QUALITY）。 */
    DEVICE_STATE_WEARING,

    /** Android 穿戴 SDK：睡眠状态（OPTIONAL_NEUTRAL_CONTEXT，需用户授权）。 */
    DEVICE_STATE_SLEEP,

    /** Android 穿戴 SDK：检测手环端应用是否安装。 */
    WEAR_APP_INSTALL_CHECK,

    /** Android 穿戴 SDK：launchWearApp。 */
    WEAR_APP_LAUNCH,
}

/**
 * 单个能力条目：状态 + 官方证据引用（Capability Matrix 章节号）。
 * [evidenceRef] 防止"我觉得应该支持"式结论漂移。
 */
data class WearableCapability(
    val id: WearCapabilityId,
    val status: WearCapabilityStatus,
    val evidenceRef: String,
) {
    companion object {
        /**
         * Band 10 能力集（官方文档事实，2026-08-16 核实）：
         * 来源 `docs/wearable/XIAOMI_BAND10_CAPABILITY_MATRIX.md`。
         */
        val BAND10_CAPABILITIES: List<WearableCapability> = listOf(
            WearableCapability(WearCapabilityId.VELA_APP_PLATFORM, WearCapabilityStatus.SUPPORTED, "matrix §1"),
            WearableCapability(WearCapabilityId.ACCELEROMETER, WearCapabilityStatus.SUPPORTED, "matrix §1.1"),
            WearableCapability(WearCapabilityId.PRESSURE, WearCapabilityStatus.SUPPORTED, "matrix §1.1"),
            WearableCapability(WearCapabilityId.COMPASS, WearCapabilityStatus.UNSUPPORTED, "matrix §1.1"),
            WearableCapability(WearCapabilityId.VIBRATION_SHORT_LONG, WearCapabilityStatus.SUPPORTED, "matrix §1.2"),
            WearableCapability(WearCapabilityId.VIBRATION_PATTERN, WearCapabilityStatus.UNSUPPORTED, "matrix §1.2"),
            WearableCapability(WearCapabilityId.INTERCONNECT_MESSAGING, WearCapabilityStatus.SUPPORTED, "matrix §1.3"),
            WearableCapability(WearCapabilityId.NETWORK_FETCH, WearCapabilityStatus.SUPPORTED, "matrix §1.3"),
            WearableCapability(WearCapabilityId.GENERIC_BLE, WearCapabilityStatus.UNKNOWN, "matrix §1.3"),
            WearableCapability(WearCapabilityId.BACKGROUND_SENSOR_LOOP, WearCapabilityStatus.UNSUPPORTED, "matrix §1.1"),
            WearableCapability(WearCapabilityId.DEVICE_STATE_CONNECTION, WearCapabilityStatus.SUPPORTED, "matrix §2"),
            WearableCapability(WearCapabilityId.DEVICE_STATE_BATTERY, WearCapabilityStatus.SUPPORTED, "matrix §2"),
            WearableCapability(WearCapabilityId.DEVICE_STATE_CHARGING, WearCapabilityStatus.SUPPORTED, "matrix §2"),
            WearableCapability(WearCapabilityId.DEVICE_STATE_WEARING, WearCapabilityStatus.SUPPORTED, "matrix §2"),
            WearableCapability(WearCapabilityId.DEVICE_STATE_SLEEP, WearCapabilityStatus.SUPPORTED, "matrix §2"),
            WearableCapability(WearCapabilityId.WEAR_APP_INSTALL_CHECK, WearCapabilityStatus.SUPPORTED, "matrix §2"),
            WearableCapability(WearCapabilityId.WEAR_APP_LAUNCH, WearCapabilityStatus.SUPPORTED, "matrix §2"),
        )

        /** 以能力编号查询；未知能力按 UNKNOWN 处理（测试纪律：绝不猜）。 */
        fun statusOf(
            capabilities: List<WearableCapability>,
            id: WearCapabilityId,
        ): WearCapabilityStatus = capabilities.firstOrNull { it.id == id }?.status ?: WearCapabilityStatus.UNKNOWN
    }
}
