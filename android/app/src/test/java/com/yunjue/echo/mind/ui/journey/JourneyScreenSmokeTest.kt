package com.yunjue.echo.mind.ui.journey

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.NarrativeFallbackLevel
import com.yunjue.echo.mind.journey.JourneyEvent
import com.yunjue.echo.mind.journey.JourneyNarrative
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneySyncStatus
import com.yunjue.echo.mind.journey.JourneyUiState
import com.yunjue.echo.mind.journey.TrendNoDataReason
import com.yunjue.echo.mind.journey.TrendUiState
import com.yunjue.echo.mind.journey.assembleJourneyUiState
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.ui.TREND_DISCLAIMER
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 32 — JourneyScreen 纯状态渲染 smoke test（Robolectric + Compose）：
 * 免责声明 / 六态分支（LOADING·PERMISSION_DISABLED·ERROR·NO_DATA·OFFLINE_CACHED·FRESH）/
 * 尺度选择事件 / 单日历史重建事件 / Evidence 折叠事件。
 *
 * 不构造 ViewModel / Repository / DB——状态直接注入 JourneyScreenContent（state-in / event-out）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JourneyScreenSmokeTest {

    @get:Rule
    val compose = createComposeRule()

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

    private fun freshState(showEvidence: Boolean = false) = assembleJourneyUiState(
        scale = JourneyScale.DAY,
        timeline = PortraitTimelineUiState(
            days = 7,
            loading = false,
            portraits = listOf(portrait("2026-08-14")),
        ),
        permissionEnabled = true,
        narrative = JourneyNarrative(
            result = AiNarrativeService.NarrativeResult(
                level = NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                text = "这段时期你的作息更早、更规律。",
                usedSources = listOf(DataSourceCategory.PORTRAIT_HISTORY),
            ),
        ),
        runtimeAvailability = null,
        runtimeDiagnostics = null,
        showEvidence = showEvidence,
        intelligenceAvailable = true,
        syncStatus = JourneySyncStatus(consent = true, permissionEnabled = true),
        journeySeed = 42L,
    )

    private fun setJourneyContent(
        state: JourneyUiState,
        events: MutableList<JourneyEvent> = mutableListOf(),
        onGoToSupport: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                JourneyScreenContent(
                    state = state,
                    onEvent = { events += it },
                    feedback = { null },
                    onGoToSupport = onGoToSupport,
                )
            }
        }
    }

    @Test
    fun disclaimerAndTitleAlwaysRendered() {
        setJourneyContent(JourneyUiState(trendState = TrendUiState.LOADING))
        compose.onNodeWithText(TREND_DISCLAIMER).assertExists()
        compose.onNodeWithText("旅程 · 我的时间").assertExists()
    }

    @Test
    fun loadingBranchShowsLoadingText() {
        setJourneyContent(JourneyUiState(trendState = TrendUiState.LOADING))
        compose.onNodeWithText("旅程加载中…").assertExists()
    }

    @Test
    fun permissionDisabledBranchShowsRepairEntry() {
        setJourneyContent(JourneyUiState(trendState = TrendUiState.PERMISSION_DISABLED))
        compose.onNodeWithText("被动感知已关闭或权限被撤，无法获取新的旅程数据。").assertExists()
        compose.onNodeWithText("前往系统设置修复权限").assertExists()
    }

    @Test
    fun errorBranchRetryEmitsRefreshEvent() {
        val events = mutableListOf<JourneyEvent>()
        setJourneyContent(JourneyUiState(trendState = TrendUiState.ERROR), events = events)
        compose.onNodeWithText("旅程加载失败").assertExists()
        compose.onNodeWithText("重试").performClick()
        assertEquals(listOf(JourneyEvent.Refresh), events)
    }

    @Test
    fun noDataBranchShowsReasonAndSupportEntry() {
        var supportRequested = false
        setJourneyContent(
            state = JourneyUiState(
                trendState = TrendUiState.NO_DATA,
                noDataReason = TrendNoDataReason.CLOSED,
            ),
            onGoToSupport = { supportRequested = true },
        )
        compose.onNodeWithText("最近成功采集：暂无").assertExists()
        compose.onNodeWithText("前往支持页重新开启").performClick()
        assertTrue(supportRequested)
    }

    @Test
    fun offlineCachedBranchShowsNoticeAndContent() {
        setJourneyContent(
            assembleJourneyUiState(
                scale = JourneyScale.DAY,
                timeline = PortraitTimelineUiState(
                    days = 7,
                    loading = false,
                    fromCache = true,
                    portraits = listOf(portrait("2026-08-14")),
                ),
                permissionEnabled = true,
                narrative = null,
                runtimeAvailability = null,
                runtimeDiagnostics = null,
                showEvidence = false,
                intelligenceAvailable = false,
                syncStatus = JourneySyncStatus(consent = true, permissionEnabled = true),
                journeySeed = 42L,
            )
        )
        compose.onNodeWithText("当前离线，以下为缓存的旅程。").assertExists()
        compose.onNodeWithText("查看依据").assertExists()
    }

    @Test
    fun freshBranchRendersNarrativeAndEvidenceSources() {
        setJourneyContent(freshState())
        compose.onNodeWithText("这段时期你的作息更早、更规律。").assertExists()
        compose.onNodeWithText("依据：历史画像").assertExists()
        compose.onNodeWithText("查看依据").assertExists()
    }

    @Test
    fun evidenceToggleEmitsEventAndFlipsLabel() {
        var showEvidence by mutableStateOf(false)
        val events = mutableListOf<JourneyEvent>()
        compose.setContent {
            MaterialTheme {
                JourneyScreenContent(
                    state = freshState(showEvidence = showEvidence),
                    onEvent = { events += it },
                    feedback = { null },
                )
            }
        }
        compose.onNodeWithText("查看依据").performScrollTo().performClick()
        compose.runOnIdle { showEvidence = true }
        compose.onNodeWithText("收起依据").assertExists()
        compose.onNodeWithText("数据覆盖度：", substring = true).assertExists()
        assertTrue(events.contains(JourneyEvent.ToggleEvidence))
    }

    @Test
    fun scaleChipClickEmitsSelectScale() {
        val events = mutableListOf<JourneyEvent>()
        setJourneyContent(freshState(), events = events)
        compose.onNodeWithText("天").assertIsSelected()
        compose.onNode(hasClickAction() and hasText("周")).performClick()
        assertTrue(events.contains(JourneyEvent.SelectScale(JourneyScale.WEEK)))
    }

    @Test
    fun dayCellClickEmitsSelectDayForToday() {
        val events = mutableListOf<JourneyEvent>()
        setJourneyContent(freshState(), events = events)
        val today = LocalDate.now()
        val label = "${today.monthValue}/${today.dayOfMonth}"
        compose.onNode(hasClickAction() and hasText(label)).performScrollTo().performClick()
        assertTrue(events.contains(JourneyEvent.SelectDay(today.toString())))
    }
}
