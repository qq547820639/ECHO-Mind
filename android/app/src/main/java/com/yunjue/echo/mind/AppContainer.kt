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
import com.yunjue.echo.mind.data.LegacyInputRepository
import com.yunjue.echo.mind.data.LocalDataRights
import com.yunjue.echo.mind.data.LocalPortraitDataSource
import com.yunjue.echo.mind.data.NarrativeProfileRepository
import com.yunjue.echo.mind.data.OnboardingRepository
import com.yunjue.echo.mind.data.PortraitRepository
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

private val MIGRATION_1_2 = object : Migration(1, 2) {
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
private val MIGRATION_2_3 = object : Migration(2, 3) {
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

class AppContainer(context: Context) {
    /** 生产字段加密：AndroidKeystore fail-closed（Keystore 不可用即抛异常，绝不降级）。 */
    val cipher: FieldCipher = AndroidKeystoreFieldCipher()
    val passiveSensingPrefs = PassiveSensingPrefs(context)
    val preferences = AppPreferences(context, cipher, passiveSensingPrefs)

    /**
     * SQLCipher 全库加密数据库（Phase 3.1 Privacy Fail-Closed）。
     *
     * 生产语义：SQLCipher native lib 加载失败 → **fail closed**（抛 IllegalStateException，
     * 不创建任何明文敏感数据库）。禁止 `runCatching{...}.getOrElse{普通Room}` 静默回退。
     *
     * JVM/Robolectric 测试需要普通 Room 时：使用显式 **Test Database Factory**
     * （测试内 `Room.inMemoryDatabaseBuilder(...)`，见 DatabaseMigrationTest 等），
     * 与生产路径完全分离；本容器不提供测试降级。
     */
    val database: EchoDatabase = runCatching { net.sqlcipher.database.SQLiteDatabase.loadLibs(context) }
        .map {
            Room.databaseBuilder(context, EchoDatabase::class.java, "echo-mind.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8
                )
                // SQLCipher 全库加密：口令由 Android Keystore 派生，不硬编码
                .openHelperFactory(SupportFactory(cipher.deriveDatabasePassphrase()))
                .build()
        }
        .getOrElse {
            // Phase 3.1：SQLCipher 不可用 → fail closed，绝不静默回退明文 Room。
            throw IllegalStateException(
                "SQLCipher native library load failed in production: ${it.javaClass.simpleName}: ${it.message}",
                it,
            )
        }
    val apiClient = ApiClient(tokenProvider = { preferences.accessToken })

    // 跨域共享 outbox 原语（bounded-context 拆分，Step 1）。
    // v0.7 本地优先架构：本地模式（未订阅）数据仅保存在本机，outbox 不写入
    // （SyncWorker 亦静默，双保险）；订阅后自动恢复正常上行。
    val outbox = Outbox(database, cipher, localModeProvider = { preferences.localMode })

    // bounded-context 仓库（Step 1–3）。
    val featureFlagRepository = FeatureFlagRepository(preferences, apiClient)
    val syncStateRepository = SyncStateRepository(database, preferences)
    val legacyInputRepository = LegacyInputRepository(database, cipher)
    val consentRepository = ConsentRepository(outbox, preferences)
    val sensingRepository = SensingRepository(database, cipher, outbox, preferences)
    val skillRepository = SkillRepository(database, outbox, preferences, apiClient)
    val escalationRepository = EscalationRepository(database, cipher, outbox, preferences, apiClient)
    val onboardingRepository = OnboardingRepository(database.portraitDao(), preferences, apiClient)
    // v0.7 本地优先：端侧画像引擎数据源（本地模式 + 离线回退共用）
    val localPortraitDataSource = LocalPortraitDataSource(database)
    val portraitRepository = PortraitRepository(database, preferences, apiClient, outbox, localPortraitDataSource)
    // v0.7 本地优先：本地数据权利（本地模式导出/删除，数据不出设备）
    val localDataRights = LocalDataRights(database, cipher)
    val narrativeProfileRepository = NarrativeProfileRepository(preferences, apiClient)

    /** v0.6.1（P0-4）：Skill Active Session 统一协调器（进程内单例）。 */
    val skillSessionCoordinator = com.yunjue.echo.mind.ui.SkillSessionCoordinator(skillRepository)

    /** 各 Collector 工厂：使用 applicationContext 避免泄漏 Activity。 */
    fun newSensorCollector(context: Context): SensorCollector =
        SensorCollector(context.applicationContext, newSensingEventHub())
    fun newScreenCollector(context: Context): ScreenCollector =
        ScreenCollector(context.applicationContext, newSensingEventHub())
    fun newAppActivityCollector(context: Context): AppActivityCollector =
        AppActivityCollector(context.applicationContext, newSensingEventHub())
    /** 麦克风采集器工厂（注入 passiveSensingPrefs 以读取 micEnabled 开关）。 */
    fun newMicCollector(context: Context): MicCollector =
        MicCollector(context.applicationContext, passiveSensingPrefs)

    /** 进程内共享事件聚合层单例工厂。 */
    fun newSensingEventHub(): SensingEventHub = SensingEventHub.getInstance()

    /** 5 分钟窗口调度器工厂（可选注入麦克风采集器）。 */
    fun newSensingWindowScheduler(micCollector: MicCollector? = null): SensingWindowScheduler =
        SensingWindowScheduler(newSensingEventHub(), micCollector = micCollector)
}
