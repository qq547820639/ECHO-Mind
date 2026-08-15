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
    fun seasonKeyOfSplitsSameLabelAcrossCalendarYears() {
        assertEquals("SUMMER-2025", seasonKeyOf("2025-08-01"))
        assertEquals("SUMMER-2026", seasonKeyOf("2026-06-15"))
        assertEquals("WINTER-2025", seasonKeyOf("2025-12-25"))
        assertEquals("WINTER-2025", seasonKeyOf("2026-01-15"))
        assertEquals("WINTER-2025", seasonKeyOf("2026-02-28"))
        assertEquals("WINTER-2026", seasonKeyOf("2026-12-01"))
        assertEquals("SPRING-2026", seasonKeyOf("2026-03-01"))
        assertEquals("AUTUMN-2026", seasonKeyOf("2026-11-30"))
        assertNull(seasonKeyOf("garbage"))
        assertNull(seasonKeyOf(""))
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
        assertEquals("2026-01-30", shifts.first().beforeDate)
        assertTrue(shifts.first().distance >= RIVER_TRANSITION_DISTANCE)
        assertTrue(shifts.first().changedAspects.isNotEmpty())
    }

    @Test
    fun shiftExplanationLinesBindNeutralExplanationWithRange() {
        val before = visualParams { flowSpeed = 0.35f }
        val after = visualParams { flowSpeed = 0.9f; coherence = 0.9f }
        val shift = JourneyMajorShift(
            date = "2026-02-01",
            beforeDate = "2026-01-31",
            before = before,
            after = after,
            distance = 0.9f,
            changedAspects = changedVisualAspects(before, after),
        )
        val lines = shiftExplanationLines(shift)
        assertEquals(1, lines.size)
        assertTrue(lines.first().startsWith("2026-01-31 → 2026-02-01"))
        assertTrue(lines.first().contains("流动速度上升"))
        // 用户可读解释不泄漏技术维度名
        assertTrue(lines.none { it.contains("flow") || it.contains("coherence") })
    }

    @Test
    fun shiftExplanationLinesFallBackToOverallWhenNoAspects() {
        val shift = JourneyMajorShift(
            date = "2026-02-01",
            beforeDate = null,
            before = null,
            after = null,
            distance = 0.9f,
            changedAspects = emptyList(),
        )
        assertEquals(listOf("2026-02-01：整体视觉风格转变"), shiftExplanationLines(shift))
    }

    @Test
    fun identityEvolutionLinesAnnotateConsistency() {
        val same = identityGenome(seed = 42L)
        val changed = identityGenome(seed = 42L).copy(motionPersonality = 0.8f)
        val lines = identityEvolutionLines(
            listOf(
                JourneyIdentityPoint(date = "2026-03-15", identity = same),
                JourneyIdentityPoint(date = "2026-04-15", identity = same),
                JourneyIdentityPoint(date = "2026-05-15", identity = changed),
            )
        )
        assertEquals(
            listOf(
                "2026-03-15",
                "2026-04-15（与上一记录一致）",
                "2026-05-15（较上一记录有细微调整）",
            ),
            lines,
        )
        assertTrue(identityEvolutionLines(emptyList()).isEmpty())
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

    @Test
    fun yearViewSplitsSeasonsAcrossCalendarYears() {
        // 滚动 365 天窗口跨日历年（2025-08 → 2026-06）：同标签不同年份必须分桶。
        val days = listOf(
            journeyDay("2025-08-15"),
            journeyDay("2025-09-15"),
            journeyDay("2025-12-15"),
            journeyDay("2026-01-15"),
            journeyDay("2026-02-15"),
            journeyDay("2026-03-15"),
            journeyDay("2026-06-15"),
        )
        val view = buildYearView(days = days, canonicalDays = emptyList(), contextExceptions = emptyMap())
        // 标签序列：SUMMER(2025) / AUTUMN(2025) / WINTER(2025-12..2026-02) / SPRING(2026) / SUMMER(2026)
        assertEquals(
            listOf(SEASON_SUMMER, SEASON_AUTUMN, SEASON_WINTER, SEASON_SPRING, SEASON_SUMMER),
            view.seasons.map { it.season },
        )
        val summers = view.seasons.filter { it.season == SEASON_SUMMER }
        assertEquals(2, summers.size)
        assertEquals("2025-08-15", summers[0].startDate)
        assertEquals("2026-06-15", summers[1].startDate)
        // 冬季桶横跨两个日历年（12 月 + 次年 1/2 月），逐日都在一个桶内
        val winter = view.seasons.single { it.season == SEASON_WINTER }
        assertEquals("2025-12-15", winter.startDate)
        assertEquals("2026-02-15", winter.endDate)
        assertEquals(3, winter.dayCount)
        // 无任何季节桶跨 12 个月以上（合并缺陷的量化锚点）
        assertTrue(view.seasons.all { it.startDate <= it.endDate })
        val monthSpan = { s: JourneySeasonSummary ->
            val start = java.time.LocalDate.parse(s.startDate)
            val end = java.time.LocalDate.parse(s.endDate)
            java.time.temporal.ChronoUnit.MONTHS.between(start, end)
        }
        assertTrue(view.seasons.all { monthSpan(it) <= 5L })
    }

    @Test
    fun yearViewAttributesShiftsToCorrectYearBucket() {
        // 2025-11 稳定段 → 2025-12 转变：转变点必须归属 WINTER-2025（12 月），不落 2026 桶
        val novStable = (1..30).map { i -> journeyDay("2025-11-${i.toString().padStart(2, '0')}") }
        val decChanged = (1..30).map { i ->
            journeyDay(
                "2025-12-${i.toString().padStart(2, '0')}",
                params = visualParams { flowSpeed = 0.95f; coherence = 0.95f; turbulence = 0.95f; structureComplexity = 0.95f },
            )
        }
        // 2026-06 与 12 月同样参数（稳定延续）：跨年后 SUMMER-2026 独立成桶、不误报转变
        val junStable = (1..30).map { i ->
            journeyDay(
                "2026-06-${i.toString().padStart(2, '0')}",
                params = visualParams { flowSpeed = 0.95f; coherence = 0.95f; turbulence = 0.95f; structureComplexity = 0.95f },
            )
        }
        val view = buildYearView(days = novStable + decChanged + junStable, canonicalDays = emptyList(), contextExceptions = emptyMap())
        assertEquals(1, view.majorShifts.size)
        assertEquals("2025-12-01", view.majorShifts.first().date)
        val winter = view.seasons.single { it.season == SEASON_WINTER }
        assertTrue(winter.majorShifts.any { it.date == "2025-12-01" })
        val summer2026 = view.seasons.single { it.season == SEASON_SUMMER }
        assertTrue(summer2026.majorShifts.isEmpty())
        assertEquals("2026-06-01", summer2026.startDate)
    }
}
