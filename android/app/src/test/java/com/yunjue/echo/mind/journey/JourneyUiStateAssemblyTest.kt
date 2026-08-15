package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 13 §24/§102 — JourneyUiState 装配矩阵：
 * empty / partial / 7d / 28d / 90d / 365d / missing days / context exceptions / AI unavailable。
 */
class JourneyUiStateAssemblyTest {

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

    private fun assemble(
        scale: JourneyScale = JourneyScale.DAY,
        timeline: PortraitTimelineUiState = PortraitTimelineUiState(loading = false),
        permissionEnabled: Boolean = true,
        narrative: JourneyNarrative? = null,
    ): JourneyUiState = assembleJourneyUiState(
        scale = scale,
        timeline = timeline,
        permissionEnabled = permissionEnabled,
        narrative = narrative,
        runtimeAvailability = null,
        runtimeDiagnostics = null,
        showEvidence = false,
        intelligenceAvailable = true,
        syncStatus = JourneySyncStatus(permissionEnabled = permissionEnabled),
        journeySeed = 42L,
    )

    @Test
    fun emptyTimelineIsNoData() {
        val state = assemble()
        assertEquals(TrendUiState.NO_DATA, state.trendState)
        assertTrue(state.visualDays.isEmpty())
        assertNull(state.selectedPeriod)
        assertEquals(42L, state.journeySeed)
    }

    @Test
    fun loadingAndErrorSurviveAssembly() {
        assertEquals(
            TrendUiState.LOADING,
            assemble(timeline = PortraitTimelineUiState(loading = true)).trendState
        )
        assertEquals(
            TrendUiState.ERROR,
            assemble(timeline = PortraitTimelineUiState(loading = false, loadFailed = true)).trendState
        )
    }

    @Test
    fun permissionDisabledWinsOverData() {
        val timeline = PortraitTimelineUiState(loading = false, portraits = listOf(portrait("2026-08-14")))
        assertEquals(TrendUiState.PERMISSION_DISABLED, assemble(timeline = timeline, permissionEnabled = false).trendState)
    }

    @Test
    fun partialWithMissingDaysIsPartial() {
        val timeline = PortraitTimelineUiState(
            loading = false,
            isPartial = true,
            missingDates = listOf("2026-08-12"),
            portraits = listOf(portrait("2026-08-14"), portrait("2026-08-13")),
        )
        val state = assemble(timeline = timeline)
        assertEquals(TrendUiState.PARTIAL, state.trendState)
        assertEquals(listOf("2026-08-12"), state.timeline.missingDates)
    }

    @Test
    fun sevenDayScaleUsesDayGridAndWindow() {
        val state = assemble(scale = JourneyScale.DAY, timeline = freshTimeline(7))
        assertEquals(7, state.windowDays)
        assertEquals(TrendUiState.FRESH, state.trendState)
        assertEquals(7, state.visualDays.size)
        assertTrue(state.visualPeriods.isEmpty()) // DAY 用逐日网格
        assertNotNull(state.selectedPeriod) // 最近一天作为周期锚点
        assertEquals("2026-08-14", state.selectedPeriod?.days?.lastOrNull()?.date)
    }

    @Test
    fun twentyEightAndLongWindowsAggregate() {
        // 28d（WEEK/MONTH 窗口）
        val week = assemble(scale = JourneyScale.WEEK, timeline = freshTimeline(28))
        assertEquals(28, week.windowDays)
        assertEquals(4, week.visualPeriods.size) // 7 天一组
        // 90d（SEASON）
        val season = assemble(scale = JourneyScale.SEASON, timeline = freshTimeline(90))
        assertEquals(90, season.windowDays)
        assertEquals(3, season.visualPeriods.size) // 30 天一组
        // 365d（YEAR）
        val year = assemble(scale = JourneyScale.YEAR, timeline = freshTimeline(365))
        assertEquals(365, year.windowDays)
        assertEquals(13, year.visualPeriods.size) // 365/30 → 13 组（不足一组也成组）
        assertNotNull(year.selectedPeriod?.aggregateParams)
    }

    @Test
    fun contextExceptionsSurfaceInState() {
        val narrative = JourneyNarrative(
            result = AiNarrativeService.NarrativeResult(
                level = NarrativeFallbackLevel.AI_NARRATIVE,
                text = "这周节奏整体后移。",
            ),
            contextExceptions = listOf("出差", "考试周"),
        )
        val state = assemble(timeline = freshTimeline(7), narrative = narrative)
        assertEquals(listOf("出差", "考试周"), state.narrative?.contextExceptions)
        assertEquals("这周节奏整体后移。", state.narrative?.result?.text)
    }

    @Test
    fun aiUnavailableFallsBackToDeterministic() {
        val narrative = JourneyNarrative(
            result = AiNarrativeService.NarrativeResult(
                level = NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                text = "最接近：作息；变化较明显：屏幕总量",
            ),
        )
        val state = assemble(timeline = freshTimeline(7), narrative = narrative)
        assertEquals(NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE, state.narrative?.result?.level)
        assertTrue(state.narrative?.contextExceptions.orEmpty().isEmpty())
    }

