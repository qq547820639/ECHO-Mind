package com.yunjue.echo.mind.wearable

/**
 * XiaomiWearCapabilityMapper —— vendor 状态 → domain 状态的纯映射。
 *
 * 事实源：小米穿戴第三方APP能力开放接口文档 v1.4（官方文档，SDK 本体 BLOCKED）。
 * 本文件不 import 任何 SDK 类（禁止凭文档猜 class/interface 签名）——
 * 只把文档化的状态值语义映射为 wearable domain 枚举。
 *
 * 严格分类（ECHO_WRIST_CONTRACT §Wear Device State）：
 * - battery / charging / connection → DEVICE_HEALTH（禁止进入 Portrait/Memory/SelfModel）；
 * - wearing → OBSERVATION_QUALITY（NOT_WORN → 腕上观察 quality=unavailable，绝不等于"用户静止"）；
 * - sleep → OPTIONAL_NEUTRAL_CONTEXT（需官方能力 + 真机验证 + 用户显式授权）。
 */
object XiaomiWearCapabilityMapper {

    // 文档 §3.1 佩戴状态：1=佩戴中 2=未佩戴
    fun wearingFromVendor(value: Int?): String = when (value) {
        1 -> "WORN"
        2 -> "NOT_WORN"
        else -> "UNKNOWN" // 未验证 vendor 信号 → UNKNOWN，不猜
    }

    // 文档 §3.1 睡眠状态：1=睡眠中 2=清醒
    fun sleepFromVendor(value: Int?): String = when (value) {
        1 -> "SLEEPING"
        2 -> "AWAKE"
        else -> "UNKNOWN"
    }

    // 文档 §3.1 连接状态：1=连接 2=未连接
    fun connectionFromVendor(value: Int?): WearableConnectionState = when (value) {
        1 -> WearableConnectionState.CONNECTED
        2 -> WearableConnectionState.DISCONNECTED
        else -> WearableConnectionState.DISCONNECTED
    }

    // 文档 §3.1 充电状态：1=正在充电 2=非充电状态
    fun chargingFromVendor(value: Int?): Boolean? = when (value) {
        1 -> true
        2 -> false
        else -> null
    }

    // 文档 §3.1 电量：0~100；订阅不支持（只可查询）。
    fun batteryFromVendor(value: Int?): Int? =
        if (value != null && value in 0..100) value else null

    /**
     * 文档化的 vendor 状态值 → 腕上设备状态快照。
     * 未提供/未验证的值保持 UNKNOWN（null/UNKNOWN 语义一致：绝不猜）。
     */
    fun deviceStateFromVendor(
        wearing: Int? = null,
        sleep: Int? = null,
        battery: Int? = null,
        charging: Int? = null,
    ): com.yunjue.echo.mind.wearable.WearDeviceStateSnapshot =
        com.yunjue.echo.mind.wearable.WearDeviceStateSnapshot(
            wearing = wearingFromVendor(wearing),
            sleep = sleepFromVendor(sleep),
            batteryPercent = batteryFromVendor(battery),
            charging = chargingFromVendor(charging),
        )
}
