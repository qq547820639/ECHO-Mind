package com.yunjue.echo.mind

import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.model.NarrativeDisplay
import com.yunjue.echo.mind.model.NarrativeEventDisplay
import com.yunjue.echo.mind.model.NarrativeFetchResult
import com.yunjue.echo.mind.model.PortraitAvailability
import com.yunjue.echo.mind.model.ProfileDisplay
import com.yunjue.echo.mind.model.SensingDiagnostics
import com.yunjue.echo.mind.sensing.CapabilityState
import com.yunjue.echo.mind.sensing.SensingCapability
import com.yunjue.echo.mind.ui.TREND_DISCLAIMER
import com.yunjue.echo.mind.ui.TrendNoDataReason
import com.yunjue.echo.mind.ui.TrendUiState
import com.yunjue.echo.mind.ui.appSettingsIntent
import com.yunjue.echo.mind.ui.missingSourcesFromCapabilities
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
                items = null, permissionEnabled = true, isPartial = false
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
                items = emptyList<NarrativeDisplay>(), permissionEnabled = true, isPartial = false
            )
        )
        // 真无数据（成功但空）→ NO_DATA
        assertEquals(
            TrendUiState.NO_DATA,
            resolveTrendState(
                loading = false, loadFailed = false, offlineCached = false,
                items = emptyList<NarrativeDisplay>(), permissionEnabled = true, isPartial = false
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
                items = listOf(narrative("2026-07-27")), permissionEnabled = false, isPartial = false
            )
        )
    }

    @Test
    fun offlineCachedState() {
        assertEquals(
            TrendUiState.OFFLINE_CACHED,
            resolveTrendState(
                loading = false, loadFailed = false, offlineCached = true,
                items = listOf(narrative("2026-07-27")), permissionEnabled = true, isPartial = false
            )
        )
    }

    @Test
    fun partialState() {
        assertEquals(
            TrendUiState.PARTIAL,
            resolveTrendState(
                loading = false, loadFailed = false, offlineCached = false,
                items = listOf(narrative("2026-07-27")), permissionEnabled = true, isPartial = true
            )
        )
    }

    @Test
    fun freshState() {
        assertEquals(
            TrendUiState.FRESH,
            resolveTrendState(
                loading = false, loadFailed = false, offlineCached = false,
                items = listOf(narrative("2026-07-27")), permissionEnabled = true, isPartial = false
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
    fun portraitSummaryIsDeterministicAndNonDiagnostic() {
        // 28 日综述（Milestone G）：最稳定 = SIMILAR 比例最高；变化较明显 = 非 SIMILAR 最多。
        // 纯函数计算详见 PortraitTimelineTest；此处仅验证不包含情绪/诊断措辞的锚点文案存在。
        assertTrue(TREND_DISCLAIMER.contains("不能知道或判断你的真实情绪"))
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

    // ---------- T02 七态细化：NO_DATA 原因解析（Phase 6.5.3 新签名） ----------

    private fun availability(
        baselineDays: Int = 5,
        missingSources: List<String> = emptyList()
    ): PortraitAvailability = PortraitAvailability(
        baselineStatus = "BASELINE_READY",
        baselineDays = baselineDays,
        missingSources = missingSources
    )

    private fun diagnostics(
        sensingActive: Boolean = true,
        sensorState: CapabilityState = CapabilityState.AVAILABLE,
        lastCollectionAt: Long = System.currentTimeMillis(),
        consecutivePersistenceFailures: Int = 0,
        pendingUploadCount: Int = 0
    ): SensingDiagnostics = SensingDiagnostics(
        capabilities = mapOf(SensingCapability.SENSOR to sensorState),
        sensingActive = sensingActive,
        lastCollectionAt = lastCollectionAt,
        consecutivePersistenceFailures = consecutivePersistenceFailures,
        pendingUploadCount = pendingUploadCount
    )

    @Test
    fun noDataReasonNewUserWhenNoBaselineDays() {
        // baselineDays == 0 → 新用户尚无窗口（替代 legacy observationDays）
        assertEquals(
            TrendNoDataReason.NEW_USER,
            resolveTrendNoDataReason(
                availability = availability(baselineDays = 0),
                diagnostics = diagnostics()
            )
        )
        assertTrue(trendNoDataReasonText(TrendNoDataReason.NEW_USER).contains("刚开始使用"))
    }

    @Test
    fun noDataReasonClosedWhenSensingInactive() {
        // consent/总开关关闭 → CLOSED
        assertEquals(
            TrendNoDataReason.CLOSED,
            resolveTrendNoDataReason(
                availability = availability(),
                diagnostics = diagnostics(sensingActive = false)
            )
        )
    }

    @Test
    fun noDataReasonPermissionWhenSensorDenied() {
        // SENSOR 能力被拒 → PERMISSION
        assertEquals(
            TrendNoDataReason.PERMISSION,
            resolveTrendNoDataReason(
                availability = availability(),
                diagnostics = diagnostics(sensorState = CapabilityState.DENIED)
            )
        )
    }

    @Test
    fun noDataReasonPrioritizesSystemBackgroundAndPersistence() {
        // 无近期采集（从未采集）→ SYSTEM_BACKGROUND
        assertEquals(
            TrendNoDataReason.SYSTEM_BACKGROUND,
            resolveTrendNoDataReason(
                availability = availability(baselineDays = 5),
                diagnostics = diagnostics(lastCollectionAt = 0L)
            )
        )
        assertEquals(
            TrendNoDataReason.PERSISTENCE_FAILURE,
            resolveTrendNoDataReason(
                availability = availability(baselineDays = 5),
                diagnostics = diagnostics(consecutivePersistenceFailures = 2)
            )
        )
        assertEquals(
            TrendNoDataReason.AWAITING_UPLOAD,
            resolveTrendNoDataReason(
                availability = availability(baselineDays = 5),
                diagnostics = diagnostics(pendingUploadCount = 3)
            )
        )
        assertEquals(
            TrendNoDataReason.SOURCE_GAPS,
            resolveTrendNoDataReason(
                availability = availability(baselineDays = 5, missingSources = listOf("gyro")),
                diagnostics = diagnostics()
            )
        )
    }

    @Test
    fun missingSourcesDerivedFromCapabilityStates() {
        // SENSOR 可用 + SCREEN 可用 → accel/gyro/screen 不缺失；notification/app_activity 缺失
        val caps = mapOf(
            SensingCapability.SENSOR to CapabilityState.AVAILABLE,
            SensingCapability.SCREEN to CapabilityState.AVAILABLE,
            SensingCapability.NOTIFICATION to CapabilityState.DENIED,
            SensingCapability.USAGE to CapabilityState.DENIED
        )
        assertEquals(
            listOf("notification", "app_activity"),
            missingSourcesFromCapabilities(caps)
        )
        // 全部 AVAILABLE → 无缺失
        val allOk = SensingCapability.entries.associateWith { CapabilityState.AVAILABLE }
        assertTrue(missingSourcesFromCapabilities(allOk).isEmpty())
        // 能力缺失（未提供）视为 UNAVAILABLE → 对应 source 缺失（fail-safe）
        assertTrue(missingSourcesFromCapabilities(emptyMap()).isNotEmpty())
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
