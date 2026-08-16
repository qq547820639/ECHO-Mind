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
 *
 * v8（离线画像引擎）：新增 sourcesPresentJson（窗口实际信号源 JSON 数组）。
 * 端侧本地画像聚合需要该字段还原 missing_sources 与 confidence；
 * 迁移 7→8 为纯加列（可空），旧行回退按 source 单元素集合解释。
 * v12（§108 Journey Long History）：复合索引 (userId, schemaVersion, windowStart)
 * ——时间线窗口查询不再全表扫描（MIGRATION_11_12 CREATE INDEX，纯增量）。
 */
@Entity(
    tableName = "feature_vectors",
    indices = [Index(value = ["userId", "schemaVersion", "windowStart"])]
)
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
    val createdAt: Long,
    val sourcesPresentJson: String? = null
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
    /** 离线画像引擎：某用户全部 passive-core-v1 窗口（按窗口起点升序）。 */
    @Query("SELECT * FROM feature_vectors WHERE userId = :userId AND schemaVersion = 'passive-core-v1' ORDER BY windowStart ASC")
    suspend fun allPassiveCoreRows(userId: String): List<FeatureVectorEntity>
    /** §108 Journey Long History：窗口化查询（时间线只载入所需日期范围，复合索引直查）。 */
    @Query(
        "SELECT * FROM feature_vectors WHERE userId = :userId AND schemaVersion = 'passive-core-v1' " +
            "AND windowStart >= :fromMs AND windowStart <= :toMs ORDER BY windowStart ASC"
    )
    suspend fun passiveCoreRowsBetween(userId: String, fromMs: Long, toMs: Long): List<FeatureVectorEntity>
    /** 本地数据权利：按用户删除全部派生特征窗口（本地模式删除用）。 */
    @Query("DELETE FROM feature_vectors WHERE userId = :userId")
    suspend fun deleteFeatureVectorsByUser(userId: String)
    /** v0.7.4 本机数据面板：某用户的派生特征窗口数。 */
    @Query("SELECT COUNT(*) FROM feature_vectors WHERE userId = :userId")
    suspend fun countFeatureVectorsByUser(userId: String): Int

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

    /** 本地数据权利：某用户全部同意记录（导出用，按时间升序）。 */
    @Query("SELECT * FROM consents WHERE userId = :userId ORDER BY grantedAt ASC")
    suspend fun allByUser(userId: String): List<ConsentEntity>

    /** 本地数据权利：按用户删除全部同意记录（本地模式删除用）。 */
    @Query("DELETE FROM consents WHERE userId = :userId")
    suspend fun deleteConsentsByUser(userId: String)
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

    /** ERA 32 R26：按服务端 escalation id 查本地行（user-status 轮询回写用）。 */
    @Query("SELECT * FROM escalation_requests WHERE serverEscalationId = :serverId")
    suspend fun byServerEscalationId(serverId: String): EscalationEntity?

    /** ERA 32 R26：本地模式期间落库但未入队的人工支持请求（订阅切换补发用）。 */
    @Query("SELECT * FROM escalation_requests WHERE outboxSynced = 0")
    suspend fun unsyncedEscalations(): List<EscalationEntity>

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
 * - headline / dimensions / facts / coverage 以 JSON 字符串存储（解析由 PortraitRepository 承担）
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

    /** v0.7.4 本机数据面板：某用户的画像缓存数。 */
    @Query("SELECT COUNT(*) FROM portrait_daily WHERE userId = :userId")
    suspend fun countPortraitsByUser(userId: String): Int
}

/**
 * ERA 6 — EchoMemory 本地实体（Room v9）。
 *
 * Memory ≠ 聊天记录：每条记忆携带生命周期字段（PERSONAL_INTELLIGENCE_CONTRACT §5.2）。
 * - id 为本地生成幂等键；content 为明文短文本（用户可见、可编辑、可删除）；
 * - provenance 记录来源（observation-core / user-statement / ai-inference-v1…）；
 * - deleted 为软删除（用户 forget / 自动过期后打标，历史可审计）。
 */
