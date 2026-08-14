package com.yunjue.echo.mind.di

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.PassiveSensingPrefs
import com.yunjue.echo.mind.data.ApiClient
import com.yunjue.echo.mind.data.ConsentRepository
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.EscalationRepository
import com.yunjue.echo.mind.data.FeatureFlagRepository
import com.yunjue.echo.mind.data.LocalDataRights
import com.yunjue.echo.mind.data.LocalPortraitDataSource
import com.yunjue.echo.mind.data.MemoryRepository
import com.yunjue.echo.mind.data.MessageRepository
import com.yunjue.echo.mind.data.OnboardingRepository
import com.yunjue.echo.mind.data.PortraitRepository
import com.yunjue.echo.mind.data.PresenceRepository
import com.yunjue.echo.mind.data.SensingRepository
import com.yunjue.echo.mind.data.SkillRepository
import com.yunjue.echo.mind.data.SyncStateRepository
import com.yunjue.echo.mind.data.outbox.Outbox
import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.AiProviderManager
import com.yunjue.echo.mind.intelligence.EchoContextRetriever
import com.yunjue.echo.mind.intelligence.ProviderCredentialStore
import com.yunjue.echo.mind.presence.EchoStateStore
import com.yunjue.echo.mind.security.AndroidKeystoreFieldCipher

/**
 * v3 §40/§41 — 子容器（组合式，所有权拆分第一步；不引入 DI 框架）。
 *
 * 所有权 Scope：
 * - 全部 **Application scoped**（EchoMindApplication lazy 单例）；
 * - Wallpaper/Dream 只读 Presence 快照，**不**初始化本容器；
 * - Worker 经 Application container 访问（Worker scoped 生命周期由 WorkManager 管理）。
 *
 * 依赖方向（v3 §45-49）：observation 不依赖 intelligence；presence 渲染不依赖 Room；
 * memory 不依赖具体 Provider；intelligence 依赖 observation 接口。
 */

/** Core：安全/数据库/同步/基础仓库（GROUND TRUTH 与基础设施）。 */
class CoreContainer(
    val cipher: AndroidKeystoreFieldCipher,
    val passiveSensingPrefs: PassiveSensingPrefs,
    val preferences: AppPreferences,
    val database: EchoDatabase,
    val outbox: Outbox,
    val apiClient: ApiClient,
    val featureFlagRepository: FeatureFlagRepository,
    val syncStateRepository: SyncStateRepository,
)

/** Sensing：感知/同意/外围能力/支持请求（observation 边界内）。 */
class SensingContainer(
    val consentRepository: ConsentRepository,
    val sensingRepository: SensingRepository,
    val skillRepository: SkillRepository,
    val escalationRepository: EscalationRepository,
    val onboardingRepository: OnboardingRepository,
)

/** Observation：端侧画像 Ground Truth（不依赖 intelligence/affective——永久边界）。 */
class ObservationContainer(
    val localPortraitDataSource: LocalPortraitDataSource,
    val portraitRepository: PortraitRepository,
    val localDataRights: LocalDataRights,
    val messageRepository: MessageRepository,
)

/** Presence：单一 Current ECHO State（App/Wallpaper/Dream 共享）。 */
class PresenceContainer(
    val echoStateStore: EchoStateStore,
    val presenceRepository: PresenceRepository,
)

/** Intelligence：BYOM Provider + 叙事编排 + Context 检索（LLM Provider ≠ ECHO）。 */
class IntelligenceContainer(
    val providerCredentialStore: ProviderCredentialStore,
    val aiProviderManager: AiProviderManager,
    val aiNarrativeService: AiNarrativeService,
    val contextRetriever: EchoContextRetriever,
)

/** Memory：EchoMemory（ECHO 的，不是任何 Provider 的）。 */
class MemoryContainer(
    val memoryRepository: MemoryRepository,
)
