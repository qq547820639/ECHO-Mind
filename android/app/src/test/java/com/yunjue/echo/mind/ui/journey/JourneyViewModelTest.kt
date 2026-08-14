package com.yunjue.echo.mind.ui.journey

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyNarrative
import com.yunjue.echo.mind.journey.JourneyPort
import com.yunjue.echo.mind.journey.JourneyRuntimeSnapshot
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.TrendUiState
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitAvailability
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.SensingDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 13 §23/§25 — JourneyViewModel 事件与状态编排：
 * SelectScale 切换窗口 / Refresh 触发刷新与快照 / 叙事自动生成（AI unavailable → deterministic）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JourneyViewModelTest {

    private open class FakeJourneyPort : JourneyPort {
        val refreshCalls = mutableListOf<Int>()
        val snapshotCalls = mutableListOf<Boolean>()
        val todaySnapshotCalls = mutableListOf<Unit>()
        var narrativeCalls = 0
        val timelineFlow = MutableStateFlow(PortraitTimelineUiState(loading = false))
        val canonicalFlow = MutableStateFlow<List<com.yunjue.echo.mind.journey.JourneyCanonicalDay>>(emptyList())
        var contextExceptions: Map<String, String> = emptyMap()

        override val consentFlow: Flow<Boolean> = flowOf(true)
        override val permissionEnabledFlow: Flow<Boolean> = flowOf(true)
        override fun timeline(days: Int): StateFlow<PortraitTimelineUiState> = timelineFlow
        override suspend fun refresh(days: Int) { refreshCalls += days }
        override suspend fun runtimeSnapshot(consent: Boolean): JourneyRuntimeSnapshot {
            snapshotCalls += consent
            return JourneyRuntimeSnapshot(
                availability = PortraitAvailability(baselineDays = 5, lastCollectedAt = 1234L),
                diagnostics = SensingDiagnostics(sensingActive = consent),
            )
        }
        override suspend fun narrativeFor(portraits: List<DailyPortraitDto>): JourneyNarrative {
            narrativeCalls++
            return JourneyNarrative(
                result = AiNarrativeService.NarrativeResult(
                    level = NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                    text = "最接近：作息；变化较明显：屏幕总量",
                ),
            )
        }
        override fun feedback(date: String): Boolean? = null
        override fun journeySeed(): Long = 7L
        override fun intelligenceAvailable(): Boolean = true
        override val canonicalDays: Flow<List<com.yunjue.echo.mind.journey.JourneyCanonicalDay>> = canonicalFlow
        override suspend fun snapshotToday() { todaySnapshotCalls += Unit }
        override suspend fun contextExceptions(): Map<String, String> = contextExceptions
    }

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun portrait(date: String) = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = 10,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = "SIMILAR", metric = "movement_index", z = 0.5),
        ),
    )

    private fun kotlinx.coroutines.test.TestScope.collectState(vm: JourneyViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun initialRefreshRunsAndNarrativeGeneratedWhenDataArrives() = runTest(mainDispatcher) {
        val fake = FakeJourneyPort()
        val vm = JourneyViewModel(app, fake)
        collectState(vm)
        advanceUntilIdle()
        // init 触发一次 Refresh（DAY=7 天窗口）
        assertEquals(listOf(7), fake.refreshCalls)
        assertEquals(listOf(true), fake.snapshotCalls)
        // 无数据 → 不生成叙事
        assertEquals(0, fake.narrativeCalls)
        assertEquals(TrendUiState.NO_DATA, vm.uiState.value.trendState)
        // 数据到达 → 自动生成叙事（AI unavailable → deterministic fallback）
        fake.timelineFlow.value = PortraitTimelineUiState(
            days = 7, loading = false, portraits = listOf(portrait("2026-08-14")),
        )
        advanceUntilIdle()
        assertEquals(1, fake.narrativeCalls)
        assertEquals(
            NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
            vm.uiState.value.narrative?.result?.level,
        )
        assertEquals(1234L, vm.uiState.value.syncStatus.lastCollectedAt)
    }

    @Test
    fun selectScaleSwitchesWindowAndPeriods() = runTest(mainDispatcher) {
        val fake = FakeJourneyPort()
        val vm = JourneyViewModel(app, fake)
        collectState(vm)
        advanceUntilIdle()
        vm.onEvent(JourneyEvent.SelectScale(JourneyScale.YEAR))
        advanceUntilIdle()
        assertEquals(JourneyScale.YEAR, vm.uiState.value.selectedScale)
        assertEquals(365, vm.uiState.value.windowDays)
        // Refresh 使用新窗口
        vm.onEvent(JourneyEvent.Refresh)
        advanceUntilIdle()
        assertTrue(fake.refreshCalls.contains(365))
    }

    @Test
    fun toggleEvidenceAndRetryNarrativeFlow() = runTest(mainDispatcher) {
        val fake = FakeJourneyPort()
        val vm = JourneyViewModel(app, fake)
        collectState(vm)
        advanceUntilIdle()
        fake.timelineFlow.value = PortraitTimelineUiState(
            days = 7, loading = false, portraits = listOf(portrait("2026-08-14")),
        )
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showEvidence)
        vm.onEvent(JourneyEvent.ToggleEvidence)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showEvidence)
        val callsBefore = fake.narrativeCalls
        vm.onEvent(JourneyEvent.RetryNarrative)
        advanceUntilIdle()
        assertTrue(fake.narrativeCalls > callsBefore)
    }

    @Test
    fun permissionDisabledPropagatesFromPort() = runTest(mainDispatcher) {
        val fake = object : FakeJourneyPort() {
            override val permissionEnabledFlow: Flow<Boolean> = flowOf(false)
        }
        val vm = JourneyViewModel(app, fake)
        collectState(vm)
        advanceUntilIdle()
        fake.timelineFlow.value = PortraitTimelineUiState(
            days = 7, loading = false, portraits = listOf(portrait("2026-08-14")),
        )
        advanceUntilIdle()
        assertEquals(TrendUiState.PERMISSION_DISABLED, vm.uiState.value.trendState)
        assertFalse(vm.uiState.value.syncStatus.permissionEnabled)
        assertFalse(vm.uiState.value.syncStatus.consent)
    }

    @Test
    fun refreshSnapshotsTodayCanonicalState() = runTest(mainDispatcher) {
        val fake = FakeJourneyPort()
        val vm = JourneyViewModel(app, fake)
        collectState(vm)
        advanceUntilIdle()
        // init Refresh → snapshotToday 执行一次（§83）
        assertEquals(1, fake.todaySnapshotCalls.size)
        vm.onEvent(JourneyEvent.Refresh)
        advanceUntilIdle()
        assertEquals(2, fake.todaySnapshotCalls.size)
    }

    @Test
    fun contextExceptionsPropagateIntoState() = runTest(mainDispatcher) {
        val fake = FakeJourneyPort().apply { contextExceptions = mapOf("2026-08-05" to "travel") }
        val vm = JourneyViewModel(app, fake)
        collectState(vm)
        advanceUntilIdle()
        fake.timelineFlow.value = PortraitTimelineUiState(
            days = 7, loading = false, portraits = (1..7).map { i -> portrait("2026-08-${i.toString().padStart(2, '0')}") },
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.riverSegments.any { it.kind == com.yunjue.echo.mind.journey.RiverSegmentKind.SPECIAL })
    }

    @Test
    fun selectDayTogglesHistoricalReconstruction() = runTest(mainDispatcher) {
        val fake = FakeJourneyPort()
        val vm = JourneyViewModel(app, fake)
        collectState(vm)
        advanceUntilIdle()
        fake.timelineFlow.value = PortraitTimelineUiState(
            days = 7, loading = false, portraits = (1..7).map { i -> portrait("2026-08-${i.toString().padStart(2, '0')}") },
        )
        advanceUntilIdle()
        vm.onEvent(JourneyEvent.SelectDay("2026-08-03"))
        advanceUntilIdle()
        assertEquals("2026-08-03", vm.uiState.value.selectedDay?.date)
        // 再次点击同一日期 → 取消选择
        vm.onEvent(JourneyEvent.SelectDay("2026-08-03"))
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.selectedDay)
    }
}
