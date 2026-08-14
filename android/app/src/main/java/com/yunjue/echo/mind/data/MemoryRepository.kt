package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.memory.RetentionClass
import com.yunjue.echo.mind.memory.defaultRetentionFor
import com.yunjue.echo.mind.memory.memoryDecayScore
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

    suspend fun topMemories(limit: Int = 20): List<EchoMemory> =
        memoryDao().topByUser(preferences.userId, limit).map { it.toDomain() }

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

    override suspend fun edit(id: String, content: String, now: Long) {
        if (content.isBlank()) return
        memoryDao().edit(id, content.trim(), now)
    }

    /** 自动过期清理（软删）；返回清理条数。 */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int {
        var purged = 0
        for (entity in memoryDao().topByUser(preferences.userId, limit = 500)) {
            if (shouldForget(entity.toDomain(), now)) {
                memoryDao().expire(entity.id)
                purged++
            }
        }
        return purged
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
