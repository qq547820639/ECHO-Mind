package com.yunjue.echo.mind.me

import com.yunjue.echo.mind.data.EscalationEntity
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.model.CapabilityState
import com.yunjue.echo.mind.model.SensingCapability

/**
 * ERA 13.1 §32 — Me 根页面单一 UI 状态。
 *
 * 根页面只需要：Presence / Intelligence / Memory / Sensing 摘要 + Privacy（Data & Sensing 分节）
 * + Support + About。子领域交互状态在各自 ViewModel（§33–§36）。
 */

/** 同步状态摘要。 */
data class MeSyncStatus(
    val pendingCount: Int = 0,
    val label: String = "",
    val lastCollectionTs: Long = 0L,
    val lastSyncTs: Long = 0L,
    val lastPartialSyncTs: Long? = null,
    val lastPersistenceFailureTs: Long? = null,
    val consecutiveFailures: Int = 0,
)

/** Presence 摘要（壁纸/屏保/视觉偏好）。 */
data class MePresenceSummary(
    val motionLevel: String = "DEFAULT",
    val nightMode: Boolean = false,
    val reduceMotion: Boolean = false,
    val suggestionsEnabled: Boolean = false,
)

/** Intelligence 摘要（provider config 概览）。 */
data class MeIntelligenceSummary(
    val providerConfigured: Boolean = false,
    val model: String? = null,
    val baseUrl: String? = null,
)

/** Memory 摘要（分类计数）。 */
data class MeMemorySummary(
    val total: Int = 0,
    val corrections: Int = 0,
    val uncertain: Int = 0,
    val confirmed: Int = 0,
)

data class MeUiState(
    val message: String? = null,
    val sensingEnabled: Boolean = false,
    val micEnabled: Boolean = false,
    val showSupportConfirm: Boolean = false,
    val sync: MeSyncStatus = MeSyncStatus(),
    val escalations: List<EscalationEntity> = emptyList(),
    val presence: MePresenceSummary = MePresenceSummary(),
    val intelligence: MeIntelligenceSummary = MeIntelligenceSummary(),
    val memory: MeMemorySummary = MeMemorySummary(),
)

/**
 * ERA 13.1 §33 — Data & Sensing 状态。
 * permission truth / sensing runtime / usage access / notification listener /
 * mic opt-in / pause/resume / system repair / last active / 数据权利。
 */
data class DataAndSensingUiState(
    val sensingEnabled: Boolean = false,
    val reEnabling: Boolean = false,
    val micEnabled: Boolean = false,
    val showMicConfirm: Boolean = false,
    val showLocalDeleteConfirm: Boolean = false,
    val eveningReminderEnabled: Boolean = false,
    val capabilityStates: Map<SensingCapability, CapabilityState> = emptyMap(),
    val lastCollectionTs: Long = 0L,
    val lastSyncTs: Long = 0L,
    val lastPartialSyncTs: Long? = null,
    val lastPersistenceFailureTs: Long? = null,
    val consecutiveFailures: Int = 0,
    val syncLabel: String = "",
    val online: Boolean = false,
    val localWindows: Int = 0,
    val localPortraits: Int = 0,
    val localMode: Boolean = true,
    val institutionCode: String = "",
    val userId: String = "",
    val message: String? = null,
)

/**
 * ERA 13.1 §34 — Intelligence Settings 状态。
 */
data class IntelligenceSettingsUiState(
    val providerConfigured: Boolean = false,
    val model: String? = null,
    val baseUrl: String? = null,
    val status: com.yunjue.echo.mind.intelligence.ProviderStatus? = null,
    val statusText: String? = null,
    val testDetail: String? = null,
    val busy: Boolean = false,
    val changeExpanded: Boolean = false,
    val draftBaseUrl: String = "",
    val draftModel: String = "",
    val draftApiKey: String = "",
)

/**
 * ERA 13.1 §35 — Presence Settings 状态。
 */
data class PresenceSettingsUiState(
    val motionLevel: String = "DEFAULT",
    val nightMode: Boolean = false,
    val reduceMotion: Boolean = false,
    val suggestionsEnabled: Boolean = false,
)