@Entity(
    tableName = "echo_memories",
    indices = [
        Index(value = ["userId", "deleted", "importance"]),
        Index(value = ["userId", "type", "deleted"])
    ]
)
data class EchoMemoryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val type: String,
    val content: String,
    val source: String,
    val confidence: Float,
    val createdAt: Long,
    val lastConfirmedAt: Long,
    val importance: Int,
    val retentionClass: String,
    val provenance: String,
    val deleted: Boolean = false,
)

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: EchoMemoryEntity)

    @Query("SELECT * FROM echo_memories WHERE userId = :userId AND deleted = 0 ORDER BY importance DESC, lastConfirmedAt DESC")
    fun observeByUser(userId: String): Flow<List<EchoMemoryEntity>>

    @Query("SELECT * FROM echo_memories WHERE userId = :userId AND deleted = 0 ORDER BY importance DESC, lastConfirmedAt DESC LIMIT :limit")
    suspend fun topByUser(userId: String, limit: Int): List<EchoMemoryEntity>

    /** ERA 63（§109 审计）：过期维护专用全量扫描（无 LIMIT——此前 500 条截断漏过期低重要度记忆）。 */
    @Query("SELECT * FROM echo_memories WHERE userId = :userId AND deleted = 0")
    suspend fun allNonDeletedByUser(userId: String): List<EchoMemoryEntity>

    @Query("SELECT * FROM echo_memories WHERE userId = :userId AND type = :type AND deleted = 0 ORDER BY lastConfirmedAt DESC")
    suspend fun byType(userId: String, type: String): List<EchoMemoryEntity>

    /** 本地数据权利导出：该用户全部记忆（含软删记录与 deleted 标记，完整记录不留盲区）。 */
    @Query("SELECT * FROM echo_memories WHERE userId = :userId ORDER BY createdAt ASC")
    suspend fun allByUser(userId: String): List<EchoMemoryEntity>

    @Query("SELECT * FROM echo_memories WHERE id = :id")
    suspend fun byId(id: String): EchoMemoryEntity?

    /** 用户 forget：软删除（可审计，不物理抹除）。 */
    @Query("UPDATE echo_memories SET deleted = 1 WHERE id = :id")
    suspend fun forget(id: String)

    /** 用户 confirm：刷新确认时间 + 重要度 +10（上限 100）。 */
    @Query("UPDATE echo_memories SET lastConfirmedAt = :now, importance = MIN(100, importance + 10) WHERE id = :id")
    suspend fun confirm(id: String, now: Long)

    /** 用户 edit：内容更新 + 确认时间刷新。 */
    @Query("UPDATE echo_memories SET content = :content, lastConfirmedAt = :now WHERE id = :id")
    suspend fun edit(id: String, content: String, now: Long)

    /** ERA 82 §76：用户 pin——固定为永不过期（USER_PINNED）+ 确认时间刷新。 */
    @Query("UPDATE echo_memories SET retentionClass = 'USER_PINNED', lastConfirmedAt = :now WHERE id = :id")
    suspend fun pin(id: String, now: Long)

    /** 自动过期打标（purgeExpired）。 */
    @Query("UPDATE echo_memories SET deleted = 1 WHERE id = :id")
    suspend fun expire(id: String)

    /** 本地数据权利：物理删除某用户全部记忆。 */
    @Query("DELETE FROM echo_memories WHERE userId = :userId")
    suspend fun deleteByUser(userId: String)

    /** ERA 66（ADR-062 第 1 轮）：存储足迹计数（含软删行——数据权利体检按存储真值报告）。 */
    @Query("SELECT COUNT(*) FROM echo_memories WHERE userId = :userId")
    suspend fun countAllByUser(userId: String): Int
}

/**
 * ERA 16 §83 — Journey Canonical Daily State 持久化（Room v10）。
 *
 * 每天一行「当天 ECHO 长什么样」的最小事实（payload = [JourneyCanonicalCodec] v1 编码，
 * 只存视觉参数/身份参考/成熟度/证据 id——绝不保存 bitmap）。
 * - id = "${userId}_${localDate}"（同日覆盖 = 幂等快照）
 * - 所有查询 SQL 层按 userId 隔离（同 portrait_daily 语义）
 */
@Entity(
    tableName = "journey_canonical_days",
    indices = [Index(value = ["localDate"]), Index(value = ["userId"])]
)
data class JourneyCanonicalDayEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val localDate: String,
    val payload: String,
    val createdAtEpochMs: Long
)

@Dao
interface JourneyCanonicalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: JourneyCanonicalDayEntity)

    @Query("SELECT * FROM journey_canonical_days WHERE userId = :userId ORDER BY localDate ASC")
    fun observeByUser(userId: String): Flow<List<JourneyCanonicalDayEntity>>

    @Query("SELECT * FROM journey_canonical_days WHERE id = :id")
    suspend fun byId(id: String): JourneyCanonicalDayEntity?

    @Query(
        "SELECT * FROM journey_canonical_days WHERE userId = :userId " +
            "AND localDate >= :from AND localDate <= :to ORDER BY localDate ASC"
    )
    suspend fun range(userId: String, from: String, to: String): List<JourneyCanonicalDayEntity>

    @Query("SELECT COUNT(*) FROM journey_canonical_days WHERE userId = :userId")
    suspend fun countByUser(userId: String): Int

    /** 本地数据权利：物理删除某用户全部 Canonical 快照。 */
    @Query("DELETE FROM journey_canonical_days WHERE userId = :userId")
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
        DailyPortraitEntity::class,
        EchoMemoryEntity::class,
        JourneyCanonicalDayEntity::class
    ],
    version = 12,
    exportSchema = true
)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun dao(): EchoDao
    abstract fun consentDao(): ConsentDao
    abstract fun escalationDao(): EscalationDao
    abstract fun portraitDao(): PortraitDao
    abstract fun memoryDao(): MemoryDao
    abstract fun journeyCanonicalDao(): JourneyCanonicalDao
}
