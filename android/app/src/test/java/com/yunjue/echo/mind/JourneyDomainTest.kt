package com.yunjue.echo.mind

import com.yunjue.echo.mind.journey.buildJourneyDays
import com.yunjue.echo.mind.journey.buildJourneyPeriods
import com.yunjue.echo.mind.journey.journeyAggregateOfDays
import com.yunjue.echo.mind.journey.journeyRepresentativeDay
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.1 §25/§26：Journey Domain 回归（JourneyDay 装配 + 视觉聚合 deterministic）。
 */
class JourneyDomainTest {

    private fun portrait(date: String, movement: String = "SIMILAR") = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = 10,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = movement, metric = "movement_index", z = 0.5),
            "SCREEN_AMOUNT" to PortraitDimensionDto(value = "SIMILAR", metric = "screen_on_minutes", z = 0.5),
            "RHYTHM" to PortraitDimensionDto(value = "SIMILAR", metric = "active_start_minute", z = 0.5),
        ),
    )

    @Test
    fun buildsDaysWithPreassembledVisualParams() {
        val days = buildJourneyDays(listOf(portrait("2026-08-14"), portrait("2026-08-13", movement = "MORE")))
        assertEquals(2, days.size)
        assertEquals("2026-08-13", days[0].date) // 按旧到新排序
        assertNotNull(days[0].visualParams)
        assertEquals("接近", days[0].headline)
        assertEquals(3, days[0].dimensionValues.size) // MOVEMENT/SCREEN_AMOUNT/RHYTHM 三个非空维度
        assertTrue(buildJourneyDays(emptyList()).isEmpty())
    }

    @Test
    fun representativeDayPicksMostSimilar() {
        val days = buildJourneyDays(
            listOf(
                portrait("2026-08-11", movement = "MORE"),
                portrait("2026-08-12"), // 全 SIMILAR
                portrait("2026-08-13", movement = "LESS"),
            )
        )
        assertEquals("2026-08-12", journeyRepresentativeDay(days)?.date)
        assertNull(journeyRepresentativeDay(emptyList()))
    }

    @Test
    fun aggregateIsDeterministicAverage() {
        val days = buildJourneyDays((1..4).map { i -> portrait("2026-08-1$i", if (i % 2 == 0) "MORE" else "LESS") })
        val a1 = journeyAggregateOfDays(days)
        val a2 = journeyAggregateOfDays(days)
        assertEquals(a1, a2)
        assertNotNull(a1)
        assertNull(journeyAggregateOfDays(emptyList()))
    }

    @Test
    fun periodsChunkAndAggregate() {
        val days = buildJourneyDays((1..15).map { i -> portrait("2026-08-${i.toString().padStart(2, '0')}") })
        val periods = buildJourneyPeriods(days, 7)
        assertEquals(3, periods.size)
        assertEquals(7, periods[0].days.size)
        assertEquals(1, periods[2].days.size)
        assertNotNull(periods[0].aggregateParams)
        assertNotNull(periods[0].representative)
        assertTrue(buildJourneyPeriods(emptyList(), 7).isEmpty())
        assertTrue(buildJourneyPeriods(days, 0).isEmpty())
    }
}
