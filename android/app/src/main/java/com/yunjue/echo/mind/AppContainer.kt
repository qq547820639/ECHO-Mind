package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
import com.yunjue.echo.mind.sensing.AppActivityCollector
import com.yunjue.echo.mind.sensing.MicCollector
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensingWindowScheduler
import com.yunjue.echo.mind.sensing.SensorCollector
import com.yunjue.echo.mind.security.AndroidKeystoreFieldCipher
import com.yunjue.echo.mind.security.FieldCipher
import net.sqlcipher.database.SupportFactory

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS journal_entries (
            eventId TEXT NOT NULL PRIMARY KEY,
            logicalId TEXT NOT NULL,
            revision INTEGER NOT NULL,
            bodyCiphertext TEXT,
            tagsJson TEXT NOT NULL,
            clientTimeEpochMs INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS questionnaire_results (
            eventId TEXT NOT NULL PRIMARY KEY,
            instrument TEXT NOT NULL,
            version TEXT NOT NULL,
            answersJson TEXT NOT NULL,
            score INTEGER NOT NULL,
            urgentItem INTEGER NOT NULL,
            clientTimeEpochMs INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS practice_completions (
            eventId TEXT NOT NULL PRIMARY KEY,
            practiceId TEXT NOT NULL,
            contentVersion TEXT NOT NULL,
            status TEXT NOT NULL,
            durationSeconds INTEGER NOT NULL,
            clientTimeEpochMs INTEGER NOT NULL
        )""")
    }
}

/**
 * v2 → v3 迁移：新增 consents / sensor_samples / feature_vectors 三张表。
 * ConsentEntity 由 T02 定义但未注册，此处统一建表。
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 同意记录表
        db.execSQL("""CREATE TABLE IF NOT EXISTS consents (
            eventId TEXT NOT NULL PRIMARY KEY,
            userId TEXT NOT NULL,
            consentType TEXT NOT NULL,
            version TEXT NOT NULL,
            granted INTEGER NOT NULL,
            grantedAt INTEGER NOT NULL,
            evidenceHash TEXT NOT NULL
        )""")
        // 原始信号样本缓冲表（仅端侧，不上云）
        db.execSQL("""CREATE TABLE IF NOT EXISTS sensor_samples (
            id TEXT NOT NULL PRIMARY KEY,
            userId TEXT NOT NULL,
            source TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            value TEXT NOT NULL,
            createdAt INTEGER NOT NULL
        )""")
        // 派生特征本地缓存表
        db.execSQL("""CREATE TABLE IF NOT EXISTS feature_vectors (
            id TEXT NOT NULL PRIMARY KEY,
            userId TEXT NOT NULL,
            schemaVersion TEXT NOT NULL,
            source TEXT NOT NULL,
            windowStart INTEGER NOT NULL,
            windowEnd INTEGER NOT NULL,
            summaryCiphertext TEXT NOT NULL,
            vector TEXT NOT NULL,
            synced INTEGER NOT NULL DEFAULT 0,
            createdAt INTEGER NOT NULL
        )""")
    }
}

/**
 * v3 → v4 迁移（02b §3.1）：
 * DROP sensor_samples 表（移除违反"原始数据不落盘"承诺的死表）；
 * feature_vectors / consents / outbox_events 等保持。
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS sensor_samples")
    }
}

/**
 * v4 → v5 迁移（T02）：新增 active_skill_sessions 表（Skill 执行会话持久化）。
 * 纯增量 CREATE TABLE，无数据改写；旧表不动。
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS active_skill_sessions (
                sessionId TEXT NOT NULL PRIMARY KEY,
                skillId TEXT NOT NULL,
                skillVersion INTEGER NOT NULL,
                skillRevision INTEGER NOT NULL,
                actionType TEXT NOT NULL,
                status TEXT NOT NULL,
                currentStep INTEGER NOT NULL,
                startedAt INTEGER NOT NULL,
                accumulatedActiveMs INTEGER NOT NULL,
                segmentStartedAtMs INTEGER,
                pausedAt INTEGER,
                updatedAt INTEGER NOT NULL
            )"""
        )
    }
}

