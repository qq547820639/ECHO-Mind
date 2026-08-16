package com.yunjue.echo.mind.di

import android.content.Context
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.PassiveSensingPrefs
import com.yunjue.echo.mind.data.ApiClient
import com.yunjue.echo.mind.data.ConsentRepository
import com.yunjue.echo.mind.data.DeterministicPersonalAnswerProvider
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
import com.yunjue.echo.mind.journey.JourneyMemoryRepository
import com.yunjue.echo.mind.journey.JourneyRepository
import com.yunjue.echo.mind.openDatabase
import com.yunjue.echo.mind.presence.EchoStateStore
import com.yunjue.echo.mind.security.AndroidKeystoreFieldCipher
import com.yunjue.echo.mind.security.AndroidKeystoreKeyProvider
import com.yunjue.echo.mind.security.KeystoreKeyProvider
import com.yunjue.echo.mind.security.PreferencesDatabaseSecretStorage

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
class CoreContainer(
    context: Context,
    /** 测试注入缝：按 alias 后缀构造 KeystoreKeyProvider（默认生产实现）。 */
    keyProviderFactory: (suffix: String) -> KeystoreKeyProvider = { AndroidKeystoreKeyProvider(it) },
) {
    val applicationContext: Context = context.applicationContext

    private val secretPrefs = context.getSharedPreferences(AppPreferences.PREFS_FILE, Context.MODE_PRIVATE)
    private val secretStorage = PreferencesDatabaseSecretStorage(secretPrefs)

    /**
     * 生产字段加密：AndroidKeystore fail-closed（Keystore 不可用即抛异常，绝不降级）。
     * ERA 17 §89/§90：DB 口令 = HKDF(受保护随机 secret)；字段/DB 密钥分离（独立 alias）。
     *
     * ERA 32 R16 自愈（真机缺陷修复）：主 alias 键在设备上不可用（OEM 卸载残留旧签名
     * Keystore 条目等）且本机**尚无受保护秘密**（全新安装，无数据可孤儿化）时，
     * 换 per-install 后缀 alias 重建密钥并持久化后缀——后续启动直接用 fallback alias；
     * 已有受保护秘密 → 保持 fail-closed（保护既有数据，绝不换钥丢库）。
     */
    val cipher: AndroidKeystoreFieldCipher
    val database: EchoDatabase

    init {
        val persistedSuffix = secretPrefs.getString(KEY_DB_ALIAS_SUFFIX, null)
        val resolved = com.yunjue.echo.mind.security.resolveCipher(
            persistedSuffix = persistedSuffix,
            build = { suffix ->
                AndroidKeystoreFieldCipher(keyProviderFactory(suffix), secretStorage)
            },
            hasWrappedSecret = { secretStorage.readWrappedSecret() != null },
            newSuffix = {
                "-r" + java.security.SecureRandom().let { rng ->
                    ByteArray(8).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                }
            },
            persistSuffix = { secretPrefs.edit().putString(KEY_DB_ALIAS_SUFFIX, it).apply() },
        )
        cipher = resolved
        database = openDatabase(context, resolved)
    }

    val passiveSensingPrefs = PassiveSensingPrefs(context)
    val preferences = AppPreferences(context, cipher, passiveSensingPrefs)

    val apiClient = ApiClient(tokenProvider = { preferences.accessToken })

    /** 跨域共享 outbox 原语（本地模式不写入；订阅后自动恢复正常上行）。 */
    val outbox = Outbox(database, cipher, localModeProvider = { preferences.localMode })

    val featureFlagRepository = FeatureFlagRepository(preferences, apiClient)
    val syncStateRepository = SyncStateRepository(database, preferences)
    val onboardingRepository = OnboardingRepository(database.portraitDao(), preferences, apiClient)
    val escalationRepository = EscalationRepository(database, cipher, outbox, preferences, apiClient)

    companion object {
        /** ERA 32 R16：fallback alias 后缀的 prefs 键（非敏感；只是别名片段）。 */
        const val KEY_DB_ALIAS_SUFFIX = "db_secret_alias_suffix"
    }
}

/** Observation：端侧画像 Ground Truth + 感知/同意（不依赖 intelligence——永久边界）。 */
class ObservationContainer(core: CoreContainer) {
    val sensingRepository = SensingRepository(core.database, core.cipher, core.outbox, core.preferences)
    val consentRepository = ConsentRepository(core.database, core.outbox, core.preferences)
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
    /** ERA 31：人生季节漂移（确定性个人回答的稳定性补充；Presence 未就绪时 0f）。 */
    seasonDriftProvider: () -> Float,
) {
    val providerCredentialStore = ProviderCredentialStore(core.applicationContext, core.cipher)
    val aiProviderManager = AiProviderManager(providerCredentialStore)
    /** ERA 31 BATCH 2：无 Provider/离线时 Ask ECHO 的确定性个人回答（免费核心承诺）。 */
    val deterministicPersonalAnswerProvider = DeterministicPersonalAnswerProvider(
        portraitDataSource = observation.localPortraitDataSource,
        memoryReader = memory.memoryRepository,
        userId = { core.preferences.userId },
        seasonDrift = seasonDriftProvider,
    )
    val aiNarrativeService = AiNarrativeService(
        hasProvider = { aiProviderManager.hasProvider() },
        reason = { request -> aiProviderManager.reason(request) },
        deterministicPersonalAnswer = deterministicPersonalAnswerProvider::answer,
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

/**
 * Journey：Personal Visual Memory System 应用层（ERA 13 §26 + ERA 16 §83）。
 *
 * - [journeyRepository]：跨域应用服务（画像时间线 / 叙事 / 运行时快照 / Canonical 快照写入）；
 * - [journeyMemoryRepository]：Canonical Daily State 的 Room Adapter（实现 JourneyMemoryPort）。
 */
class JourneyContainer(
    core: CoreContainer,
    observation: ObservationContainer,
    presence: PresenceContainer,
    memory: MemoryContainer,
    intelligence: IntelligenceContainer,
) {
    val journeyMemoryRepository = JourneyMemoryRepository(
        db = core.database,
        userId = { core.preferences.userId },
    )
    val journeyRepository = JourneyRepository(
        portraitRepository = observation.portraitRepository,
        syncStateRepository = core.syncStateRepository,
        featureFlagRepository = core.featureFlagRepository,
        aiNarrativeService = intelligence.aiNarrativeService,
        contextRetriever = intelligence.contextRetriever,
        preferences = core.preferences,
        appContext = core.applicationContext,
        hasIntelligence = { intelligence.aiProviderManager.hasProvider() },
        presenceStateSource = presence.presenceRepository,
        journeyMemory = journeyMemoryRepository,
        memoryRepository = memory.memoryRepository,
    )
}
