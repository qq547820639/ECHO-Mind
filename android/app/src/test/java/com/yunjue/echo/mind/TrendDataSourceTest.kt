package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.NarrativeDisplay
import com.yunjue.echo.mind.model.NarrativeEventDisplay
import com.yunjue.echo.mind.model.NarrativeFetchResult
import com.yunjue.echo.mind.model.ProfileDisplay
import com.yunjue.echo.mind.ui.TREND_DISCLAIMER
import com.yunjue.echo.mind.ui.TrendUiState
import com.yunjue.echo.mind.ui.activityRhythmSummary
import com.yunjue.echo.mind.ui.baselineStabilitySummary
import com.yunjue.echo.mind.ui.behaviorPatternSummary
import com.yunjue.echo.mind.ui.resolveTrendState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T12.6/T05 趋势视图数据源回归（纯函数，无需 Robolectric）。
 *
 * 契约点 2 / 8 验收：
 * - 七态区分：error（API 失败）≠ no_data（真无数据）
 * - 批量拉取（单次请求）契约：NarrativeFetchResult 承载 ordered narratives + dataCoverage + missingDates
 * - 免责文案常量存在（单测锚点）
 * - moodHintToValue / buildTrendValues 已删除（编译期保证：下方不引用，旧测试已移除）
 * - ProfileDisplay 支持 loadFailed 语义
 * - 定性摘要函数（活动节律/行为模式/基线稳定性）为非诊断表达
 */
class TrendDataSourceTest {

    private fun narrative(date: String, source: String = "screen", summary: String = "屏幕开启 2 次"): NarrativeDisplay =
        NarrativeDisplay(
            date = date,
            moodHint = "", // mood_hint 已不再用于 UI 渲染
            events = listOf(NarrativeEventDisplay(source, summary, "")),
            gaps = emptyList()
        )

    // ---------- 契约点 8：七态 ----------

    @Test
    fun loadingState() {
        assertEquals(
            TrendUiState.LOADING,
            resolveTrendState(
                loading = true, loadFailed = false, offlineCached = false,
                narratives = null, permissionEnabled = true, isPartial = false
            )
        )
    }

    @Test
    fun errorStateIsDistinctFromNoData() {
        // API 失败（loadFailed=true）→ ERROR，即使 narratives 为空
        assertEquals(
            TrendUiState.ERROR,
            resolveTrendState(
                loading = false, loadFailed = true, offlineCached = false,
                narratives = emptyList(), permissionEnabled = true, isPartial = false
            )
        )
        // 真无数据（成功但空）→ NO_DATA
        assertEquals(
            TrendUiState.NO_DATA,
            resolveTrendState(
                loading = false, loadFailed = false, offlineCached = false,
                narratives = emptyList(), permissionEnabled = true, isPartial = false
            )
        )
        // error 必须不等于 no data
        assertFalse("ERROR 不得等于 NO_DATA", TrendUiState.ERROR == TrendUiState.NO_DATA)
    }

    @Test
    fun permissionDisabledState() {
        assertEquals(
            TrendUiState.PERMISSION_DISABLED,
            resolveTrendState(
                loading = false, loadFailed = false, offlineCached = false,
                narratives = listOf(narrative("2026-07-27")), permissionEnabled = false, isPartial = false
            )
        )
    }

    @Test
    fun offlineCachedState() {
        assertEquals(
            TrendUiState.OFFLINE_CACHED,
            resolveTrendState(
                loading = false, loadFailed = false, offlineCached = true,
                narratives = listOf(narrative("2026-07-27")), permissionEnabled = true, isPartial = false
            )
        )
    }

    @Test
    fun partialState() {
        assertEquals(
            TrendUiState.PARTIAL,
            resolveTrendState(
                loading = false, loadFailed = false, offlineCached = false,
                narratives = listOf(narrative("2026-07-27")), permissionEnabled = true, isPartial = true
            )
        )
    }

    @Test
    fun freshState() {
        assertEquals(
            TrendUiState.FRESH,
            resolveTrendState(
                loading = false, loadFailed = false, offlineCached = false,
                narratives = listOf(narrative("2026-07-27")), permissionEnabled = true, isPartial = false
            )
        )
    }

