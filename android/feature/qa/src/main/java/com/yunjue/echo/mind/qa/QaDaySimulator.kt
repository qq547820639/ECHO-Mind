package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.localportrait.LocalDayAggregate
import com.yunjue.echo.mind.localportrait.isWeekendLocal
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Product Quality Era（ERA 19 §3）— 确定性单日观测模拟器。
 *
 * 给定 profile + dayIndex，产出该日 [LocalDayAggregate]（与真实采集聚合同构）。
 * 同输入恒同输出；输入直接进入生产管线（LocalBaselineCalculator / AmbientEngine），
 * 保证 fixture 检验的是真实产品逻辑而不是仿制品。
 */
object QaDaySimulator {

    /** 一天 5 分钟窗口数（与 LocalAggregateCalculator 的 288 一致）。 */
    const val EXPECTED_WINDOWS = 288

    /** 低于此覆盖视为「当天几乎没有可判数据」→ 活跃起点缺失（missing != irregular）。 */
    const val MIN_COVERAGE_FOR_START = 0.12

    /** 晚屏起点：22:00（与后端 late_screen_minutes 语义一致）。 */
    const val LATE_SCREEN_START_MINUTE = 1320

    fun day(profile: QaProfileSpec, dayIndex: Int, date: LocalDate): LocalDayAggregate {
        val rng = QaRng.forDay(profile.identitySeed, dayIndex)
        val windows = profile.windowsFor(dayIndex)
        val weekend = isWeekendLocal(date)

        // 长期漂移（每 30 天累计）
        val driftWake = profile.wakeDriftPer30DaysMinutes * (dayIndex / 30.0)
        val driftEnd = profile.endDriftPer30DaysMinutes * (dayIndex / 30.0)
        val driftScreen = profile.screenDriftPer30DaysMinutes * (dayIndex / 30.0)
        val driftFrag = profile.fragmentationDriftPer30Days * (dayIndex / 30.0)

        // 特殊窗口 + 周内结构
        val wakeShift = windows.sumOf { it.wakeShiftMinutes } +
            if (weekend) profile.weekendWakeShiftMinutes else 0
        val endShift = windows.sumOf { it.endShiftMinutes } +
            if (weekend) profile.weekendEndShiftMinutes else 0
        val movementScale = windows.fold(1.0) { acc, w -> acc * w.movementScale } *
            if (weekend) profile.weekendMovementScale else 1.0
        val screenScale = windows.fold(1.0) { acc, w -> acc * w.screenScale } *
            if (weekend) profile.weekendScreenScale else 1.0
        val switchScale = windows.fold(1.0) { acc, w -> acc * w.switchScale }

        // 不规律放大系数
        val noiseBoost = 1.0 + profile.irregularity * 2.0

        // 覆盖率
        val coverage = gaussian(rng, profile.coverage, profile.coverageNoise * noiseBoost).coerceIn(0.0, 1.0)

        // 活跃起点与时长（span 保证 4h..20h 的物理合理性）
        val wakeMean = wrapMinute(profile.wakeMinute + driftWake + wakeShift)
        val wake = wrapMinute(gaussian(rng, wakeMean, profile.wakeNoiseMinutes * noiseBoost))
        val baseSpan = spanOf(profile.wakeMinute, profile.endMinute)
        val span = gaussian(rng, baseSpan + endShift + driftEnd, profile.endNoiseMinutes * noiseBoost)
            .coerceIn(240.0, 1200.0)
        val end = wrapMinute(wake + span)

        // 行为指标
        val movement = gaussian(rng, profile.movementIndex * movementScale, profile.movementNoise * noiseBoost)
            .coerceIn(0.01, 4.0)
        val screen = gaussian(rng, profile.screenOnMinutes * screenScale + driftScreen, profile.screenNoiseMinutes * noiseBoost)
            .coerceIn(5.0, 900.0)
        val switches = gaussian(rng, profile.appSwitchCount * switchScale, profile.switchNoise * noiseBoost)
            .roundToInt().coerceIn(0, 2000)
        val notifications = gaussian(rng, profile.notificationCount.toDouble(), 20.0 * noiseBoost)
            .roundToInt().coerceIn(0, 500)

        // 活跃时长占比（碎片化方向）+ 长期碎片化漂移
        val spread = (span / 1440.0 * 0.85 + 0.15 + driftFrag +
            gaussian(rng, 0.0, 0.03 * noiseBoost)).coerceIn(0.10, 0.95)

        // 晚屏：22:00 之后（跨午夜正确累加）
        // 先 clamp base 到 [0, 420]，再加噪声，最后整体 clamp 防止越界
        val lateScreenBase = if (end >= LATE_SCREEN_START_MINUTE) {
            (end - LATE_SCREEN_START_MINUTE) * screenScale
        } else {
            (end + 120.0) * screenScale
        }.coerceIn(0.0, 420.0)
        val lateScreen = (lateScreenBase + gaussian(rng, 0.0, 10.0 * noiseBoost)).coerceIn(0.0, 420.0)

        val validWindows = (EXPECTED_WINDOWS * coverage).roundToInt().coerceIn(0, EXPECTED_WINDOWS)

        val sourcesPresent = buildList {
            if (coverage >= 0.08) add("motion")
            if (coverage >= 0.25) add("app_usage")
            if (coverage >= 0.25) add("screen")
            if (coverage >= 0.30) add("notification")
        }
        val allSources = listOf("motion", "app_usage", "screen", "notification")

        return LocalDayAggregate(
            localDate = date,
            timezone = profile.timezone,
            coverageScore = coverage,
            validWindowCount = validWindows,
            expectedWindowCount = EXPECTED_WINDOWS,
            movementIndex = movement,
            movementVariability = movement * 0.3 + gaussian(rng, 0.0, 0.05).coerceIn(0.0, 0.2),
            screenOnMinutes = screen,
            screenOpenCount = (screen / 20.0).roundToInt().coerceIn(0, 200),
            lateScreenMinutes = lateScreen.coerceAtLeast(0.0),
            appSwitchCount = switches,
            notificationCount = notifications,
            activeStartMinute = if (coverage >= MIN_COVERAGE_FOR_START) wake.roundToInt().mod(1440) else null,
            activeEndMinute = if (coverage >= MIN_COVERAGE_FOR_START) end.roundToInt().mod(1440) else null,
            activeHourSpread = spread,
            sourcesPresent = sourcesPresent,
            missingSources = allSources.filterNot { it in sourcesPresent },
        )
    }

    /** 分钟值折回 [0, 1440)。 */
    private fun wrapMinute(value: Double): Double {
        val v = value % 1440.0
        return if (v < 0) v + 1440.0 else v
    }

    /** 起止分钟（可能跨午夜）→ 清醒时长分钟数。 */
    private fun spanOf(wake: Int, end: Int): Double {
        val raw = end - wake
        return if (raw > 0) raw.toDouble() else (raw + 1440).toDouble()
    }

    private fun gaussian(rng: QaRng, mean: Double, sd: Double): Double = mean + rng.nextGaussian() * sd
}
