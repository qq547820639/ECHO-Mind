package com.yunjue.echo.mind.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "checkins")
data class CheckinEntity(
    @PrimaryKey val eventId: String,
    val mood: Int,
    val stress: Int,
    val energy: Int,
    val sleepRecovery: Int,
    val eventFlag: Boolean,
    val helpRequested: Boolean,
    val noteCiphertext: String?,
    val clientTimeEpochMs: Long,
    val syncState: String = "pending"
)

@Entity(tableName = "journal_entries")
data class JournalEntity(
    @PrimaryKey val eventId: String,
    val logicalId: String,
    val revision: Int,
    val bodyCiphertext: String?,
    val tagsJson: String,
    val clientTimeEpochMs: Long,
    val deleted: Boolean = false
)

@Entity(tableName = "questionnaire_results")
data class QuestionnaireEntity(
    @PrimaryKey val eventId: String,
    val instrument: String,
    val version: String,
    val answersJson: String,
    val score: Int,
    val urgentItem: Boolean,
    val clientTimeEpochMs: Long
)

@Entity(tableName = "practice_completions")
data class PracticeCompletionEntity(
    @PrimaryKey val eventId: String,
    val practiceId: String,
    val contentVersion: String,
    val status: String,
    val durationSeconds: Int,
    val clientTimeEpochMs: Long
)

@Entity(tableName = "outbox_events")
data class OutboxEventEntity(
    @PrimaryKey val eventId: String,
    val eventType: String,
    val payloadCiphertext: String,
    val priority: Int,
    val createdAtEpochMs: Long,
    val attempts: Int = 0
)

/**
 * 原始信号样本缓冲历史类定义（Room v3 遗留，仅作历史，**不再注册**到 @Database）。
 *
 * v4 迁移（MIGRATION_3_4）已 DROP sensor_samples 表，落实"原始数据不落盘"承诺。
 */
@Entity(tableName = "sensor_samples")
data class SensorSampleEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val source: String,
    val timestamp: Long,
    val value: String,
    val createdAt: Long
)

/**
 * 派生特征本地缓存（summary 字段加密）。
 * vector 存储为 JSON 数组字符串。synced 标记是否已成功上传。
 */
@Entity(tableName = "feature_vectors")
data class FeatureVectorEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val schemaVersion: String,
    val source: String,
    val windowStart: Long,
    val windowEnd: Long,
    val summaryCiphertext: String,
    val vector: String,
    val synced: Boolean = false,
    val createdAt: Long
)

/**
 * Skill 执行会话持久化（Room v5，T02）：
 *
 * - 跨 recomposition / tab 切换 / activity recreation / process death / app restart 恢复；
 * - status：running / paused（恢复时统一回到 PAUSED，避免进程死亡期间虚增时长）；
 * - duration 语义：activeDurationMs = accumulatedActiveMs + (now - segmentStartedAtMs)
 *   （running 时）；paused 时 = accumulatedActiveMs（暂停不计时）；
 * - 加载时 actionType 不在白名单 → 丢弃会话（fail closed）。
 */
@Entity(tableName = "active_skill_sessions")
data class ActiveSkillSessionEntity(
    @PrimaryKey val sessionId: String,
    val skillId: String,
    val skillVersion: Int,
    val skillRevision: Int,
    val actionType: String,
    val status: String,
    val currentStep: Int,
    val startedAt: Long,
    val accumulatedActiveMs: Long,
    val segmentStartedAtMs: Long?,
    val pausedAt: Long?,
    val updatedAt: Long
)

@Dao
interface EchoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCheckin(value: CheckinEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertJournal(value: JournalEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertQuestionnaire(value: QuestionnaireEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPracticeCompletion(value: PracticeCompletionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOutbox(value: OutboxEventEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOutboxEvents(values: List<OutboxEventEntity>)

    @Query("SELECT * FROM checkins ORDER BY clientTimeEpochMs DESC") fun observeCheckins(): Flow<List<CheckinEntity>>
    @Query("SELECT * FROM journal_entries WHERE deleted = 0 ORDER BY clientTimeEpochMs DESC") fun observeJournals(): Flow<List<JournalEntity>>
    @Query("SELECT * FROM questionnaire_results ORDER BY clientTimeEpochMs DESC") fun observeQuestionnaires(): Flow<List<QuestionnaireEntity>>
    @Query("SELECT * FROM practice_completions ORDER BY clientTimeEpochMs DESC") fun observePractices(): Flow<List<PracticeCompletionEntity>>
    @Query("SELECT * FROM outbox_events ORDER BY priority DESC, createdAtEpochMs ASC") suspend fun pendingOutbox(): List<OutboxEventEntity>
    @Query("DELETE FROM outbox_events WHERE eventId = :eventId") suspend fun deleteOutbox(eventId: String)
    @Query("UPDATE outbox_events SET attempts = attempts + 1 WHERE eventId = :eventId") suspend fun incrementAttempts(eventId: String)
    @Query("SELECT COUNT(*) FROM outbox_events") fun observePendingCount(): Flow<Int>

    // ===== T04 派生特征 DAO =====
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFeatureVector(value: FeatureVectorEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFeatureVectors(values: List<FeatureVectorEntity>)
    @Query("SELECT * FROM feature_vectors WHERE synced = 0 ORDER BY windowStart ASC") suspend fun pendingFeatureVectors(): List<FeatureVectorEntity>
    @Query("UPDATE feature_vectors SET synced = 1 WHERE id = :id") suspend fun markFeatureVectorSynced(id: String)

    // ===== v5 ActiveSkillSession DAO（T02） =====
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertActiveSkillSession(value: ActiveSkillSessionEntity)
    @Query("SELECT * FROM active_skill_sessions LIMIT 1") suspend fun activeSkillSession(): ActiveSkillSessionEntity?
    @Query("DELETE FROM active_skill_sessions WHERE sessionId = :sessionId") suspend fun deleteActiveSkillSession(sessionId: String)
}

/**
 * 同意记录（本地持久化）。
 *
 * T04 已统一注册到 @Database entities 列表，v2→v3 迁移建表。
 */
@Entity(tableName = "consents")
data class ConsentEntity(
    @PrimaryKey val eventId: String,
    val userId: String,
    val consentType: String,
    val version: String,
    val granted: Boolean,
    val grantedAt: Long,
    val evidenceHash: String
)

@Dao
interface ConsentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConsent(value: ConsentEntity)

    @Query("SELECT * FROM consents WHERE consentType = :type ORDER BY grantedAt DESC LIMIT 1")
    suspend fun latestConsent(type: String): ConsentEntity?

    @Query("SELECT * FROM consents WHERE consentType = :type ORDER BY grantedAt DESC")
    suspend fun consentsByType(type: String): List<ConsentEntity>

    @Query("SELECT COUNT(*) FROM consents WHERE consentType = :type AND granted = 1")
    suspend fun grantedCount(type: String): Int
}

@Database(
    entities = [
        CheckinEntity::class,
        JournalEntity::class,
        QuestionnaireEntity::class,
        PracticeCompletionEntity::class,
        OutboxEventEntity::class,
        ConsentEntity::class,
        FeatureVectorEntity::class,
        ActiveSkillSessionEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun dao(): EchoDao
    abstract fun consentDao(): ConsentDao
}
