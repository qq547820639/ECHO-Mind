package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.database.*
import com.yunjue.echo.mind.sensing.AppActivityCollector
import com.yunjue.echo.mind.sensing.MicCollector
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensingWindowScheduler
import com.yunjue.echo.mind.sensing.SensorCollector
import com.yunjue.echo.mind.security.AndroidKeystoreFieldCipher
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class AppContainer(context: Context) {
    /** v3 §41：Application 级上下文（ViewModel/Worker/Service 所有权基础）。 */
    val applicationContext: Context = context.applicationContext

    // ===== ERA 13.3 §43/§44：Root = Application-wide composition root，只组合六容器 =====
    // 构造职责归各领域容器（di/EchoContainers.kt）；Root 不构造任何领域对象。
    val core = com.yunjue.echo.mind.di.CoreContainer(context)
    val observation = com.yunjue.echo.mind.di.ObservationContainer(core)
    val presence = com.yunjue.echo.mind.di.PresenceContainer(core, observation)
    val memory = com.yunjue.echo.mind.di.MemoryContainer(core)
    val intelligence = com.yunjue.echo.mind.di.IntelligenceContainer(
        core, observation, memory,
        seasonDriftProvider = { presence.echoStateStore.state.value?.lifeSeason?.drift ?: 0f },
    )
    val actions = com.yunjue.echo.mind.di.ActionContainer(core)
    /** ERA 13/16：Journey 应用层容器（跨 observation/presence/intelligence/memory/core）。 */
    val journey = com.yunjue.echo.mind.di.JourneyContainer(core, observation, presence, memory, intelligence)

    // ===== 跨域编排（composition root 职责） =====
    /** v2 §13：Echo Runtime 协调器（六态/Presence/Provider 统一广播）。 */
    val echoRuntimeCoordinator = com.yunjue.echo.mind.runtime.EchoRuntimeCoordinator(
        appContext = core.applicationContext,
        preferences = core.preferences,
        passiveSensingPrefs = core.passiveSensingPrefs,
        presenceRepository = presence.presenceRepository,
        aiProviderManager = intelligence.aiProviderManager,
    )
    /** ERA 13 §26：Journey Application Layer（由 JourneyContainer 持有构造职责）。 */
    val journeyRepository = journey.journeyRepository
    /** ERA 16 §83：Journey Canonical Daily State 存储（JourneyContainer 持有）。 */
    val journeyMemoryRepository = journey.journeyMemoryRepository
    /** v0.6.1（P0-4）：Skill Active Session 统一协调器（进程内单例）。 */
    val skillSessionCoordinator = com.yunjue.echo.mind.ui.SkillSessionCoordinator(actions.skillRepository)

    // ===== 兼容访问器（新代码走领域入口 core/observation/...；旧调用点逐步迁移） =====
    val cipher get() = core.cipher
    val passiveSensingPrefs get() = core.passiveSensingPrefs
    val preferences get() = core.preferences
    val database get() = core.database
    val apiClient get() = core.apiClient
    val outbox get() = core.outbox
    val featureFlagRepository get() = core.featureFlagRepository
    val syncStateRepository get() = core.syncStateRepository
    val onboardingRepository get() = core.onboardingRepository
    val escalationRepository get() = core.escalationRepository
    val sensingRepository get() = observation.sensingRepository
    val consentRepository get() = observation.consentRepository
    val localPortraitDataSource get() = observation.localPortraitDataSource
    val portraitRepository get() = observation.portraitRepository
    val localDataRights get() = observation.localDataRights
    val messageRepository get() = observation.messageRepository
    val echoStateStore get() = presence.echoStateStore
    val presenceRepository get() = presence.presenceRepository
    val memoryRepository get() = memory.memoryRepository
    val providerCredentialStore get() = intelligence.providerCredentialStore
    val aiProviderManager get() = intelligence.aiProviderManager
    val aiNarrativeService get() = intelligence.aiNarrativeService
    val contextRetriever get() = intelligence.contextRetriever
    val skillRepository get() = actions.skillRepository

    // ===== Transient 工厂（§45：每次新建，非 Application scoped） =====
    /** 各 Collector 工厂：使用 applicationContext 避免泄漏 Activity。 */
    fun newSensorCollector(context: Context): SensorCollector =
        SensorCollector(context.applicationContext, newSensingEventHub())
    fun newScreenCollector(context: Context): ScreenCollector =
        ScreenCollector(context.applicationContext, newSensingEventHub())
    fun newAppActivityCollector(context: Context): AppActivityCollector =
        AppActivityCollector(context.applicationContext, newSensingEventHub())
    /** 麦克风采集器工厂（注入 passiveSensingPrefs 以读取 micEnabled 开关）。 */
    fun newMicCollector(context: Context): MicCollector =
        MicCollector(context.applicationContext, core.passiveSensingPrefs)

    /** 进程内共享事件聚合层单例工厂。 */
    fun newSensingEventHub(): SensingEventHub = SensingEventHub.getInstance()

    /** 5 分钟窗口调度器工厂（可选注入麦克风采集器）。 */
    fun newSensingWindowScheduler(micCollector: MicCollector? = null): SensingWindowScheduler =
        SensingWindowScheduler(newSensingEventHub(), micCollector = micCollector)
}

