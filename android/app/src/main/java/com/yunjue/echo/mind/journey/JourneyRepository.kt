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
import com.yunjue.echo.mind.model.portraitStabilitySummary
import com.yunjue.echo.mind.sensing.SensingCapability
import com.yunjue.echo.mind.sensing.capabilityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
) : JourneyPort {

    /** Provider 是否配置（§24 intelligenceAvailability 注入 UI state）。 */
    override fun intelligenceAvailable(): Boolean = hasIntelligence()

    /** 被动感知 consent 流。 */
    override val consentFlow: Flow<Boolean> = syncStateRepository.passiveSensingConsentFlow()

    /** consent + 租户 flag 联合判定（任一关闭 → permission_disabled 态；§28 由 ViewModel 注入 UI state）。 */
    override val permissionEnabledFlow: Flow<Boolean> =
        combine(consentFlow, featureFlagRepository.featureFlagsFlow) { consent, flags ->
            consent && (flags["passive_sensing_enabled"] ?: false)
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
            deterministicText = portraitStabilitySummary(portraits),
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
}
