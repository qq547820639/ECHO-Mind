package com.yunjue.echo.mind.journey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 16 §86 — Year View：季节聚合 / 转变检测 / 上下文时期合并 / 身份演化 / 空输入。
 */
class JourneyYearViewTest {

    @Test
    fun seasonOfBucketsByMonth() {
        assertEquals(SEASON_SPRING, seasonOf("2026-03-01"))
        assertEquals(SEASON_SPRING, seasonOf("2026-05-31"))
        assertEquals(SEASON_SUMMER, seasonOf("2026-06-15"))
        assertEquals(SEASON_AUTUMN, seasonOf("2026-09-01"))
        assertEquals(SEASON_WINTER, seasonOf("2026-12-25"))
        assertEquals(SEASON_WINTER, seasonOf("2026-01-15"))
        assertNull(seasonOf("garbage"))
        assertNull(seasonOf(""))
    }

    @Test
    fun contextPeriodsMergeAdjacentSameKindDates() {
        val periods = buildContextPeriods(
            mapOf(
                "2026-08-01" to "travel",
                "2026-08-02" to "travel",
                "2026-08-04" to "travel",
                "2026-08-10" to "exam",
            )
        )
        assertEquals(2, periods.size)
        assertEquals("2026-08-01", periods[0].startDate)
        assertEquals("2026-08-04", periods[0].endDate)
        assertEquals("travel", periods[0].kind)
        assertEquals("2026-08-10", periods[1].startDate)
        assertEquals("exam", periods[1].kind)
    }

    @Test
    fun contextPeriodsIgnoreUnparseableDates() {
        val periods = buildContextPeriods(mapOf("garbage" to "travel"))
        assertTrue(periods.isEmpty())
    }

    @Test
    fun majorShiftsDetectedBetweenDifferentAggregates() {
        val stable = (1..30).map { i -> journeyDay("2026-01-${i.toString().padStart(2, '0')}") }
        val changed = (1..30).map { i ->
            journeyDay(
                "2026-02-${i.toString().padStart(2, '0')}",
                params = visualParams { flowSpeed = 0.95f; coherence = 0.95f; turbulence = 0.95f; structureComplexity = 0.95f },
            )
        }
        val shifts = detectMajorShifts(stable + changed, chunkDays = 30)
        assertEquals(1, shifts.size)
        assertEquals("2026-02-01", shifts.first().date)
        assertTrue(shifts.first().distance >= RIVER_TRANSITION_DISTANCE)
        assertTrue(shifts.first().changedAspects.isNotEmpty())
    }

    @Test
    fun noShiftsWhenStable() {
        val days = (1..60).map { i ->
            val month = if (i <= 30) "01" else "02"
            val day = ((i - 1) % 30 + 1).toString().padStart(2, '0')
            journeyDay("2026-$month-$day")
        }
        assertTrue(detectMajorShifts(days, chunkDays = 30).isEmpty())
    }

    @Test
    fun yearViewAssemblesSeasonsShiftsPeriodsAndIdentity() {
        val days = listOf(
            journeyDay("2026-03-15"),
            journeyDay("2026-06-15", params = visualParams { flowSpeed = 0.9f; coherence = 0.9f; turbulence = 0.9f; structureComplexity = 0.9f }),
            journeyDay("2026-06-20", params = visualParams { flowSpeed = 0.9f; coherence = 0.9f; turbulence = 0.9f; structureComplexity = 0.9f }),
            journeyDay("2026-09-15"),
            journeyDay("2026-12-15"),
        )
        val canonical = listOf(
            canonicalDay("2026-03-15", seed = 42L),
            canonicalDay("2026-06-15", seed = 42L),
        )
        val view = buildYearView(
            days = days,
            canonicalDays = canonical,
            contextExceptions = mapOf("2026-09-15" to "exam"),
        )
        assertEquals(listOf(SEASON_SPRING, SEASON_SUMMER, SEASON_AUTUMN, SEASON_WINTER), view.seasons.map { it.season })
        val summer = view.seasons.first { it.season == SEASON_SUMMER }
        assertEquals(2, summer.dayCount)
        assertNotNull(summer.visualParams)
        assertNotNull(summer.identitySnapshot)
        // 上下文时期进入对应季节
        val autumn = view.seasons.first { it.season == SEASON_AUTUMN }
        assertEquals(listOf("exam"), autumn.contextPeriods.map { it.kind })
        // 身份演化：每月最近一次快照
        assertEquals(2, view.identityEvolution.size)
        assertEquals("2026-03-15", view.identityEvolution.first().date)
        assertEquals(42L, view.identityEvolution.first().identity.seed)
    }

    @Test
    fun emptyInputsProduceEmptyYearView() {
        val view = buildYearView(emptyList(), emptyList(), emptyMap())
        assertTrue(view.seasons.isEmpty())
        assertTrue(view.majorShifts.isEmpty())
        assertTrue(view.contextPeriods.isEmpty())
        assertTrue(view.identityEvolution.isEmpty())
    }
}
