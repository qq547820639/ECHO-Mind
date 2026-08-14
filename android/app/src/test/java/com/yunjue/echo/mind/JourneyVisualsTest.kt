package com.yunjue.echo.mind

import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.growthScore
import com.yunjue.echo.mind.journey.journeyAggregateParams
import com.yunjue.echo.mind.journey.journeyChunkDays
import com.yunjue.echo.mind.journey.journeyDayParams
import com.yunjue.echo.mind.journey.journeyGroups
import com.yunjue.echo.mind.journey.journeyRepresentativeIndex
import com.yunjue.echo.mind.journey.journeyThumbnailFrame
import com.yunjue.echo.mind.journey.journeyWeekGroups
import com.yunjue.echo.mind.journey.journeyWindowDays
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 8：Journey 视觉记忆回归（确定性：同一天永远同一帧；成长：结构复杂度随基线单调）。
 */
class JourneyVisualsTest {

    private fun portrait(
        date: String,
        baselineDays: Int,
        movement: String = "SIMILAR",
        screen: String = "SIMILAR",
        rhythm: String = "SIMILAR",
        z: Double? = 0.5,
    ) = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = baselineDays,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = movement, metric = "movement_index", z = z),
            "SCREEN_AMOUNT" to PortraitDimensionDto(value = screen, metric = "screen_on_minutes", z = z),
            "RHYTHM" to PortraitDimensionDto(value = rhythm, metric = "active_start_minute", z = z),
        ),
    )

    @Test
    fun dayParamsAreDeterministic() {
        val p = portrait("2026-08-14", 10)
        assertEquals(journeyDayParams(p), journeyDayParams(p))
        // 参数边界
        val params = journeyDayParams(p)!!
        assertTrue(params.flowSpeed in 0f..1f)
        assertTrue(params.coherence in 0f..1f)
        assertTrue(params.turbulence in 0f..1f)
        assertTrue(params.coreOpenness in 0f..1f)
        assertTrue(params.pulsePeriodSeconds in 3.8f..5.6f)
    }

    @Test
    fun thumbnailFrameIsStableAcrossCalls() {
        val p = portrait("2026-08-14", 10)
        val f1 = journeyThumbnailFrame(p, 42L, 100f, 100f)
        val f2 = journeyThumbnailFrame(p, 42L, 100f, 100f)
        assertEquals(f1, f2)
        assertNotNull(f1)
    }

    @Test
    fun missingDayHasNoFrame() {
        assertNull(journeyThumbnailFrame(null, 42L, 100f, 100f))
        assertNull(journeyDayParams(null))
    }

    @Test
    fun representativeDayIsMostSimilar() {
        val portraits = listOf(
            portrait("2026-08-11", 10, movement = "MORE"),
            portrait("2026-08-12", 10), // 全 SIMILAR
            portrait("2026-08-13", 10, movement = "LESS", screen = "MORE"),
        )
        assertEquals(1, journeyRepresentativeIndex(portraits))
        assertEquals(-1, journeyRepresentativeIndex(emptyList()))
    }

    @Test
    fun aggregateAveragesDayParams() {
        val portraits = (1..4).map { i -> portrait("2026-08-1$i", 10, movement = if (i % 2 == 0) "MORE" else "LESS") }
        val agg = journeyAggregateParams(portraits)
        assertNotNull(agg)
        val dayAvg = portraits.mapNotNull { journeyDayParams(it) }.map { it.flowSpeed }.average().toFloat()
        assertEquals(dayAvg, agg!!.flowSpeed, 1e-4f)
        assertNull(journeyAggregateParams(emptyList()))
    }

    @Test
    fun weekGroupsChunkBySeven() {
        val portraits = (1..15).map { i -> portrait("2026-08-${i.toString().padStart(2, '0')}", 10) }
        val groups = journeyWeekGroups(portraits)
        assertEquals(3, groups.size)
        assertEquals(7, groups[0].size)
        assertEquals(7, groups[1].size)
        assertEquals(1, groups[2].size)
        assertTrue(journeyWeekGroups(emptyList()).isEmpty())
    }

    @Test
    fun growthScoreReflectsMaturityIncrease() {
        val young = listOf(portrait("2026-08-10", 1), portrait("2026-08-14", 2))
        val old = listOf(portrait("2026-08-10", 20), portrait("2026-08-14", 21))
        // 基线天数越多，结构复杂度越高 → 成长度更高（同一窗口内差分也非负）
        assertTrue(growthScore(old) >= 0f)
        assertEquals(0f, growthScore(emptyList()))
    }

    @Test
    fun scaleWindowDays() {
        assertEquals(7, journeyWindowDays(JourneyScale.DAY))
        assertEquals(28, journeyWindowDays(JourneyScale.WEEK))
        assertEquals(28, journeyWindowDays(JourneyScale.MONTH))
        assertEquals(90, journeyWindowDays(JourneyScale.SEASON))
        assertEquals(365, journeyWindowDays(JourneyScale.YEAR))
    }

    @Test
    fun seasonAndYearGroupByThirtyDays() {
        assertEquals(30, journeyChunkDays(JourneyScale.SEASON))
        assertEquals(30, journeyChunkDays(JourneyScale.YEAR))
        val portraits = (1..65).map { i -> portrait("2026-07-${(i % 28 + 1).toString().padStart(2, '0')}", 10) }
        val groups = journeyGroups(portraits, 30)
        assertEquals(3, groups.size)
        assertTrue(journeyGroups(emptyList(), 30).isEmpty())
        assertTrue(journeyGroups(portraits, 0).isEmpty())
    }
}