/**
 * ERA 13.1 §36 — Memory Management（What ECHO Knows）状态。
 * confirm / edit / forget / pin / filter。
 */
data class MemoryManagementUiState(
    val memories: List<com.yunjue.echo.mind.memory.EchoMemory> = emptyList(),
    /** 当前过滤类别；null = 全部。 */
    val filter: MemoryType? = null,
)

/**
 * ERA 33 — Subscription（可选订阅开通）状态。
 * 业务（激活码验证 / 订阅状态快照）在 SubscriptionViewModel；UI 只渲染。
 */
data class SubscriptionUiState(
    val bindCode: String = "",
    val binding: Boolean = false,
    val bindMessage: String? = null,
    val localMode: Boolean = true,
    val subscriptionExpiresAt: Long? = null,
    val subscriptionExpired: Boolean = false,
)

/** Memory 分类视图（§80：Observed/User-confirmed/Correction/不确定 区分展示）。 */
data class MemoryGroups(
    val corrections: List<com.yunjue.echo.mind.memory.EchoMemory> = emptyList(),
    val uncertain: List<com.yunjue.echo.mind.memory.EchoMemory> = emptyList(),
    val confirmed: List<com.yunjue.echo.mind.memory.EchoMemory> = emptyList(),
)

fun groupMemories(
    memories: List<com.yunjue.echo.mind.memory.EchoMemory>,
    filter: MemoryType?,
): MemoryGroups {
    val filtered = if (filter == null) memories else memories.filter { it.type == filter }
    val corrections = filtered.filter { it.type == MemoryType.CORRECTION }
    val uncertain = filtered.filter { it.confidence < 0.5f && it.type != MemoryType.CORRECTION }
    val confirmed = filtered.filter { it !in corrections && it !in uncertain }
    return MemoryGroups(corrections = corrections, uncertain = uncertain, confirmed = confirmed)
}

/** Me 同步装配输入（detekt LongParameterList 合规分组）。 */
data class MeSyncInputs(
    val networkAvailable: Boolean = true,
    val deadLetterCount: Int = 0,
    val authBlocked: Boolean = false,
    val consentBlocked: Boolean = false,
    val retrying: Boolean = false,
    val lastCollectionTs: Long = 0L,
    val lastSyncTs: Long = 0L,
    val lastPartialSyncTs: Long? = null,
    val lastPersistenceFailureTs: Long? = null,
    val consecutiveFailures: Int = 0,
)

/** MeUiState 装配输入（§32；可测试）。 */
data class MeAssemblyInputs(
    val sensingEnabled: Boolean = false,
    val micEnabled: Boolean = false,
    val pendingCount: Int = 0,
    val escalations: List<EscalationEntity> = emptyList(),
    val memories: List<com.yunjue.echo.mind.memory.EchoMemory> = emptyList(),
    val message: String? = null,
    val showSupportConfirm: Boolean = false,
    val sync: MeSyncInputs = MeSyncInputs(),
    val presence: MePresenceSummary = MePresenceSummary(),
    val storedProvider: com.yunjue.echo.mind.intelligence.ProviderCredentialStore.Stored? = null,
)

/**
 * MeUiState 纯函数装配器（§32；可测试：provider/permission/sensing/mic/memory/support 摘要）。
 */
