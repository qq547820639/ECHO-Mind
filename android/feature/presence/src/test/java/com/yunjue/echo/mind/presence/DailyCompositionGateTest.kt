package com.yunjue.echo.mind.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

/**
 * ERA 21 §18 — Daily Composition 日级稳定：
 * 同一天多次 refresh（分钟级）不得改变日构图；跨日才重算。
 * Moment 层不受影响（继续分钟级呼吸）。
 */
class DailyCompositionGateTest {

    private val d1 = LocalDate.parse("2026-01-05")
    private val d2 = d1.plusDays(1)

    @Test
    fun sameDayAlwaysReturnsFrozenComposition() {
        val gate = DailyCompositionGate()
        val first = gate.compositionFor(d1) { EchoDailyComposition(flowSpeed = 0.3f) }
        // 同一天后续 refresh：即使 compute 结果不同，也必须返回固化值
        val later = gate.compositionFor(d1) { EchoDailyComposition(flowSpeed = 0.9f) }
        assertSame("同日必须返回同一实例（构图不漂移）", first, later)
        assertEquals(0.3f, later.flowSpeed)
    }

    @Test
    fun nextDayRecomputes() {
        val gate = DailyCompositionGate()
        val day1 = gate.compositionFor(d1) { EchoDailyComposition(flowSpeed = 0.3f) }
        val day2 = gate.compositionFor(d2) { EchoDailyComposition(flowSpeed = 0.9f) }
        assertEquals(0.3f, day1.flowSpeed)
        assertEquals("跨日必须重算", 0.9f, day2.flowSpeed)
    }

    @Test
    fun gateHoldsLatestDayOnly() {
        val gate = DailyCompositionGate()
        gate.compositionFor(d1) { EchoDailyComposition(flowSpeed = 0.3f) }
        gate.compositionFor(d2) { EchoDailyComposition(flowSpeed = 0.9f) }
        // 单日缓存语义：日期变化（含回拨）即重算；生产侧 refresh 恒传「今天」
        val rolledBack = gate.compositionFor(d1) { EchoDailyComposition(flowSpeed = 0.1f) }
        assertEquals(0.1f, rolledBack.flowSpeed)
    }
}
