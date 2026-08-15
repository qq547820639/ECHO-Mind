package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.presence.PresenceMotionLevel

/**
 * Product Quality Era（ERA 19 §3）— 可重复测试用户规格。
 *
 * 每个 profile 完全由确定性参数定义：同一天（dayIndex）任意时刻重放，
 * 产出的 LocalDayAggregate / 画像 / Presence 状态逐字节一致。
 *
 * 参数语义（全部中性、无心理结论）：
 * - wake/end：典型活跃起止（分钟，0..1439）；
 * - drift：每 30 天的长期漂移（制造「半年后确实变了」的时间形状）；
 * - irregularity：0..1 日间噪声放大系数；
 * - specialWindows：出差 / 项目冲刺等特殊上下文窗口（DAY 结构真实变化）。
 */
data class QaSpecialWindow(
    /** 窗口起止（含端点，安装日起第 N 天）。 */
    val fromDay: Int,
    val toDay: Int,
    /** 上下文标签（中性描述：出差 / 冲刺 / …）。 */
    val label: String,
    val wakeShiftMinutes: Int = 0,
    val endShiftMinutes: Int = 0,
    val movementScale: Double = 1.0,
    val screenScale: Double = 1.0,
    val switchScale: Double = 1.0,
)

data class QaProfileSpec(
    val id: String,
    val displayName: String,
    val description: String,
    /** installation random seed（确定性 fixture：固定种子，非运行时随机）。 */
    val identitySeed: Long,
    val motionPreference: PresenceMotionLevel,
    val timezone: String,
    // 典型一天
    val wakeMinute: Int,
    val endMinute: Int,
    val wakeNoiseMinutes: Double,
    val endNoiseMinutes: Double,
    val movementIndex: Double,
    val movementNoise: Double,
    val screenOnMinutes: Double,
    val screenNoiseMinutes: Double,
    val appSwitchCount: Int,
    val switchNoise: Double,
    val notificationCount: Int,
    val coverage: Double,
    val coverageNoise: Double,
    // 周内结构
    val weekendWakeShiftMinutes: Int = 0,
    val weekendEndShiftMinutes: Int = 0,
    val weekendMovementScale: Double = 1.0,
    val weekendScreenScale: Double = 1.0,
    // 长期漂移（每 30 天）
    val wakeDriftPer30DaysMinutes: Double = 0.0,
    val endDriftPer30DaysMinutes: Double = 0.0,
    val screenDriftPer30DaysMinutes: Double = 0.0,
    val fragmentationDriftPer30Days: Double = 0.0,
    /** 0..1：日间噪声放大（不规律用户）。 */
    val irregularity: Double = 0.0,
    val specialWindows: List<QaSpecialWindow> = emptyList(),
) {
    /** 安装日当天窗口集合（快照报告用）。 */
    fun windowsFor(dayIndex: Int): List<QaSpecialWindow> =
        specialWindows.filter { dayIndex in it.fromDay..it.toDay }
}
