package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.model.PortraitAvailability
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.SensingDiagnostics

/**
 * ERA 13 §24 — Journey 单一 UI 状态。
 *
 * Screen 只消费本状态；所有业务装配（七态解析 / 视觉记忆装配 / 叙事 / 证据）发生在
 * 应用层（JourneyViewModel + assembleJourneyUiState 纯函数）。
 */

/** Journey 同步状态摘要（应用层从 SyncStateRepository 快照派生）。 */
data class JourneySyncStatus(
    val lastCollectedAt: Long = 0L,
    val lastSyncedAt: Long = 0L,
    val pendingUploads: Int = 0,
    val persistenceFailures: Int = 0,
    val consent: Boolean = false,
    val permissionEnabled: Boolean = false,
)

/** Journey 叙事结果 + 上下文例外（用户告诉我的特殊日期）。 */
data class JourneyNarrative(
    val result: AiNarrativeService.NarrativeResult,
    val contextExceptions: List<String> = emptyList(),
)

/** Journey 运行时快照（画像可用性 + 感知诊断，应用层一次 IO 内计算）。 */
data class JourneyRuntimeSnapshot(
    val availability: PortraitAvailability,
    val diagnostics: SensingDiagnostics,
)

data class JourneyUiState(
    val selectedScale: JourneyScale = JourneyScale.DAY,
    val windowDays: Int = journeyWindowDays(JourneyScale.DAY),
    val timeline: PortraitTimelineUiState = PortraitTimelineUiState(),
    val trendState: TrendUiState = TrendUiState.LOADING,
    val availability: PortraitAvailability = PortraitAvailability(),
    val diagnostics: SensingDiagnostics = SensingDiagnostics(),
    val noDataReason: TrendNoDataReason = TrendNoDataReason.UNKNOWN,
    /** 视觉记忆：预装配的单日单元（UI 只渲染）。 */
    val visualDays: List<JourneyDay> = emptyList(),
    /** 视觉记忆：预装配的聚合周期（WEEK=7 天 / MONTH/SEASON/YEAR=30 天组）。 */
    val visualPeriods: List<JourneyPeriod> = emptyList(),
    /** 当前选中周期锚点（聚合尺度的最近一组 / DAY 尺度的最近一天）。 */
    val selectedPeriod: JourneyPeriod? = null,
    val narrative: JourneyNarrative? = null,
    val showEvidence: Boolean = false,
    val intelligenceAvailable: Boolean = false,
    val syncStatus: JourneySyncStatus = JourneySyncStatus(),
    val loading: Boolean = true,
    val error: Boolean = false,
    /** Journey 视觉种子（userId 稳定派生；Identity Genome 同源）。 */
    val journeySeed: Long = 0L,
)

/**
 * Journey UI 状态纯函数装配器（§24；与 EchoSceneUiState 装配器同模式）。
 *
 * 输入全部来自应用层数据（timeline/permission/runtime/narrative）；
 * Screen 与 ViewModel 都不各自拼状态。
 */
fun assembleJourneyUiState(
    scale: JourneyScale,
    timeline: PortraitTimelineUiState,
    permissionEnabled: Boolean,
    narrative: JourneyNarrative?,
    runtimeAvailability: PortraitAvailability?,
    runtimeDiagnostics: SensingDiagnostics?,
    showEvidence: Boolean,
    intelligenceAvailable: Boolean,
    syncStatus: JourneySyncStatus,
    journeySeed: Long,
): JourneyUiState {
    val availability = runtimeAvailability ?: PortraitAvailability()
    val diagnostics = runtimeDiagnostics ?: SensingDiagnostics(sensingActive = syncStatus.consent)
    val trendState = resolveTrendState(
        loading = timeline.loading,
        loadFailed = timeline.loadFailed,
        offlineCached = timeline.fromCache,
        items = timeline.portraits.ifEmpty { null },
        permissionEnabled = permissionEnabled,
        isPartial = timeline.isPartial,
    )
    val visualDays = buildJourneyDays(timeline.portraits)
    val visualPeriods = when (scale) {
        JourneyScale.DAY -> emptyList()
        else -> buildJourneyPeriods(visualDays, journeyChunkDays(scale))
    }
    val selectedPeriod = visualPeriods.lastOrNull { it.aggregateParams != null }
        ?: visualPeriods.lastOrNull()
        ?: visualDays.lastOrNull()?.let { JourneyPeriod(listOf(it), it.visualParams, it) }
    return JourneyUiState(
        selectedScale = scale,
        windowDays = journeyWindowDays(scale),
        timeline = timeline,
        trendState = trendState,
        availability = availability,
        diagnostics = diagnostics,
        noDataReason = resolveTrendNoDataReason(availability, diagnostics),
        visualDays = visualDays,
        visualPeriods = visualPeriods,
        selectedPeriod = selectedPeriod,
        narrative = narrative,
        showEvidence = showEvidence,
        intelligenceAvailable = intelligenceAvailable,
        syncStatus = syncStatus,
        loading = timeline.loading,
        error = trendState == TrendUiState.ERROR,
        journeySeed = journeySeed,
    )
}
