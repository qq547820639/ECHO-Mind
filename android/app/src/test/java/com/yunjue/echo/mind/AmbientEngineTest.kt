package com.yunjue.echo.mind

import com.yunjue.echo.mind.localportrait.LocalBaselineSnapshot
import com.yunjue.echo.mind.localportrait.LocalDayAggregate
import com.yunjue.echo.mind.localportrait.LocalMetricStats
import com.yunjue.echo.mind.presence.AmbientEngine
import com.yunjue.echo.mind.presence.AmbientState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * ERA 2：AmbientEngine 回归（机器内部状态：中性、可解释、数据不足必 UNKNOWN）。
 */
class AmbientEngineTest {

    private fun aggregate(
        coverage: Double = 0.6,
        movement: Double? = 0.5,
        screenMinutes: Double = 60.0,
        appSwitch: Int = 40,
        notification: Int = 30,
        startMinute: Int? = 8 * 60 + 30,
        hourSpread: Double? = 0.5,
        validWindows: Int = 100,
    ) = LocalDayAggregate(
        localDate = LocalDate.of(2026, 8, 14),
        timezone = "Asia/Shanghai",
        coverageScore = coverage,
        validWindowCount = validWindows,
        expectedWindowCount = 288,
        movementIndex = movement,
        movementVariability = null,
        screenOnMinutes = screenMinutes,
        screenOpenCount = 50,
        lateScreenMinutes = 0.0,
        appSwitchCount = appSwitch,
        notificationCount = notification,
        activeStartMinute = startMinute,
        activeEndMinute = 22 * 60,
        activeHourSpread = hourSpread,
        sourcesPresent = listOf("accel", "gyro", "screen", "notification", "app_activity"),
        missingSources = emptyList(),
    )

    private fun baseline(
        validDays: Int,
        movementMedian: Double = 0.5,
        movementMad: Double = 0.2,
        screenMedian: Double = 60.0,
        screenMad: Double = 20.0,
        switchMedian: Double = 40.0,
        switchMad: Double = 15.0,
        startMedian: Double = 8.5 * 60,
        startMad: Double = 30.0,
    ) = LocalBaselineSnapshot(
        bucket = "weekday",
        windowStart = LocalDate.of(2026, 7, 17),
        windowEnd = LocalDate.of(2026, 8, 13),
        validDays = validDays,
        metrics = mapOf(
            "movement_index" to stats(movementMedian, movementMad),
            "screen_on_minutes" to stats(screenMedian, screenMad),
            "app_switch_count" to stats(switchMedian, switchMad),
            "active_start_minute" to stats(startMedian, startMad),
        ),
    )

    private fun stats(median: Double, mad: Double) = LocalMetricStats(
        median = median, mad = mad, p10 = null, p25 = null, p75 = null, p90 = null, validDays = 1
    )

    @Test
    fun noDataIsUnknown() {
        val result = AmbientEngine.compute(null, null)
        assertEquals(AmbientState.UNKNOWN, result.state)
        assertEquals(0f, result.vector.confidence)
    }

    @Test
    fun lowCoverageIsUnknownEvenWithAggregate() {
        val result = AmbientEngine.compute(aggregate(coverage = 0.05), baseline(7))
        assertEquals(AmbientState.UNKNOWN, result.state)
    }

    @Test
    fun lateStartIsLateState() {
        // baseline 8:30 ± 30min；今天 9:45 → 圆周 z = 75/30 = 2.5 ≥ 1.5
        val result = AmbientEngine.compute(
            aggregate(startMinute = 9 * 60 + 45),
            baseline(7),
        )
        assertEquals(AmbientState.LATE, result.state)
    }

    @Test
    fun slowDayIsSlowState() {
        // movement z = (0.1-0.5)/0.2 = -2；screen z = (30-60)/20 = -1.5
        val result = AmbientEngine.compute(
            aggregate(movement = 0.1, screenMinutes = 30.0),
            baseline(7),
        )
        assertEquals(AmbientState.SLOW, result.state)
    }

    @Test
    fun activeDayIsActiveState() {
        // movement z = (0.9-0.5)/0.2 = 2 ≥ 1
        val result = AmbientEngine.compute(
            aggregate(movement = 0.9),
            baseline(7),
        )
        assertEquals(AmbientState.ACTIVE, result.state)
    }

    @Test
    fun denseDayIsDenseState() {
        // switch z = (70-40)/15 = 2 ≥ 1
        val result = AmbientEngine.compute(
            aggregate(appSwitch = 70, notification = 60),
            baseline(7),
        )
        assertEquals(AmbientState.DENSE, result.state)
    }

    @Test
    fun quietDayIsQuietState() {
        val result = AmbientEngine.compute(
            aggregate(movement = 0.2, appSwitch = 10, notification = 5),
            baseline(7),
        )
        assertEquals(AmbientState.QUIET, result.state)
    }

    @Test
    fun emergingBaselineIsTransition() {
        val result = AmbientEngine.compute(aggregate(), baseline(5))
        assertEquals(AmbientState.TRANSITION, result.state)
    }

    @Test
    fun vectorValuesAreBounded() {
        val result = AmbientEngine.compute(aggregate(), baseline(14))
        val v = result.vector
        for (value in listOf(v.activation, v.regularity, v.density, v.deviation, v.confidence)) {
            assertTrue("向量值必须在 0..1：$value", value in 0f..1f)
        }
        assertTrue("成熟基线置信度应 > 0", v.confidence > 0f)
    }

    @Test
    fun circularDiffCrossesMidnightCorrectly() {
        // 23:55 vs 基线 00:05 → 差 -10 分钟，而不是 1430
        assertEquals(-10.0, AmbientEngine.circularDiff(1435.0, 5.0), 1e-9)
        assertEquals(10.0, AmbientEngine.circularDiff(5.0, 1435.0), 1e-9)
        assertEquals(0.0, AmbientEngine.circularDiff(720.0, 720.0), 1e-9)
    }
}