/**
 * SQLCipher 加密库打开（ERA 17 §88-§91 KDF 正式迁移）：
 *
 * 1. 新路径：HKDF-SHA256(每安装随机 256-bit 秘密，Keystore 包装) —— §89 标准 KDF，
 *    无固定 IV、无 SHA-256-of-ciphertext；
 * 2. 旧库（v0.8/0.9 固定 IV 派生口令）：legacy 派生 → 打开 → 生成/存储新受保护秘密
 *    → PRAGMA rekey → verify → 标记迁移 → 退役 ancient v1 alias（§91 全链）；
 * 3. 已迁移库：legacy 派生退役（fail-closed，绝不回退）；
 * 4. Keystore 不可用 / 口令全部不可用 → 抛原异常（fail-closed，绝不回退明文）。
 *
 * 编排逻辑（纯决策，JVM 可测）在 :core:security [DatabaseOpenOrchestrator]；
 * 本函数只做 Room/SQLCipher 适配。
 */
internal fun openDatabase(context: Context, cipher: AndroidKeystoreFieldCipher): EchoDatabase {
    // ERA 32 R19（真机根因修复）：net.zetetic:sqlcipher-android 4.17.0 不再自动加载原生库
    // （旧 net.sqlcipher 时代的 loadLibs 已随新 artifact 移除）——必须在任何建库/查库前
    // 显式 System.loadLibrary("sqlcipher")，否则首个 Room 写（onboarding 同意落库）在
    // nativeOpen 边界抛 UnsatisfiedLinkError（nubia NX809J / Android 16 实测复现）。
    // 加载失败会沿容器构建链冒泡到 MainActivity 兜底画面（fail-closed，不静默降级）。
    System.loadLibrary("sqlcipher")
    fun build(passphrase: ByteArray): EchoDatabase =
        Room.databaseBuilder(context, EchoDatabase::class.java, "echo-mind.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
            )
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
            .also { db ->
                // ERA 32 R24：强制立即打开——Room 惰性打开会让错钥 SQLiteException
                // 逃逸到首次查询（在 DatabaseOpenOrchestrator 的 try/catch 之外），
                // 使 §91 legacy 迁移链整体失效（v0.8/0.9 老库升级将裸崩且永不迁移）。
                db.openHelper.writableDatabase
            }

    return com.yunjue.echo.mind.security.DatabaseOpenOrchestrator.open(
        inputs = com.yunjue.echo.mind.security.DatabaseOpenInputs(
            deriveNew = { cipher.deriveDatabasePassphrase() },
            deriveLegacy = {
                cipher.deriveLegacyDatabasePassphrase()
                    ?: cipher.deriveAncientDatabasePassphrase()
            },
            isMigrated = { cipher.isDatabaseSecretMigrated() },
            isWrongKey = { it is android.database.sqlite.SQLiteException },
        ),
        actions = com.yunjue.echo.mind.security.DatabaseMigrationActions(
            rotateSecret = { cipher.rotateDatabaseSecret() },
            markMigrated = { cipher.markDatabaseSecretMigrated() },
            retireAncient = { cipher.retireAncientAlias() },
        ),
        io = com.yunjue.echo.mind.security.DatabaseIo(
            build = { passphrase -> build(passphrase) },
            rekey = { db, fresh ->
                db.openHelper.writableDatabase.execSQL(AndroidKeystoreFieldCipher.rekeyPragma(fresh))
            },
            verify = { db ->
                db.openHelper.writableDatabase
                    .query("SELECT count(*) FROM sqlite_master")
                    .use { cursor -> cursor.moveToFirst() }
            },
        ),
    )
}