/**
 * v5 → v6 迁移（v0.6.1，P0-2）：新增 escalation_requests 表（人工支持客户端闭环）。
 * 纯增量 CREATE TABLE，无数据改写。
 */
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS escalation_requests (
                eventId TEXT NOT NULL PRIMARY KEY,
                userId TEXT NOT NULL,
                trigger TEXT NOT NULL,
                evidenceSummaryCiphertext TEXT NOT NULL,
                status TEXT NOT NULL,
                serverEscalationId TEXT,
                serverStatusJson TEXT,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                outboxSynced INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }
}

/**
 * v6 → v7 迁移（Milestone H）：新增 portrait_daily 表（每日画像离线缓存）。
 * 纯增量 CREATE TABLE IF NOT EXISTS（幂等），无数据改写；旧表不动。
 * 列名与 [DailyPortraitEntity] 字段一致（Room 按列名匹配）。
 */
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS portrait_daily (
                id TEXT NOT NULL PRIMARY KEY,
                localDate TEXT NOT NULL,
                userId TEXT NOT NULL,
                status TEXT NOT NULL,
                confidence TEXT NOT NULL,
                headlineJson TEXT NOT NULL,
                summary TEXT NOT NULL,
                dimensionsJson TEXT NOT NULL,
                factsJson TEXT NOT NULL,
                coverageJson TEXT,
                timezoneUsed TEXT,
                fetchedAt INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_portrait_daily_localDate ON portrait_daily (localDate)")
    }
}

/**
 * v7 → v8 迁移（离线画像引擎）：feature_vectors 加 sourcesPresentJson 可空列。
 * 纯加列（ALTER TABLE ADD COLUMN，可空），旧行值为 NULL（端侧回退按 source 单元素集合解释）；
 * 无数据改写，幂等（列已存在时跳过）。
 */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val columns = db.query("PRAGMA table_info(feature_vectors)").use { cursor ->
            val names = mutableSetOf<String>()
            val idx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) names.add(cursor.getString(idx))
            names
        }
        if ("sourcesPresentJson" !in columns) {
            db.execSQL("ALTER TABLE feature_vectors ADD COLUMN sourcesPresentJson TEXT")
        }
    }
}

/**
 * v8 → v9 迁移（ERA 6 EchoMemory）：新增 echo_memories 表（纯建表，无数据改写）。
 */
internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS echo_memories (
                id TEXT NOT NULL PRIMARY KEY,
                userId TEXT NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT NOT NULL,
                confidence REAL NOT NULL,
                createdAt INTEGER NOT NULL,
                lastConfirmedAt INTEGER NOT NULL,
                importance INTEGER NOT NULL,
                retentionClass TEXT NOT NULL,
                provenance TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }
}

/**
 * v9 → v10 迁移（ERA 16 Journey 长期记忆）：新增 journey_canonical_days 表
 * （Canonical Daily State 最小事实快照，payload 为编解码字符串，无 bitmap）。
 * 纯增量 CREATE TABLE IF NOT EXISTS（幂等），无数据改写；旧表不动。
 */
internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS journey_canonical_days (
                id TEXT NOT NULL PRIMARY KEY,
                userId TEXT NOT NULL,
                localDate TEXT NOT NULL,
                payload TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_journey_canonical_days_localDate ON journey_canonical_days (localDate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_journey_canonical_days_userId ON journey_canonical_days (userId)")
    }
}

class AppContainer(context: Context) {
    /** v3 §41：Application 级上下文（ViewModel/Worker/Service 所有权基础）。 */
    val applicationContext: Context = context.applicationContext

    // ===== ERA 13.3 §43/§44：Root = Application-wide composition root，只组合六容器 =====
    // 构造职责归各领域容器（di/EchoContainers.kt）；Root 不构造任何领域对象。
    val core = com.yunjue.echo.mind.di.CoreContainer(context)
    val observation = com.yunjue.echo.mind.di.ObservationContainer(core)
    val presence = com.yunjue.echo.mind.di.PresenceContainer(core, observation)
    val memory = com.yunjue.echo.mind.di.MemoryContainer(core)
    val intelligence = com.yunjue.echo.mind.di.IntelligenceContainer(core, observation, memory)
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
    fun build(passphrase: ByteArray): EchoDatabase =
        Room.databaseBuilder(context, EchoDatabase::class.java, "echo-mind.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
            )
            .openHelperFactory(SupportFactory(passphrase))
            .build()

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
