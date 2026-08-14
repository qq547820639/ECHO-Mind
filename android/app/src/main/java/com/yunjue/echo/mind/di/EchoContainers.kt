package com.yunjue.echo.mind.di

import android.content.Context
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
import com.yunjue.echo.mind.openDatabase
import com.yunjue.echo.mind.presence.EchoStateStore
import com.yunjue.echo.mind.security.AndroidKeystoreFieldCipher

/**
 * ERA 13.3 §43/§44 — 子容器自持构造职责（Real DI Ownership）。
 *
 * - 每个 Container 在领域内构建自己的对象图；Root（AppContainer）只组合六容器 + 跨域编排；
 * - 所有权 Scope（§45）：全部 **Application scoped**（EchoMindApplication lazy 单例）；
 *   Wallpaper/Dream 只读 Presence 快照，不初始化容器；
 *   Worker 经 Application container 访问（Worker scoped 生命周期由 WorkManager 管理）；
 *   Collector/Scheduler 为 **Transient**（Root 工厂每次新建）；
 *   ViewModel 为 **ViewModel scoped**（viewModelFactory 构造）。
 * - 依赖方向：observation 不依赖 intelligence；presence 渲染不依赖 Room；
 *   memory 不依赖具体 Provider；intelligence 依赖 observation/memory 端口。
 */

/** Core：安全/数据库/同步/基础仓库（基础设施，Application scoped）。 */
class CoreContainer(context: Context) {
    val applicationContext: Context = context.applicationContext

    /** 生产字段加密：AndroidKeystore fail-closed（Keystore 不可用即抛异常，绝不降级）。 */
    val cipher: AndroidKeystoreFieldCipher = AndroidKeystoreFieldCipher()
    val passiveSensingPrefs = PassiveSensingPrefs(context)
    val preferences = AppPreferences(context, cipher, passiveSensingPrefs)

    /** SQLCipher 全库加密数据库（fail-closed：native 加载失败抛异常，绝不回退明文 Room）。 */
    val database: EchoDatabase = openDatabase(context, cipher)

    val apiClient = ApiClient(tokenProvider = { preferences.accessToken })

    /** 跨域共享 outbox 原语（本地模式不写入；订阅后自动恢复正常上行）。 */
    val outbox = Outbox(database, cipher, localModeProvider = { preferences.localMode })

    val featureFlagRepository = FeatureFlagRepository(preferences, apiClient)
    val syncStateRepository = SyncStateRepository(database, preferences)
    val onboardingRepository = OnboardingRepository(database.portraitDao(), preferences, apiClient)
    val escalationRepository = EscalationRepository(database, cipher, outbox, preferences, apiClient)
}

/** Observation：端侧画像 Ground Truth + 感知/同意（不依赖 intelligence——永久边界）。 */
class ObservationContainer(core: CoreContainer) {
    val sensingRepository = SensingRepository(core.database, core.cipher, core.outbox, core.preferences)
    val consentRepository = ConsentRepository(core.outbox, core.preferences)
    val localPortraitDataSource = LocalPortraitDataSource(core.database)
    val portraitRepository = PortraitRepository(
        core.database, core.preferences, core.apiClient, core.outbox, localPortraitDataSource
    )
    val localDataRights = LocalDataRights(core.database, core.cipher)
    val messageRepository = MessageRepository(core.preferences, core.apiClient, localPortraitDataSource)
}

/** Presence：单一 Current ECHO State（App/Wallpaper/Dream 共享）。 */
class PresenceContainer(core: CoreContainer, observation: ObservationContainer) {
    val echoStateStore = EchoStateStore()
    val presenceRepository = PresenceRepository(
        dataSource = observation.localPortraitDataSource,
        preferences = core.preferences,
        passiveSensingPrefs = core.passiveSensingPrefs,
        appContext = core.applicationContext,
        store = echoStateStore,
    )
}

/** Memory：EchoMemory（七类记忆 + 生命周期；Memory ≠ 聊天记录）。 */
class MemoryContainer(core: CoreContainer) {
    val memoryRepository = MemoryRepository(core.database, core.preferences)
}

/** Intelligence：BYOM Provider + 叙事编排 + Context 检索（LLM Provider ≠ ECHO）。 */
class IntelligenceContainer(
    core: CoreContainer,
    observation: ObservationContainer,
    memory: MemoryContainer,
) {
    val providerCredentialStore = ProviderCredentialStore(core.applicationContext, core.cipher)
    val aiProviderManager = AiProviderManager(providerCredentialStore)
    val aiNarrativeService = AiNarrativeService(
        hasProvider = { aiProviderManager.hasProvider() },
        reason = { request -> aiProviderManager.reason(request) },
    )
    val contextRetriever = EchoContextRetriever(
        observationSource = observation.localPortraitDataSource,
        memoryReader = memory.memoryRepository,
        userId = { core.preferences.userId },
    )
}

/** Actions：行动内容源（SkillRepository 为 Adapter；§41 未来 ActionContentSource 端口在此落地）。 */
class ActionContainer(core: CoreContainer) {
    val skillRepository = SkillRepository(core.database, core.outbox, core.preferences, core.apiClient)
}
