package com.yunjue.echo.mind.memory

/**
 * ERA 6 — EchoMemory 领域模型（纯 Kotlin，无 Android 依赖）。
 *
 * PERSONAL_INTELLIGENCE_CONTRACT §5：Memory ≠ 聊天记录；每条记忆带生命周期
 * （decay / reinforcement / expiry / delete / user edit）；不是什么都值得记。
 */

enum class MemoryType {
    /** Observation Core 沉淀的事实（低权重，可被聚合覆盖）。 */
    OBSERVATION,

    /** 用户确认的生活上下文（出差/备考/休假…）。 */
    CONTEXT,

    /** 用户明确确认过的事实。 */
    USER_CONFIRMED,

    /** 用户偏好（提醒方式/视觉偏好…）。 */
    PREFERENCE,

    /** 用户纠错（prediction ≠ feedback + 原因）。 */
    CORRECTION,

    /** 长期稳定模式的提炼。 */
    DERIVED_PATTERN,

    /** 短时推测（不落强记忆，自动过期）。 */
    TEMPORARY_INTERPRETATION,
}

enum class RetentionClass {
    EPHEMERAL,     // 7 天
    SHORT_TERM,    // 30 天
    LONG_TERM,     // 365 天
    USER_PINNED,   // 永不过期（用户固定）
}

/**
 * ERA 23 §37 — 记忆敏感度（Privacy）：每条记忆显式携带敏感度。
 * SENSITIVE 记忆不进锁屏/壁纸叙事、不进公开依据展示。
 */
enum class MemorySensitivity {
    /** 普通个人节律/偏好。 */
    PERSONAL,

    /** 用户自述的敏感事实（纠正原因、生活事件等）。 */
    SENSITIVE,
}

data class EchoMemory(
    val id: String,
    val userId: String,
    val type: MemoryType,
    val content: String,
    val source: String,
    val confidence: Float,
    val createdAt: Long,
    val lastConfirmedAt: Long,
    val importance: Int, // 0..100
    val retentionClass: RetentionClass,
    val provenance: String,
    val deleted: Boolean = false,
    /** §37 sensitivity（CORRECTION 默认为 SENSITIVE，其余 PERSONAL）。 */
    val sensitivity: MemorySensitivity = MemorySensitivity.PERSONAL,
)

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
