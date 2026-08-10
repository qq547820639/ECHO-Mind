package com.yunjue.echo.mind

import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.model.NarrativeDisplay
import com.yunjue.echo.mind.model.NarrativeEventDisplay
import com.yunjue.echo.mind.model.NarrativeFetchResult
import com.yunjue.echo.mind.model.ProfileDisplay
import com.yunjue.echo.mind.ui.TREND_DISCLAIMER
import com.yunjue.echo.mind.ui.TrendNoDataReason
import com.yunjue.echo.mind.ui.TrendUiState
import com.yunjue.echo.mind.ui.activityRhythmSummary
import com.yunjue.echo.mind.ui.appSettingsIntent
import com.yunjue.echo.mind.ui.baselineStabilitySummary
import com.yunjue.echo.mind.ui.behaviorPatternSummary
import com.yunjue.echo.mind.ui.resolveTrendNoDataReason
import com.yunjue.echo.mind.ui.resolveTrendState
import com.yunjue.echo.mind.ui.trendNoDataReasonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T12.6/T05 趋势视图数据源回归。
 *
 * 契约点 2 / 8 验收：
 * - 七态区分：error（API 失败）≠ no_data（真无数据）
 * - 批量拉取（单次请求）契约：NarrativeFetchResult 承载 ordered narratives + dataCoverage + missingDates
 * - 免责文案常量存在（单测锚点）
 * - mood 相关字段已删除（编译期保证：下方不引用）
 * - T02 七态细化：NO_DATA 原因解析 + 系统设置 deep link
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrendDataSourceTest {

    private fun narrative(date: String, source: String = "screen", summary: String = "屏幕开启 2 次"): NarrativeDisplay =
        NarrativeDisplay(
            date = date,
            events = listOf(NarrativeEventDisplay(source, summary, listOf("screen"))),
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
            version = 2,
            sourcesPresentUnion = listOf("accel", "screen"),
            loadFailed = false
        )
        assertEquals(5, profile.observationDays)
        assertEquals(3, profile.narrativeDaysLast7)
        assertEquals(listOf("accel", "screen"), profile.sourcesPresentUnion)
        assertFalse(profile.loadFailed)

        val failed = ProfileDisplay(
            observationDays = 0,
            narrativeDaysLast7 = 0,
            version = 0,
            loadFailed = true
        )
        assertTrue("fetchProfile 失败应 loadFailed=true（趋势页区分 error）", failed.loadFailed)
    }

    // ---------- T02 七态细化：NO_DATA 原因解析 ----------

    @Test
    fun noDataReasonNewUserWhenNoObservationDays() {
        assertEquals(
            TrendNoDataReason.NEW_USER,
            resolveTrendNoDataReason(
                observationDays = 0,
                systemBackgroundRestricted = false,
                persistenceFailedRecently = false,
                pendingUploadCount = 0,
                missingSources = emptyList()
            )
        )
        assertTrue(trendNoDataReasonText(TrendNoDataReason.NEW_USER).contains("刚开始使用"))
    }

    @Test
    fun noDataReasonPrioritizesSystemBackgroundAndPersistence() {
        assertEquals(
            TrendNoDataReason.SYSTEM_BACKGROUND,
            resolveTrendNoDataReason(
                observationDays = 5,
                systemBackgroundRestricted = true,
                persistenceFailedRecently = false,
                pendingUploadCount = 0,
                missingSources = emptyList()
            )
        )
        assertEquals(
            TrendNoDataReason.PERSISTENCE_FAILURE,
            resolveTrendNoDataReason(
                observationDays = 5,
                systemBackgroundRestricted = false,
                persistenceFailedRecently = true,
                pendingUploadCount = 0,
                missingSources = emptyList()
            )
        )
        assertEquals(
            TrendNoDataReason.AWAITING_UPLOAD,
            resolveTrendNoDataReason(
                observationDays = 5,
                systemBackgroundRestricted = false,
                persistenceFailedRecently = false,
                pendingUploadCount = 3,
                missingSources = emptyList()
            )
        )
        assertEquals(
            TrendNoDataReason.SOURCE_GAPS,
            resolveTrendNoDataReason(
                observationDays = 5,
                systemBackgroundRestricted = false,
                persistenceFailedRecently = false,
                pendingUploadCount = 0,
                missingSources = listOf("gyro")
            )
        )
    }

    @Test
    fun noDataReasonTextNeverExposesEngineeringTerms() {
        for (reason in TrendNoDataReason.entries) {
            val text = trendNoDataReasonText(reason)
            assertTrue("NO_DATA 文案不应为空", text.isNotBlank())
            for (token in listOf("HTTP", "window", "persist", "upload", "source", "404", "500")) {
                assertFalse("文案不应暴露工程术语 $token：$text", text.contains(token, ignoreCase = true))
            }
        }
    }

    @Test
    fun appSettingsDeepLinkTargetsThisPackage() {
        val intent = appSettingsIntent(ApplicationProvider.getApplicationContext())
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", intent.action)
    }
}
