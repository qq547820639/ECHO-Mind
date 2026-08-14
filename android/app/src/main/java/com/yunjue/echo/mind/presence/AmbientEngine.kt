package com.yunjue.echo.mind.presence

import com.yunjue.echo.mind.localportrait.LocalBaselineSnapshot
import com.yunjue.echo.mind.localportrait.LocalDayAggregate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ERA 2 — Ambient Engine（机器内部状态引擎，纯 Kotlin 无 Android 依赖）。
 *
 * 产品契约（Master Prompt PART 6 / PERSONAL_INTELLIGENCE_CONTRACT）：
 * - Ambient State 描述**节律与活动**，不是情绪；禁止评价性命名；
 * - 输出中性状态向量，视觉引擎自己映射视觉参数（算法与视觉解耦）；
 * - 数据不足必须 UNKNOWN，不硬判；
 * - 用户可见的永远不是这些词（它们只驱动视觉）。
 */

/** 机器内部 Ambient 状态（对用户不显示这些词；仅驱动视觉参数）。 */
enum class AmbientState {
    /** 近期交互、移动相对平缓。 */
    QUIET,

    /** 近期活动明显增加。 */
    ACTIVE,

    /** 事件切换密集。 */
    DENSE,

    /** 整体行为节奏放缓。 */
    SLOW,

    /** 今天的活跃时段明显晚于个人基线。 */
    LATE,

    /** 正在从一种行为节律转换到另一种状态（结构形成期或活跃起点大幅漂移）。 */
    TRANSITION,

    /** 数据不足。 */
    UNKNOWN,
}

/** 中性状态向量（0..1，全部无单位；视觉引擎唯一消费端）。 */
data class AmbientVector(
    val activation: Float,
    val regularity: Float,
    val density: Float,
    val deviation: Float,
    val confidence: Float,
)

/** Ambient 计算结果：状态标签 + 向量 + 覆盖度。 */
data class AmbientResult(
    val state: AmbientState,
    val vector: AmbientVector,
    val coverage: Float,
)

internal object AmbientEngine {

    /** UNKNOWN 门槛：当日覆盖度低于此值不判断。 */
    const val MIN_COVERAGE_FOR_STATE = 0.1f

    /** 覆盖率低于此值时置信度按 0 处理。 */
    const val MIN_COVERAGE_FOR_CONFIDENCE = 0.05f

    /** 活跃起点 z 超过此阈值 → LATE。 */
    const val LATE_START_Z = 1.5

    /** 行为指标 z 超过此阈值 → ACTIVE/DENSE/SLOW 的强信号。 */
    const val STRONG_Z = 1.0

    /** 活跃起点大幅漂移（分钟）→ TRANSITION。 */
    const val START_SHIFT_MINUTES = 90

    /**
     * 计算 Ambient 状态与向量。
     *
     * @param today 当日聚合（可为 null = 无数据）
     * @param baseline 个人基线快照（可为 null = 尚未成型）
     */
    fun compute(today: LocalDayAggregate?, baseline: LocalBaselineSnapshot?): AmbientResult {
        val coverage = today?.coverageScore?.toFloat() ?: 0f
        if (today == null || coverage < MIN_COVERAGE_FOR_STATE) {
            return AmbientResult(
                state = AmbientState.UNKNOWN,
                vector = AmbientVector(
                    activation = 0f,
                    regularity = 0f,
                    density = 0f,
                    deviation = 0f,
                    confidence = 0f,
                ),
                coverage = coverage,
            )
        }

        val activation = normalizeActivation(today.movementIndex)
        val regularity = today.activeHourSpread?.toFloat()?.coerceIn(0f, 1f) ?: 0f
        val density = normalizeDensity(today)

        // 基线偏差：各行为指标 |z| 最大值（无基线时 deviation=0，置信度按覆盖度折减）
        val movementZ = zOf(today.movementIndex, baseline, "movement_index")
        val screenZ = zOf(today.screenOnMinutes, baseline, "screen_on_minutes")
        val switchZ = zOf(today.appSwitchCount.toDouble(), baseline, "app_switch_count")
        val startZ = zOf(today.activeStartMinute?.toDouble(), baseline, "active_start_minute", circular = true)
        val deviation = listOf(movementZ, screenZ, switchZ).map { abs(it).toFloat() / 2f }.max()
            .coerceIn(0f, 1f)

        val baselineDays = baseline?.validDays ?: 0
        val confidence = (
            coverage * min(baselineDays / 7f, 1f)
            ).coerceIn(0f, 1f)

        val state = classify(
            today = today,
            baselineDays = baselineDays,
            movementZ = movementZ,
            screenZ = screenZ,
            switchZ = switchZ,
            startZ = startZ,
            activation = activation,
            density = density,
        )

        return AmbientResult(
            state = state,
            vector = AmbientVector(
                activation = activation,
                regularity = regularity,
                density = density,
                deviation = deviation,
                confidence = confidence,
            ),
            coverage = coverage,
        )
    }

