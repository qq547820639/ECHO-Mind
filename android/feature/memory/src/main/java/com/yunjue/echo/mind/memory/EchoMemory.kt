package com.yunjue.echo.mind.memory

// ERA 28 §59（Product Quality Era Round 6）：契约类型（EchoMemory/MemoryType/
// RetentionClass/MemorySensitivity）已上移 core:model（core 不再依赖 feature）。
// 此处 typealias 兼容全仓旧 import（§60 渐进迁移：compile/test 每步验证，不做大爆炸）。
typealias MemoryType = com.yunjue.echo.mind.model.MemoryType
typealias RetentionClass = com.yunjue.echo.mind.model.RetentionClass
typealias MemorySensitivity = com.yunjue.echo.mind.model.MemorySensitivity
typealias EchoMemory = com.yunjue.echo.mind.model.EchoMemory

/** 保留天数（USER_PINNED 无自动过期）。 */
fun retentionDaysFor(retentionClass: RetentionClass): Long = when (retentionClass) {
    RetentionClass.EPHEMERAL -> 7L
    RetentionClass.SHORT_TERM -> 30L
    RetentionClass.LONG_TERM -> 365L
    RetentionClass.USER_PINNED -> Long.MAX_VALUE / 86_400_000L // 约 1060 亿天：语义=永不过期
}

/** 自动过期时刻（epoch ms；USER_PINNED → null）。 */
fun memoryExpiresAt(memory: EchoMemory, now: Long): Long? {
    if (memory.retentionClass == RetentionClass.USER_PINNED) return null
    return memory.lastConfirmedAt + retentionDaysFor(memory.retentionClass) * 86_400_000L
}

/** 是否应自动遗忘（过期且未固定）。 */
fun shouldForget(memory: EchoMemory, now: Long): Boolean {
    if (memory.deleted) return true
    val expiresAt = memoryExpiresAt(memory, now) ?: return false
    return now >= expiresAt
}

/**
 * 衰减分（0..100；用于「检索相关性」而非删除）：
 * importance × 剩余时间比例。刚确认的高重要记忆 ≈ importance；临期记忆趋近 0。
 */
fun memoryDecayScore(memory: EchoMemory, now: Long): Float {
    if (memory.deleted) return 0f
    val total = retentionDaysFor(memory.retentionClass)
    val elapsedDays = (now - memory.lastConfirmedAt).coerceAtLeast(0L).toDouble() / 86_400_000.0
    val remaining = (1.0 - elapsedDays / total.toDouble()).coerceIn(0.0, 1.0)
    return (memory.importance.toFloat() * remaining.toFloat()).coerceIn(0f, 100f)
}

/** 强化：用户确认 → 刷新 lastConfirmedAt（保留期顺延），重要度 +10（上限 100）。 */
fun reinforceMemory(memory: EchoMemory, now: Long): EchoMemory =
    memory.copy(
        lastConfirmedAt = now,
        importance = (memory.importance + 10).coerceAtMost(100),
        deleted = false,
    )

/** 类型 → 默认保留级别（写入新记忆时使用）。 */
fun defaultRetentionFor(type: MemoryType, importance: Int): RetentionClass = when (type) {
    MemoryType.TEMPORARY_INTERPRETATION -> RetentionClass.EPHEMERAL
    MemoryType.OBSERVATION -> RetentionClass.SHORT_TERM
    MemoryType.CORRECTION,
    MemoryType.CONTEXT,
    MemoryType.DERIVED_PATTERN -> RetentionClass.LONG_TERM
    MemoryType.USER_CONFIRMED,
    MemoryType.PREFERENCE -> if (importance >= 60) RetentionClass.LONG_TERM else RetentionClass.SHORT_TERM
}

/** 快速纠错原因（Master Prompt PART 39；写入 Correction Memory）。 */
val CORRECTION_REASONS: List<String> = listOf(
    "工作", "旅行", "假期", "身体不舒服", "特殊事件", "只是很专注", "不想说", "其他"
)
