package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.model.PortraitAvailability
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.SensingDiagnostics
import com.yunjue.echo.mind.presence.computeLifeSeason

/**
 * ERA 13 §24 + ERA 16 §81-§87 — Journey 单一 UI 状态。
 *
 * Screen 只消费本状态；所有业务装配（七态解析 / 视觉记忆装配 / 叙事 / 证据 /
 * 视觉记忆河流 / 年视图 / 历史重建 / Life Season 解释）发生在
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

/**
 * ERA 16 — Journey 长期记忆装配输入（避免 LongParameterList）。
 * 全部来自应用层数据；Screen 与 ViewModel 都不各自拼状态。
 */
data class JourneyMemoryAssemblyInputs(
    /** 已落盘的 Canonical Daily State（按日期升序）。 */
    val canonicalDays: List<JourneyCanonicalDay> = emptyList(),
    /** 用户自述特殊日期（date → kind，§78）。 */
    val contextExceptions: Map<String, String> = emptyMap(),
    /** 当前选中的历史日期（§84 历史重建入口；null = 未选择）。 */
    val selectedDayDate: String? = null,
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
    // ===== ERA 16 §81-§87 — Journey 长期记忆 =====
    /** 已落盘的 Canonical Daily State（§83；UI 只在重建时消费）。 */
    val canonicalDays: List<JourneyCanonicalDay> = emptyList(),
    /** 视觉记忆河流（§85：平稳/密集/漂移/特殊/转变河段）。 */
    val riverSegments: List<JourneyRiverSegment> = emptyList(),
    /** 年视图（§86：仅在 YEAR 尺度装配；其余尺度 null）。 */
    val yearView: JourneyYearView? = null,
    /** Life Season × Journey 解释（§87：SEASON/YEAR 尺度）。 */
    val seasonExplanation: List<String> = emptyList(),
    /** 选中历史日期（§84 历史重建：那天 ECHO 的视觉事实）。 */
    val selectedDay: JourneyDay? = null,
    val selectedCanonical: JourneyCanonicalDay? = null,
    val selectedDayExplanation: List<String> = emptyList(),
)

/**
 * ERA 72 §108 — Journey 记忆装配中间态（两段式装配的第一段）。
 *
 * 重计算（视觉日/周期/河流/年视图/生活阶段解释）只随窗口与记忆输入变化；
 * UI 轻量输入（evidence 折叠/叙事/运行时快照）复用本实例，不再触发 365 天全量重算。
 */
data class JourneyMemoryState(
    val scale: JourneyScale,
    val timeline: PortraitTimelineUiState,
    val canonicalDays: List<JourneyCanonicalDay> = emptyList(),
    val visualDays: List<JourneyDay> = emptyList(),
    val visualPeriods: List<JourneyPeriod> = emptyList(),
    val selectedPeriod: JourneyPeriod? = null,
    val riverSegments: List<JourneyRiverSegment> = emptyList(),
    val yearView: JourneyYearView? = null,
    val seasonExplanation: List<String> = emptyList(),
    val selectedDay: JourneyDay? = null,
    val selectedCanonical: JourneyCanonicalDay? = null,
    val selectedDayExplanation: List<String> = emptyList(),
)

/**
 * ERA 72 §108 — 两段式装配第一段：全部 Journey 长历史重计算（纯函数、确定性）。
 * 输入只含窗口/时间线/Canonical 快照/上下文例外/选中日期。
 */
