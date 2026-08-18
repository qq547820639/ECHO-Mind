package com.yunjue.echo.mind

import com.yunjue.echo.mind.journey.JourneyOrganismVisuals
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.journeyChunkDays
import com.yunjue.echo.mind.journey.journeyDayParams
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
    fun thumbnailGenomeIsStableAcrossCalls() {
        // V3：production organism genome（存参数不存图；同一天同一 genome → 同一帧）
        val p = portrait("2026-08-14", 10)
        val g1 = JourneyOrganismVisuals.genomeFor(p, 42L)
        val g2 = JourneyOrganismVisuals.genomeFor(p, 42L)
        assertEquals(g1, g2)
        assertNotNull(g1)
    }

    @Test
    fun missingDayHasNoGenome() {
        assertNull(JourneyOrganismVisuals.genomeFor(null, 42L))
        assertNull(journeyDayParams(null))
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
    }
}