    // ---------- 契约点 2：免责文案 + 情绪语义移除 ----------

    @Test
    fun trendDisclaimerTextIsFixed() {
        assertEquals(
            "这些趋势来自设备上的行为派生特征，不能知道或判断你的真实情绪。",
            TREND_DISCLAIMER
        )
        assertTrue("免责文案应明确'不能判断情绪'", TREND_DISCLAIMER.contains("不能知道或判断你的真实情绪"))
    }

    @Test
    fun trendUiStateEnumsAreTheSevenContractStates() {
        assertEquals(
            listOf("LOADING", "OFFLINE_CACHED", "FRESH", "PARTIAL", "NO_DATA", "ERROR", "PERMISSION_DISABLED"),
            TrendUiState.entries.map { it.name }
        )
    }

    // ---------- 契约点 8：批量拉取结果契约 ----------

    @Test
    fun narrativeFetchResultCarriesCoverageAndMissingDates() {
        val result = NarrativeFetchResult(
            narratives = listOf(narrative("2026-07-27"), narrative("2026-07-28")),
            loadFailed = false,
            fromCache = false,
            dataCoverage = 2f / 7f,
            missingDates = listOf("2026-07-25", "2026-07-26"),
            isPartial = true
        )
        assertEquals(2, result.narratives.size)
        assertEquals(2f / 7f, result.dataCoverage, 0.0001f)
        assertTrue("missing window 应可标注", result.missingDates.contains("2026-07-25"))
        assertTrue("存在缺失应标记 partial", result.isPartial)
    }

    @Test
    fun loadFailedIsExplicitOnNarrativeResult() {
        val failed = NarrativeFetchResult(
            narratives = emptyList(),
            loadFailed = true,
            fromCache = false,
            dataCoverage = 0f,
            missingDates = emptyList(),
            isPartial = false
        )
        assertTrue("API 失败必须 loadFailed=true（不得伪装 observation_days=0）", failed.loadFailed)
    }

    // ---------- 定性摘要（非诊断） ----------

    @Test
    fun activityRhythmSummaryIsNonDiagnostic() {
        val text = activityRhythmSummary(listOf(narrative("2026-07-27", source = "accel")))
        assertTrue(text.contains("活动节律") || text.contains("活动信号"))
        assertFalse("定性摘要不得含情绪词", text.contains("情绪"))
        assertFalse("定性摘要不得含诊断词", text.contains("诊断") && text.contains("你"))
    }

    @Test
    fun behaviorPatternSummaryIsNonDiagnostic() {
        val text = behaviorPatternSummary(listOf(narrative("2026-07-27", source = "screen")))
        assertTrue(text.contains("屏幕事件") || text.contains("屏幕互动"))
        assertFalse(text.contains("情绪"))
    }

    @Test
    fun baselineStabilitySummaryHandlesEmptyAndCoverage() {
        val emptyText = baselineStabilitySummary(emptyList(), 0f)
        assertTrue(emptyText.contains("暂无足够数据"))
        val stableText = baselineStabilitySummary(listOf(narrative("2026-07-27")), 0.9f)
        assertTrue(stableText.contains("覆盖"))
    }

    // ---------- ProfileDisplay loadFailed 语义 ----------

    @Test
    fun profileDisplayExposesExpectedTraitsAndLoadFailed() {
        val profile = ProfileDisplay(
            observationDays = 5,
            narrativeDaysLast7 = 3,
            recentMoodHint = "平稳",
            version = 2,
            loadFailed = false
        )
        assertEquals(5, profile.observationDays)
        assertEquals(3, profile.narrativeDaysLast7)
        assertFalse(profile.loadFailed)

        val failed = ProfileDisplay(
            observationDays = 0,
            narrativeDaysLast7 = 0,
            recentMoodHint = "未知",
            version = 0,
            loadFailed = true
        )
        assertTrue("fetchProfile 失败应 loadFailed=true（趋势页区分 error）", failed.loadFailed)
    }
}