    @Test
    fun scaleSwitchKeepsSeedStable() {
        val day = assemble(scale = JourneyScale.DAY, timeline = freshTimeline(7))
        val year = assemble(scale = JourneyScale.YEAR, timeline = freshTimeline(365))
        assertEquals(day.journeySeed, year.journeySeed)
    }

    // ===== ERA 16 §81-§87 =====

    @Test
    fun dayScaleBuildsRiverSegments() {
        val state = assemble(scale = JourneyScale.DAY, timeline = freshTimeline(7))
        assertTrue(state.riverSegments.isNotEmpty())
        assertTrue(state.yearView == null) // 年视图仅 YEAR 尺度
        assertTrue(state.seasonExplanation.isEmpty()) // 阶段解释仅 SEASON/YEAR
    }

    @Test
    fun yearScaleBuildsYearViewAndSeasonExplanation() {
        val state = assemble(scale = JourneyScale.YEAR, timeline = freshTimeline(365))
        assertNotNull(state.yearView)
        assertTrue(state.yearView!!.seasons.isNotEmpty())
        assertTrue(state.riverSegments.isNotEmpty())
    }

    @Test
    fun yearWindowSplitsSeasonsAcrossCalendarYearsInAssembly() {
        // ERA 70 装配级回归：365 天窗口（2025-08-15 → 2026-08-14）跨日历年，
        // 同标签季节（SUMMER 2025 / SUMMER 2026）必须各自成桶（seasonKeyOf 修复的端到端锚点）。
        val state = assemble(scale = JourneyScale.YEAR, timeline = freshTimeline(365))
        val year = state.yearView
        assertNotNull(year)
        val summers = year!!.seasons.filter { it.season == SEASON_SUMMER }
        assertEquals(2, summers.size)
        assertTrue(summers[0].startDate < summers[1].startDate)
        assertTrue(year.seasons.all { it.startDate <= it.endDate })
        assertEquals(365, year.seasons.sumOf { it.dayCount })
    }

    @Test
    fun contextExceptionsMarkSpecialRiverSegments() {
        val state = assembleJourneyUiState(
            scale = JourneyScale.DAY,
            timeline = freshTimeline(7),
            permissionEnabled = true,
            narrative = null,
            runtimeAvailability = null,
            runtimeDiagnostics = null,
            showEvidence = false,
            intelligenceAvailable = true,
            syncStatus = JourneySyncStatus(permissionEnabled = true),
            journeySeed = 42L,
            memory = JourneyMemoryAssemblyInputs(
                contextExceptions = mapOf("2026-08-12" to "travel"),
            ),
        )
        assertTrue(
            "特殊日期应产生 SPECIAL 河段",
            state.riverSegments.any { it.kind == RiverSegmentKind.SPECIAL }
        )
    }

    @Test
    fun selectedDaySurfacesReconstructionInputs() {
        val state = assembleJourneyUiState(
            scale = JourneyScale.DAY,
            timeline = freshTimeline(7),
            permissionEnabled = true,
            narrative = null,
            runtimeAvailability = null,
            runtimeDiagnostics = null,
            showEvidence = false,
            intelligenceAvailable = true,
            syncStatus = JourneySyncStatus(permissionEnabled = true),
            journeySeed = 42L,
            memory = JourneyMemoryAssemblyInputs(
                selectedDayDate = "2026-08-12",
                canonicalDays = emptyList(),
            ),
        )
        assertEquals("2026-08-12", state.selectedDay?.date)
        assertNull(state.selectedCanonical) // 无 Canonical 快照 → 不编造
    }

    @Test
    fun canonicalDaysArePassedThroughForHistoricalReconstruction() {
        val canonical = canonicalDay("2026-08-12", seed = 42L)
        val state = assembleJourneyUiState(
            scale = JourneyScale.DAY,
            timeline = freshTimeline(7),
            permissionEnabled = true,
            narrative = null,
            runtimeAvailability = null,
            runtimeDiagnostics = null,
            showEvidence = false,
            intelligenceAvailable = true,
            syncStatus = JourneySyncStatus(permissionEnabled = true),
            journeySeed = 42L,
            memory = JourneyMemoryAssemblyInputs(
                selectedDayDate = "2026-08-12",
                canonicalDays = listOf(canonical),
            ),
        )
        assertEquals(canonical, state.selectedCanonical)
        assertEquals(listOf(canonical), state.canonicalDays)
    }

    private fun freshTimeline(days: Int): PortraitTimelineUiState {
        val start = java.time.LocalDate.of(2026, 8, 14).minusDays((days - 1).toLong())
        val portraits = (0 until days).map { i ->
            portrait(start.plusDays(i.toLong()).toString(), if (i % 3 == 0) "MORE" else "SIMILAR")
        }
        return PortraitTimelineUiState(days = days, portraits = portraits, loading = false)
    }
}
