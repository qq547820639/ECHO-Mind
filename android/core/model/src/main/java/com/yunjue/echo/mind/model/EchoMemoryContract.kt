package com.yunjue.echo.mind.model

/**
 * ERA 28 §59 — EchoMemory 契约类型（core:model 宿主）。
 *
 * Product Quality Era Round 6 之前，EchoMemory/MemoryType 住在 feature:memory，
 * 导致 core:ports → feature:memory 的反向依赖（core 依赖 feature，§58 违规）。
 * 契约类型上移到 core:model；feature:memory 通过 typealias 兼容旧 import
 * （§60 渐进：一次性迁移会破坏全仓 import，typealias 桥让 compile/test 逐步推进）。
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
