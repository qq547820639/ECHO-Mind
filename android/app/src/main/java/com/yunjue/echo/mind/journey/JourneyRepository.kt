package com.yunjue.echo.mind.journey

import android.content.Context
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.FeatureFlagRepository
import com.yunjue.echo.mind.data.PortraitRepository
import com.yunjue.echo.mind.data.SyncStateRepository
import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.DataSourceCategory
import com.yunjue.echo.mind.intelligence.EchoContextRetriever
import com.yunjue.echo.mind.intelligence.ReasoningTaskId
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitAvailability
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.model.SensingDiagnostics
import com.yunjue.echo.mind.model.SensingCapability
import com.yunjue.echo.mind.sensing.capabilityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * ERA 13 §26 — Journey Application Service。
 *
 * JourneyScreen 禁止直接持有 Portrait/Sync/FeatureFlag/Memory Repository、
 * AiNarrativeService、EchoContextRetriever、AppPreferences——统一经本仓储访问。
 * （MemoryRepository 在 JourneyScreen 历史签名中从未被使用，本层不引入。）
 *
 * 方法 `open` 以便测试以子类 fake 替换（ERA 13.2 领域 ports 引入后改接口注入）。
 */
open class JourneyRepository(
    private val portraitRepository: PortraitRepository,
    private val syncStateRepository: SyncStateRepository,
    featureFlagRepository: FeatureFlagRepository,
    private val aiNarrativeService: AiNarrativeService,
    private val contextRetriever: EchoContextRetriever,
    private val preferences: AppPreferences,
    private val appContext: Context,
    private val hasIntelligence: () -> Boolean = { false },
    private val presenceStateSource: com.yunjue.echo.mind.ports.PresenceStateSource,
    private val journeyMemory: JourneyMemoryPort,
    private val memoryRepository: com.yunjue.echo.mind.data.MemoryRepository,
) : JourneyPort {

    /** Provider 是否配置（§24 intelligenceAvailability 注入 UI state）。 */
    override fun intelligenceAvailable(): Boolean = hasIntelligence()

    /** 被动感知 consent 流。 */
    override val consentFlow: Flow<Boolean> = syncStateRepository.passiveSensingConsentFlow()

    /** consent + 本地模式/租户 flag 联合判定（与感知服务门控同构；
     *  ERA 32 R20：本地模式不依赖远端 flag，无网首启不再误报「已关闭或权限被撤」）。 */
    override val permissionEnabledFlow: Flow<Boolean> =
        combine(consentFlow, featureFlagRepository.featureFlagsFlow, preferences.localModeFlow) { consent, flags, localMode ->
            resolveSensingPermissionEnabled(consent, localMode, flags["passive_sensing_enabled"])
        }

    /** 画像时间线（窗口天数由 ViewModel 按尺度决定）。 */
    override fun timeline(days: Int): StateFlow<PortraitTimelineUiState> =
        portraitRepository.observePortraits(days)

    /** 刷新画像窗口。 */
    override suspend fun refresh(days: Int) {
        portraitRepository.refreshPortraits(days)
    }

    /** 运行时快照：能力状态 + 基线状态 + 本地采集/同步时间。 */
    override suspend fun runtimeSnapshot(consent: Boolean): JourneyRuntimeSnapshot = withContext(Dispatchers.IO) {
        val caps = SensingCapability.entries.associateWith {
            capabilityState(appContext, it, consent)
        }
        val baseline = runCatching { portraitRepository.fetchBaselineStatus() }.getOrNull()
        val collectedAt = syncStateRepository.lastCollectionTimestamp()
        val syncedAt = syncStateRepository.lastSyncTimestamp()
        JourneyRuntimeSnapshot(
            availability = PortraitAvailability(
                baselineStatus = baseline?.status ?: "UNKNOWN",
                baselineDays = baseline?.baselineDays ?: 0,
                coverage = baseline?.todayCoverage?.toFloat() ?: 0f,
                missingSources = missingSourcesFromCapabilities(caps),
                lastCollectedAt = collectedAt,
                lastSyncedAt = syncedAt,
                materializationStatus = "none",
                capabilities = caps.values.toList()
            ),
            diagnostics = SensingDiagnostics(
                capabilities = caps,
                sensingActive = consent,
                lastCollectionAt = collectedAt,
                consecutivePersistenceFailures = syncStateRepository.consecutivePersistenceFailures(),
                pendingUploadCount = syncStateRepository.pendingUploadCount()
            )
        )
    }

    /**
     * 长期叙事（FIND_LONGITUDINAL_PATTERN 证据检索 + AI 叙事 → 确定性综述 fallback）。
     * 上下文例外（CONTEXT_EXCEPTIONS）一并返回，进入 JourneyUiState.exceptions。
     */
    override suspend fun narrativeFor(portraits: List<DailyPortraitDto>): JourneyNarrative {
        val evidence = contextRetriever.retrieve(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN)
        val result = aiNarrativeService.longitudinalNarrative(
            evidence = evidence,
            // ERA 31 R19：确定性 fallback 说成人话（journeyNaturalSummary），指标行留给 Evidence Layer
            deterministicText = journeyNaturalSummary(portraits),
        )
        val exceptions = evidence
            .filter { it.category == DataSourceCategory.CONTEXT_EXCEPTIONS }
            .map { it.text }
        return JourneyNarrative(result = result, contextExceptions = exceptions)
    }

    /** 画像反馈（读；写入口在 ECHO 页，Journey 只展示标记）。 */
    override fun feedback(date: String): Boolean? = portraitRepository.portraitFeedback(date)

    /** Journey 视觉种子：userId 稳定派生（与 Identity Genome 同源，跨天视觉血缘）。 */
    override fun journeySeed(): Long = preferences.userId.fold(0L) { acc, c -> acc * 31L + c.code }

    // ===== ERA 16 §83 — Journey 长期记忆（Canonical Daily State） =====

    /** 已落盘的 Canonical Daily State（按日期升序）。 */
    override val canonicalDays: Flow<List<JourneyCanonicalDay>> = journeyMemory.canonicalDays

    /**
     * §83 — 把今天的 ECHO 视觉事实落盘为 Canonical Daily State。
     * 无当前 Presence 状态 → no-op（不编造）；同日重复调用 = 幂等覆盖。
     */
    override suspend fun snapshotToday() {
        val state = presenceStateSource.state.first() ?: return
        val today = java.time.LocalDate.now().toString()
        journeyMemory.snapshot(
            buildCanonicalDay(
                date = today,
                state = state,
                keyEvidenceIds = listOf("portrait:$today"),
                createdAtEpochMs = System.currentTimeMillis(),
            )
        )
    }

    /** 用户自述特殊日期（date → kind，§78；无日期信息的不进入时间线）。 */
    override suspend fun contextExceptions(): Map<String, String> = memoryRepository.contextExceptions()
}
