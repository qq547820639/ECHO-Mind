package com.yunjue.echo.mind.ports

import com.yunjue.echo.mind.model.EchoMemory
import com.yunjue.echo.mind.model.MemoryType

/**
 * ERA 13.2 §39 — Memory Ports。
 *
 * MemoryRepository（data 包）是 Adapter：读/写/纠错三向端口分离。
 * intelligence 只读（EchoMemoryReader）；UI 写入口经 EchoCorrectionService。
 */

/** 记忆读取端口（检索与排名使用）。 */
interface EchoMemoryReader {
    suspend fun memoriesByType(type: MemoryType): List<EchoMemory>
}

/** 记忆写入端口（新记忆 / confirm / forget / edit；默认参数只允许在端口声明）。 */
interface EchoMemoryWriter {
    suspend fun record(
        type: MemoryType,
        content: String,
        source: String,
        provenance: String,
        confidence: Float = 0.8f,
        importance: Int = 50,
        now: Long = System.currentTimeMillis(),
    ): String

    suspend fun confirm(id: String, now: Long = System.currentTimeMillis())

    suspend fun forget(id: String)

    suspend fun edit(id: String, content: String, now: Long = System.currentTimeMillis())

    /** ERA 82 §76：固定记忆（USER_PINNED 永不过期；用户控制权的第四权）。 */
    suspend fun pin(id: String, now: Long = System.currentTimeMillis())
}

/** 纠错记忆写入端口（用户自述最高优先；UI 不得绕过）。 */
interface CorrectionMemoryWriter {
    suspend fun recordCorrection(
        date: String,
        reason: String,
        originalStatement: String?,
        now: Long = System.currentTimeMillis(),
    ): String
}
