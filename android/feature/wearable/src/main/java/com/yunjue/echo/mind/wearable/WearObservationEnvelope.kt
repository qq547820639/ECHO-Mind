package com.yunjue.echo.mind.wearable

/**
 * Band → Phone 观察 envelope。
 *
 * 观察 ≠ Presence（ECHO_WRIST_CONTRACT §Observation Before Presence）：
 * 手环 Evidence 必须经 Observation Adapter → approved fusion → Presence。
 * 手环不保存长期 Memory，不直接修改 EchoPresenceState。
 *
 * 内容纪律：
 * - 加速度计只在应用前台订阅；本地 5–15s 窗口，只发 summary，不发 raw；
 * - 未佩戴（NOT_WORN）→ 所有腕上观察 quality = unavailable（不把"无运动"当"静止"）；
 * - 电量/充电属于 DEVICE_HEALTH，佩戴属于 OBSERVATION_QUALITY，
 *   睡眠属于 OPTIONAL_NEUTRAL_CONTEXT —— 都禁止进入 Portrait/Memory/SelfModel。
 */
data class WearMotionSummary(
    /** 去重力加速度 RMS（m/s²）；未知/不可用时 null（不是 0）。 */
    val motionEnergy: Float?,
    /** STATIONARY / LOW / WALKING / VIGOROUS / UNKNOWN。 */
    val movementClass: String,
    /** 窗口内采样覆盖率 0..1（低覆盖 → UNKNOWN，不猜）。 */
    val sampleCoverage: Float,
    /** GOOD / POOR / UNKNOWN。 */
    val quality: String,
    /** 窗口起止（epoch ms）。 */
    val windowStartMs: Long,
    val windowEndMs: Long,
)

/**
 * 手环上报的设备状态快照（vendor 能力经真机验证后才有真实值；未验证保持 UNKNOWN）。
 * 严格分类见 [WearDeviceStateClass]。
 */
data class WearDeviceStateSnapshot(
    /** WORN / NOT_WORN / UNKNOWN —— OBSERVATION_QUALITY 类。 */
    val wearing: String = WEAR_STATE_UNKNOWN,
    /** AWAKE / SLEEPING / UNKNOWN —— OPTIONAL_NEUTRAL_CONTEXT 类（需用户授权才可用）。 */
    val sleep: String = WEAR_STATE_UNKNOWN,
    /** 电量 0..100 —— DEVICE_HEALTH 类。null = UNKNOWN。 */
    val batteryPercent: Int? = null,
    /** 是否充电中 —— DEVICE_HEALTH 类。null = UNKNOWN。 */
    val charging: Boolean? = null,
) {
    companion object {
        const val WEAR_STATE_UNKNOWN: String = "UNKNOWN"
    }
}

/** 设备状态严格分类（禁止 device state 变成 personal truth）。 */
enum class WearDeviceStateClass {
    /** battery / charging / connection：只用于设备健康与诊断。 */
    DEVICE_HEALTH,

    /** wearing：影响观察质量，但绝不等于"用户静止"。 */
    OBSERVATION_QUALITY,

    /** sleep/awake：中立上下文（sleep vs stationary 区分、日界质量、休息上下文）。 */
    OPTIONAL_NEUTRAL_CONTEXT,
}

data class WearObservationEnvelope(
    override val schemaVersion: Int = WEAR_SCHEMA_CURRENT,
    override val messageId: String,
    override val generatedAt: Long,
    override val source: WearMessageSource = WearMessageSource.XIAOMI_BAND,
    /** 运动窗口 summary（前台加速度计聚合）。 */
    val motion: WearMotionSummary? = null,
    /** 设备状态快照（vendor 信号，UNKNOWN 保留）。 */
    val deviceState: WearDeviceStateSnapshot? = null,
) : WearEnvelope

/** 观察来源（source arbitration：wrist ≠ phone，二者语义不同，不简单相加）。 */
enum class WearObservationSource {
    PHONE,
    XIAOMI_BAND,
    ANSWATCH,
}