    /** 活跃量归一化：movement_index（加速度幅度标准差，通常 0..2+）→ 0..1。 */
    private fun normalizeActivation(movementIndex: Double?): Float {
        val v = movementIndex ?: return 0f
        return (v / (v + 0.5)).toFloat().coerceIn(0f, 1f)
    }

    /** 事件密度归一化：每有效窗口 (appSwitch+notification) 均值 / 8 → 0..1。 */
    private fun normalizeDensity(today: LocalDayAggregate): Float {
        val windows = today.validWindowCount
        if (windows <= 0) return 0f
        val perWindow = (today.appSwitchCount + today.notificationCount).toDouble() / windows
        return (perWindow / 8.0).toFloat().coerceIn(0f, 1f)
    }

    /** robust z：|today - median| / (mad + eps)；无基线或统计缺失返回 0.0。
     *  [circular] 指标（active_start/end_minute）使用圆周差（23:55 与 00:05 正确接近）。 */
    private fun zOf(
        todayValue: Double?,
        baseline: LocalBaselineSnapshot?,
        metric: String,
        circular: Boolean = false,
    ): Double {
        val v = todayValue ?: return 0.0
        val stats = baseline?.metrics?.get(metric) ?: return 0.0
        val median = stats.median ?: return 0.0
        val mad = stats.mad ?: return 0.0
        if (mad <= 1e-9) return 0.0
        val diff = if (circular) circularDiff(v, median) else v - median
        return diff / mad
    }

    /** 圆周差：结果在 [-720, 720)（分钟），跨午夜正确。 */
    internal fun circularDiff(valueMinutes: Double, referenceMinutes: Double): Double {
        val diff = ((valueMinutes - referenceMinutes + 720.0) % 1440.0 + 1440.0) % 1440.0 - 720.0
        return if (diff <= -720.0) diff + 1440.0 else diff
    }

    /** 状态判定（确定性、可解释；顺序即优先级）。 */
    private fun classify(
        today: LocalDayAggregate,
        baselineDays: Int,
        movementZ: Double,
        screenZ: Double,
        switchZ: Double,
        startZ: Double,
        activation: Float,
        density: Float,
    ): AmbientState {
        // 活跃起点明显晚于基线 → LATE（最强、最可解释的信号优先）
        if (startZ >= LATE_START_Z) return AmbientState.LATE

        // 基线成型期（3-6 天）：结构正在形成 → TRANSITION
        if (baselineDays in 3..6) return AmbientState.TRANSITION

        // 明显放缓：移动与屏幕同时显著低于基线
        if (movementZ <= -STRONG_Z && screenZ <= -0.5) return AmbientState.SLOW

        // 明显活跃
        if (movementZ >= STRONG_Z || activation >= 0.66f) return AmbientState.ACTIVE

        // 事件切换密集
        if (switchZ >= STRONG_Z || density >= 0.66f) return AmbientState.DENSE

        // 平缓
        if (activation <= 0.33f && density <= 0.33f) return AmbientState.QUIET

        // 常规活跃度但无强信号 → 按主导成分给状态（避免默认 UNKNOWN 掩盖可用信息）
        return if (activation >= density) AmbientState.ACTIVE else AmbientState.DENSE
    }
}
