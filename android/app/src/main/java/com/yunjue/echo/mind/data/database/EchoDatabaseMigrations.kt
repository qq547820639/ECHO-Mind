package com.yunjue.echo.mind.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase















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

/**
 * v10 → v11 迁移（§109 Memory Long History）：echo_memories 复合索引
 * （避免全表扫描 + JVM 排序随记忆规模增长而退化；索引名与 Room 生成命名一致）。
 * 纯增量 CREATE INDEX IF NOT EXISTS（幂等），无数据改写。
 */
internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_echo_memories_userId_deleted_importance " +
                "ON echo_memories (userId, deleted, importance)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_echo_memories_userId_type_deleted " +
                "ON echo_memories (userId, type, deleted)"
        )
    }
}

/**
 * v11 → v12 迁移（§108 Journey Long History）：feature_vectors 复合索引
 * (userId, schemaVersion, windowStart)——时间线窗口查询直查索引，不再全表扫描。
 * 纯增量 CREATE INDEX IF NOT EXISTS（幂等），无数据改写。
 */
internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_feature_vectors_userId_schemaVersion_windowStart " +
                "ON feature_vectors (userId, schemaVersion, windowStart)"
        )
    }
}