fun assembleJourneyMemoryState(
    scale: JourneyScale,
    timeline: PortraitTimelineUiState,
    memory: JourneyMemoryAssemblyInputs = JourneyMemoryAssemblyInputs(),
): JourneyMemoryState {
    val visualDays = buildJourneyDays(timeline.portraits)
    val visualPeriods = when (scale) {
        JourneyScale.DAY -> emptyList()
        else -> buildJourneyPeriods(visualDays, journeyChunkDays(scale))
    }
    val selectedPeriod = visualPeriods.lastOrNull { it.aggregateParams != null }
        ?: visualPeriods.lastOrNull()
        ?: visualDays.lastOrNull()?.let { JourneyPeriod(listOf(it), it.visualParams, it) }

    val riverSegments = buildVisualMemoryRiver(
        days = visualDays,
        contextExceptions = memory.contextExceptions,
        chunkDays = journeyChunkDays(scale),
    )
    val yearView = if (scale == JourneyScale.YEAR) {
        buildYearView(
            days = visualDays,
            canonicalDays = memory.canonicalDays,
            contextExceptions = memory.contextExceptions,
        )
    } else null
    val seasonExplanation = if (scale == JourneyScale.SEASON || scale == JourneyScale.YEAR) {
        explainLifeSeasonVisual(computeLifeSeason(timeline.portraits))
    } else emptyList()
    val selectedDay = memory.selectedDayDate?.let { date -> visualDays.firstOrNull { it.date == date } }
    val selectedCanonical = memory.selectedDayDate?.let { date ->
        memory.canonicalDays.firstOrNull { it.date == date }
    }
    val selectedDayExplanation = selectedDay?.let { day ->
        val index = visualDays.indexOfFirst { it.date == day.date }
        val previous = visualDays.getOrNull(index - 1)
        explainPeriodChange(
            before = previous?.visualParams,
            after = day.visualParams,
            beforeDate = previous?.date,
            afterDate = day.date,
        )
    } ?: emptyList()

    return JourneyMemoryState(
        scale = scale,
        timeline = timeline,
        canonicalDays = memory.canonicalDays,
        visualDays = visualDays,
        visualPeriods = visualPeriods,
        selectedPeriod = selectedPeriod,
        riverSegments = riverSegments,
        yearView = yearView,
        seasonExplanation = seasonExplanation,
        selectedDay = selectedDay,
        selectedCanonical = selectedCanonical,
        selectedDayExplanation = selectedDayExplanation,
    )
}

/**
 * Journey UI 状态纯函数装配器（§24/§81-§87；与 EchoSceneUiState 装配器同模式）。
 *
 * 输入全部来自应用层数据（timeline/permission/runtime/narrative/memory）；
 * Screen 与 ViewModel 都不各自拼状态。
 */
fun assembleJourneyUiState(
    memoryState: JourneyMemoryState,
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
        loading = memoryState.timeline.loading,
        loadFailed = memoryState.timeline.loadFailed,
        offlineCached = memoryState.timeline.fromCache,
        items = memoryState.timeline.portraits.ifEmpty { null },
        permissionEnabled = permissionEnabled,
        isPartial = memoryState.timeline.isPartial,
    )

    return JourneyUiState(
        selectedScale = memoryState.scale,
        windowDays = journeyWindowDays(memoryState.scale),
        timeline = memoryState.timeline,
        trendState = trendState,
        availability = availability,
        diagnostics = diagnostics,
        noDataReason = resolveTrendNoDataReason(availability, diagnostics),
        visualDays = memoryState.visualDays,
        visualPeriods = memoryState.visualPeriods,
        selectedPeriod = memoryState.selectedPeriod,
        narrative = narrative,
        showEvidence = showEvidence,
        intelligenceAvailable = intelligenceAvailable,
        syncStatus = syncStatus,
        loading = memoryState.timeline.loading,
        error = trendState == TrendUiState.ERROR,
        journeySeed = journeySeed,
        canonicalDays = memoryState.canonicalDays,
        riverSegments = memoryState.riverSegments,
        yearView = memoryState.yearView,
        seasonExplanation = memoryState.seasonExplanation,
        selectedDay = memoryState.selectedDay,
        selectedCanonical = memoryState.selectedCanonical,
        selectedDayExplanation = memoryState.selectedDayExplanation,
    )
}

/** 兼容既有调用与测试的单段装配（内部走两段式；等价性由测试锚定）。 */
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
    memory: JourneyMemoryAssemblyInputs = JourneyMemoryAssemblyInputs(),
): JourneyUiState = assembleJourneyUiState(
    memoryState = assembleJourneyMemoryState(scale, timeline, memory),
    permissionEnabled = permissionEnabled,
    narrative = narrative,
    runtimeAvailability = runtimeAvailability,
    runtimeDiagnostics = runtimeDiagnostics,
    showEvidence = showEvidence,
    intelligenceAvailable = intelligenceAvailable,
    syncStatus = syncStatus,
    journeySeed = journeySeed,
)
