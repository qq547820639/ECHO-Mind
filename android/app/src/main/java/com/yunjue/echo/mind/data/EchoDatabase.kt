package com.yunjue.echo.mind.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
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
    // v0.6.1（P0-4）：领域规则 = 产品同时只允许一个 Skill 执行（single-active-session）。
    // 数据库与代码都强制该规则：start 新会话前先清除旧会话（协调器执行）；
    // 恢复必须按 skillId 精确查询（不得 LIMIT 1 随机取一条，避免跨卡误删）。
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertActiveSkillSession(value: ActiveSkillSessionEntity)
    @Query("SELECT * FROM active_skill_sessions WHERE skillId = :skillId LIMIT 1")
    suspend fun activeSkillSessionBySkillId(skillId: String): ActiveSkillSessionEntity?
    @Query("SELECT * FROM active_skill_sessions LIMIT 1")
    suspend fun anyActiveSkillSession(): ActiveSkillSessionEntity?
    @Query("DELETE FROM active_skill_sessions WHERE sessionId = :sessionId")
    suspend fun deleteActiveSkillSession(sessionId: String)
    @Query("DELETE FROM active_skill_sessions")
    suspend fun clearActiveSkillSessions()
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

/**
 * 人工支持请求用户侧最小状态（v0.6.1，P0-2）。
 *
 * 语义（对用户可见的**最小必要**状态，绝不暴露内部升级策略/值班隐私）：
 * - QUEUED：请求已保存在本机，等待送达（离线可排队）
 * - DELIVERED：服务端已接收（≠ 人工已收到）
 * - ACKNOWLEDGED：人工已确认（服务端 ack）
 * - TAKEN_OVER：正在接管（服务端 takeover）
 * - CLOSED：已完成（服务端 close）
 * - FAILED：本地已放弃（dead-letter，需重新联系机构）
 */
enum class EscalationStatus { QUEUED, DELIVERED, ACKNOWLEDGED, TAKEN_OVER, CLOSED, FAILED }

/**
 * 人工支持请求（escalation）本地实体（v0.6.1，P0-2 客户端闭环）：
 *
 * - eventId 为本地生成的幂等键（Outbox 同键上传 POST /v1/escalations）；
 * - status 为**用户侧可见的最小状态**（客户端自己维护的乐观状态）：
 *   queued（等待送达）/ delivered（服务端已接收）/ acknowledged（人工已确认）/
 *   taken_over（正在接管）/ closed（已完成）/ failed（dead-letter 后人工可见失败）；
 * - serverEscalationId 在服务端返回后持久化；
 * - serverStatus 为服务端 user-status 查询结果（delivery_confirmed / human_acknowledged），
 *   未收到服务端确认前**绝不**向用户展示"人工已收到"。
 */
@Entity(tableName = "escalation_requests")
data class EscalationEntity(
    @PrimaryKey val eventId: String,
    val userId: String,
    val trigger: String,
    val evidenceSummaryCiphertext: String,
    val status: String,
    val serverEscalationId: String?,
    val serverStatusJson: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val outboxSynced: Boolean = false
)

@Dao
interface EscalationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: EscalationEntity)

    @Query("SELECT * FROM escalation_requests ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<EscalationEntity>>

    @Query("SELECT * FROM escalation_requests WHERE eventId = :eventId")
    suspend fun byEventId(eventId: String): EscalationEntity?

    @Query("SELECT * FROM escalation_requests ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun latest(): EscalationEntity?

    @Query("DELETE FROM escalation_requests WHERE eventId = :eventId")
    suspend fun delete(eventId: String)
}

/**
 * 每日画像本地缓存（Room v7，Milestone H offline-first）。
 *
 * - id = "${localDate}_${userId}"（localDate 为**端侧本地时区**日期；时区修改后
 *   新"今天"不与该键冲突，重新拉取而非误用旧画像）
 * - headline / dimensions / facts / coverage 以 JSON 字符串存储（解析由 LocalRepository 承担）
 * - queryByDateRange 用 ISO 日期字符串比较（yyyy-MM-dd 字典序 = 时间序）
 * - queryLatest 供 Today 页缓存优先展示
 */
@Entity(tableName = "portrait_daily", indices = [Index(value = ["localDate"])])
data class DailyPortraitEntity(
    @PrimaryKey val id: String,
    val localDate: String,
    val userId: String,
    val status: String,
    val confidence: String,
    val headlineJson: String,
    val summary: String,
    val dimensionsJson: String,
    val factsJson: String,
    val coverageJson: String?,
    val timezoneUsed: String?,
    val fetchedAt: Long
)

@Dao
interface PortraitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(value: DailyPortraitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(values: List<DailyPortraitEntity>)

    /**
     * Phase 3.3（Portrait Cache User Isolation）：所有查询必须在 SQL 层带 userId，
     * 防止 User B 查询到 User A 的画像缓存（账户切换 / 激活码重新登录后）。
     */
    @Query("SELECT * FROM portrait_daily WHERE userId = :userId AND localDate >= :from AND localDate <= :to ORDER BY localDate ASC")
    suspend fun queryByDateRange(userId: String, from: String, to: String): List<DailyPortraitEntity>

    @Query("SELECT * FROM portrait_daily WHERE userId = :userId ORDER BY localDate DESC LIMIT 1")
    suspend fun queryLatest(userId: String): DailyPortraitEntity?

    @Query("DELETE FROM portrait_daily WHERE userId = :userId")
    suspend fun deleteByUser(userId: String)
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
        ActiveSkillSessionEntity::class,
        EscalationEntity::class,
        DailyPortraitEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun dao(): EchoDao
    abstract fun consentDao(): ConsentDao
    abstract fun escalationDao(): EscalationDao
    abstract fun portraitDao(): PortraitDao
}