fun assembleMeUiState(inputs: MeAssemblyInputs): MeUiState {
    val memories = inputs.memories
    val syncState = com.yunjue.echo.mind.data.mapSyncState(
        pendingCount = inputs.pendingCount,
        networkAvailable = inputs.sync.networkAvailable,
        deadLetterCount = inputs.sync.deadLetterCount,
        authBlocked = inputs.sync.authBlocked,
        consentBlocked = inputs.sync.consentBlocked,
        retrying = inputs.sync.retrying,
    )
    val corrections = memories.count { it.type == MemoryType.CORRECTION }
    val uncertain = memories.count { it.confidence < 0.5f && it.type != MemoryType.CORRECTION }
    return MeUiState(
        message = inputs.message,
        sensingEnabled = inputs.sensingEnabled,
        micEnabled = inputs.micEnabled,
        showSupportConfirm = inputs.showSupportConfirm,
        sync = MeSyncStatus(
            pendingCount = inputs.pendingCount,
            label = com.yunjue.echo.mind.data.syncStateText(syncState, inputs.pendingCount),
            lastCollectionTs = inputs.sync.lastCollectionTs,
            lastSyncTs = inputs.sync.lastSyncTs,
            lastPartialSyncTs = inputs.sync.lastPartialSyncTs,
            lastPersistenceFailureTs = inputs.sync.lastPersistenceFailureTs,
            consecutiveFailures = inputs.sync.consecutiveFailures,
        ),
        escalations = inputs.escalations,
        presence = inputs.presence,
        intelligence = MeIntelligenceSummary(
            providerConfigured = inputs.storedProvider != null,
            model = inputs.storedProvider?.model,
            baseUrl = inputs.storedProvider?.baseUrl,
        ),
        memory = MeMemorySummary(
            total = memories.size,
            corrections = corrections,
            uncertain = uncertain,
            confirmed = memories.size - corrections - uncertain,
        ),
    )
}

/** 感知/麦克风装配输入分组。 */
data class SensingUiInputs(
    val enabled: Boolean = false,
    val reEnabling: Boolean = false,
)

data class MicUiInputs(
    val enabled: Boolean = false,
    val showConfirm: Boolean = false,
)

/** 数据权利装配输入分组。 */
data class DataRightsInputs(
    val localWindows: Int = 0,
    val localPortraits: Int = 0,
    val localMode: Boolean = true,
    val institutionCode: String = "",
    val userId: String = "",
)

/** DataAndSensingUiState 装配输入（§33）。 */
data class DataAndSensingAssemblyInputs(
    val sensing: SensingUiInputs = SensingUiInputs(),
    val mic: MicUiInputs = MicUiInputs(),
    val showLocalDeleteConfirm: Boolean = false,
    val eveningReminderEnabled: Boolean = false,
    val capabilityStates: Map<SensingCapability, CapabilityState> = emptyMap(),
    val pendingCount: Int = 0,
    val sync: MeSyncInputs = MeSyncInputs(),
    val rights: DataRightsInputs = DataRightsInputs(),
    val message: String? = null,
)

/**
 * DataAndSensingUiState 纯函数装配器（§33；permission truth 由 VM 以系统真实能力状态传入）。
 */
fun assembleDataAndSensingUiState(inputs: DataAndSensingAssemblyInputs): DataAndSensingUiState {
    val syncState = com.yunjue.echo.mind.data.mapSyncState(
        pendingCount = inputs.pendingCount,
        networkAvailable = inputs.sync.networkAvailable,
        deadLetterCount = inputs.sync.deadLetterCount,
        authBlocked = inputs.sync.authBlocked,
        consentBlocked = inputs.sync.consentBlocked,
        retrying = inputs.sync.retrying,
    )
    return DataAndSensingUiState(
        sensingEnabled = inputs.sensing.enabled,
        reEnabling = inputs.sensing.reEnabling,
        micEnabled = inputs.mic.enabled,
        showMicConfirm = inputs.mic.showConfirm,
        showLocalDeleteConfirm = inputs.showLocalDeleteConfirm,
        eveningReminderEnabled = inputs.eveningReminderEnabled,
        capabilityStates = inputs.capabilityStates,
        lastCollectionTs = inputs.sync.lastCollectionTs,
        lastSyncTs = inputs.sync.lastSyncTs,
        lastPartialSyncTs = inputs.sync.lastPartialSyncTs,
        lastPersistenceFailureTs = inputs.sync.lastPersistenceFailureTs,
        consecutiveFailures = inputs.sync.consecutiveFailures,
        syncLabel = com.yunjue.echo.mind.data.syncStateText(syncState, inputs.pendingCount),
        online = inputs.sync.networkAvailable,
        localWindows = inputs.rights.localWindows,
        localPortraits = inputs.rights.localPortraits,
        localMode = inputs.rights.localMode,
        institutionCode = inputs.rights.institutionCode,
        userId = inputs.rights.userId,
        message = inputs.message,
    )
}
