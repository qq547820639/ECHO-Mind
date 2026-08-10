package com.yunjue.echo.mind

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yunjue.echo.mind.data.ApiClient
import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.LocalRepository
import com.yunjue.echo.mind.sensing.AppActivityCollector
import com.yunjue.echo.mind.sensing.MicCollector
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensingWindowScheduler
import com.yunjue.echo.mind.sensing.SensorCollector
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

class AppContainer(context: Context) {
    val cipher = FieldCipher()
    val passiveSensingPrefs = PassiveSensingPrefs(context)
    val preferences = AppPreferences(context, cipher, passiveSensingPrefs)
    val database = Room.databaseBuilder(context, EchoDatabase::class.java, "echo-mind.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        // SQLCipher 全库加密：口令由 Android Keystore 派生，不硬编码
        .openHelperFactory(SupportFactory(cipher.deriveDatabasePassphrase()))
        .build()
    val repository = LocalRepository(database, cipher, preferences, ApiClient { preferences.accessToken })

    /** v0.6.1（P0-4）：Skill Active Session 统一协调器（进程内单例）。 */
    val skillSessionCoordinator = com.yunjue.echo.mind.ui.SkillSessionCoordinator(repository)

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
