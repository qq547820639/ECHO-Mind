package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.RetentionClass
import com.yunjue.echo.mind.memory.defaultRetentionFor
import com.yunjue.echo.mind.memory.contextExceptionContent
import com.yunjue.echo.mind.memory.contextExceptionInfo
import com.yunjue.echo.mind.memory.derivePatterns
import com.yunjue.echo.mind.memory.memoryDecayScore
import com.yunjue.echo.mind.memory.rankMemories
import com.yunjue.echo.mind.memory.shouldForget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * ERA 6 — Memory Repository：EchoMemory 的持久化与生命周期执行点。
 *
 * - 写入口收敛（record / recordCorrection / confirm / forget / edit / purgeExpired）；
 * - 自动过期：purgeExpired 按 [shouldForget] 打软删（审计保留，物理删除走数据权利）；
 * - 检索相关性由 [memoryDecayScore] 排序（不是什么都值得记，也不是什么都永远在）。
 */
class MemoryRepository(
    private val db: EchoDatabase,
    private val preferences: AppPreferences,
) :
    com.yunjue.echo.mind.ports.EchoMemoryReader,
    com.yunjue.echo.mind.ports.EchoMemoryWriter,
    com.yunjue.echo.mind.ports.CorrectionMemoryWriter {
    private fun memoryDao() = db.memoryDao()

    fun observeMemories(): Flow<List<EchoMemory>> =
        memoryDao().observeByUser(preferences.userId).map { list -> list.map { it.toDomain() } }

    suspend fun topMemories(limit: Int = 20): List<EchoMemory> {
        // ERA 15.5 §75：JVM 侧正式排序（类型优先级 × 衰减分 × 重要度）
        val fetched = memoryDao().topByUser(preferences.userId, limit * 3).map { it.toDomain() }
        return rankMemories(fetched, System.currentTimeMillis()).take(limit)
    }

    override suspend fun memoriesByType(type: MemoryType): List<EchoMemory> = byType(type)

    suspend fun byType(type: MemoryType): List<EchoMemory> =
        memoryDao().byType(preferences.userId, type.name).map { it.toDomain() }

    /** 写入一条记忆（幂等 id 由调用方提供或自动生成）。 */
    override suspend fun record(
        type: MemoryType,
        content: String,
        source: String,
        provenance: String,
        confidence: Float,
        importance: Int,
        now: Long,
    ): String {
        val id = "mem_${UUID.randomUUID().toString().replace("-", "")}"
        memoryDao().upsert(
            EchoMemoryEntity(
                id = id,
                userId = preferences.userId,
                type = type.name,
                content = content,
                source = source,
                confidence = confidence.coerceIn(0f, 1f),
                createdAt = now,
                lastConfirmedAt = now,
                importance = importance.coerceIn(0, 100),
                retentionClass = defaultRetentionFor(type, importance).name,
                provenance = provenance,
                deleted = false,
            )
        )
        return id
    }

    /**
     * 用户纠错记忆（Master Prompt PART 39：prediction ≠ user feedback + 原因）。
     * 「不太像」的每次反馈都进入 Correction Memory，之后推理必须可检索。
     */
    override suspend fun recordCorrection(
        date: String,
        reason: String,
        originalStatement: String?,
        now: Long,
    ): String {
        val statement = originalStatement?.takeIf { it.isNotBlank() }?.let { "（原判断：$it）" } ?: ""
        return record(
            type = MemoryType.CORRECTION,
            content = "画像反馈：不太像（原因：$reason）$statement",
            source = "user-feedback",
            provenance = "user-correction:v1",
            confidence = 1f, // 用户自述 = 最高置信来源（Felt outranks interpretation）
            importance = 80,
            now = now,
        )
    }

    override suspend fun confirm(id: String, now: Long) {
        memoryDao().confirm(id, now)
    }

    override suspend fun forget(id: String) {
        memoryDao().forget(id)
    }

    override suspend fun pin(id: String, now: Long) {
        memoryDao().pin(id, now)
    }

    override suspend fun edit(id: String, content: String, now: Long) {
        if (content.isBlank()) return
        memoryDao().edit(id, content.trim(), now)
    }

    /**
     * ERA 15.5 §77：派生模式记忆（重复 + 足够 evidence + 稳定 confidence 才形成）。
     * 幂等：pattern id 由内容哈希派生，重复运行覆盖同一条。
     */
    suspend fun derivePatterns(): Int {
        val observations = byType(MemoryType.OBSERVATION)
        val patterns = derivePatterns(observations)
        var created = 0
        for (pattern in patterns) {
            val id = "pattern_" + (pattern.content.hashCode().toLong() and 0x7FFFFFFF).toString()
            memoryDao().upsert(
                EchoMemoryEntity(
                    id = id,
                    userId = preferences.userId,
                    type = MemoryType.DERIVED_PATTERN.name,
                    content = "反复出现的模式：${pattern.content}（出现 ${pattern.evidenceCount} 次）",
                    source = "derived-pattern",
                    confidence = pattern.confidence,
                    createdAt = System.currentTimeMillis(),
                    lastConfirmedAt = System.currentTimeMillis(),
                    importance = 60,
                    retentionClass = RetentionClass.LONG_TERM.name,
                    provenance = "derived-pattern:v1",
                    deleted = false,
                )
            )
            created++
        }
        return created
    }

    /**
     * ERA 15.5 §78/§79：用户解释优先（Context Exception 用户入口统一写点）。
     * ERA 16 §86：可选 [date] 使特殊时期可定位到时间线（Journey 年视图/河流 SPECIAL 段）。
     */
    suspend fun recordContextException(kind: String, note: String, date: String? = null) {
        val content = contextExceptionContent(kind, note, date)
        record(
            type = MemoryType.CONTEXT,
            content = content,
            source = "user-stated-context",
            provenance = "context-exception:v1",
            confidence = 1f, // 用户自述 = 最高置信来源
            importance = 70,
        )
    }

    /** 带日期的上下文例外（date → kind；Journey 时间线用；无日期信息的不进入）。 */
    suspend fun contextExceptions(): Map<String, String> =
        byType(MemoryType.CONTEXT)
            .mapNotNull { memory ->
                val info = contextExceptionInfo(memory.content) ?: return@mapNotNull null
                info.date?.let { date -> date to info.kind }
            }
            .toMap()

    /** 自动过期清理（软删）；返回清理条数。 */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int {
        var purged = 0
        // ERA 63（§109 审计）：全量扫描——此前 topByUser(limit=500) 按重要度截断，
        // 低重要度短时记忆（恰是最可能过期的一类）被遗漏在扫描之外。
        for (entity in memoryDao().allNonDeletedByUser(preferences.userId)) {
            if (shouldForget(entity.toDomain(), now)) {
                memoryDao().expire(entity.id)
                purged++
                // ERA 64（§109 第 2 轮）：维护 Worker 单轮成本上限——剩余过期项下一轮继续
                // （软删幂等且无顺序依赖；限制单轮 UPDATE 数量，避免万级记忆时 Worker 超时）。
                if (purged >= MAX_PURGE_PER_ROUND) break
            }
        }
        return purged
    }

    companion object {
        /** ERA 64：单轮过期清理上限（每 15 分钟维护周期最多软删条数）。 */
        const val MAX_PURGE_PER_ROUND = 2000
    }
}

private fun EchoMemoryEntity.toDomain(): EchoMemory = EchoMemory(
    id = id,
    userId = userId,
    type = runCatching { MemoryType.valueOf(type) }.getOrDefault(MemoryType.OBSERVATION),
    content = content,
    source = source,
    confidence = confidence,
    createdAt = createdAt,
    lastConfirmedAt = lastConfirmedAt,
    importance = importance,
    retentionClass = runCatching { RetentionClass.valueOf(retentionClass) }.getOrDefault(RetentionClass.SHORT_TERM),
    provenance = provenance,
    deleted = deleted,
)
