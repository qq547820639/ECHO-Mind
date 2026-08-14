package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.memory.MemoryType

/**
 * ERA 5 — Reasoning Task System（typed reasoning tasks，Master Prompt PART 29/30）。
 *
 * 不同任务有不同 context policy / privacy budget / output schema / confidence threshold；
 * 逻辑角色分开但**不滥用多 Agent**：同一个模型执行不同 Contract 的 typed task。
 *
 * v2 §42：策略实例化 —— 每个任务同时定义 EvidencePolicy（数据源 + 时间窗）
 * 与 MemoryPolicy（允许进入上下文的记忆类型）。
 */

enum class ReasoningTaskId {
    GENERATE_NOW_INTERPRETATION,
    EXPLAIN_CURRENT_STATE,
    FIND_LONGITUDINAL_PATTERN,
    ANSWER_PERSONAL_QUESTION,
    SUMMARIZE_WEEK,
    SUMMARIZE_MONTH,
    SYNTHESIZE_MEMORY,
    CLASSIFY_MEMORY_VALUE,
    PROPOSE_ACTION,
    INTERPRET_USER_CORRECTION,
}

/** 数据源类别（Privacy Budget 的计价单位）。 */
enum class DataSourceCategory {
    TODAY_AGGREGATE,
    BASELINE,
    PORTRAIT_HISTORY,
    CONTEXT_EXCEPTIONS,
    USER_CORRECTIONS,
    PREFERENCES,
    CONVERSATION_HISTORY,
    MIC_FEATURES,
    RAW_NOTIFICATIONS,
    RAW_AUDIO,
}

/**
 * 每个任务的上下文策略（PERSONAL_INTELLIGENCE_CONTRACT §9）。
 * - allowed：该任务可用的数据类别；
 * - prohibited：硬禁止（即使出现在 evidence 里也会被剔除）；
 * - maxEvidenceItems：最小上下文上限（Relevant context ≠ Maximum context）；
 * - structuredOutput：是否要求 JSON 结构化输出（native 不支持时 validation/repair/fallback）；
 * - timeWindowDays：PORTRAIT_HISTORY 检索窗口（v2 §42 EvidencePolicy.TimeWindow）；
 * - allowedMemoryTypes：允许进入上下文的记忆类型（v2 §42 MemoryPolicy）。
 */
data class ContextPolicy(
    val allowed: Set<DataSourceCategory>,
    val prohibited: Set<DataSourceCategory>,
    val maxEvidenceItems: Int,
    val structuredOutput: Boolean,
    val timeWindowDays: Int = 7,
    val allowedMemoryTypes: Set<MemoryType> = emptySet(),
    /** ERA 15 §69：记忆条数上限（防止全历史塞模型）。 */
    val maxMemories: Int = 6,
    /** ERA 15 §69：token 预算（约 3 字符/token 的保守估算）。 */
    val maxTokens: Int = 1600,
)

/** 隐私硬边界：原始通知内容 / 原始音频 / 麦克风特征永不进入任何任务。 */
private val NEVER_ALLOWED: Set<DataSourceCategory> = setOf(
    DataSourceCategory.RAW_NOTIFICATIONS,
    DataSourceCategory.RAW_AUDIO,
    DataSourceCategory.MIC_FEATURES,
)

fun contextPolicyFor(task: ReasoningTaskId): ContextPolicy {
    val base = ContextPolicy(
        allowed = setOf(DataSourceCategory.TODAY_AGGREGATE, DataSourceCategory.BASELINE),
        prohibited = NEVER_ALLOWED,
        maxEvidenceItems = 12,
        structuredOutput = false,
    )
    return when (task) {
        ReasoningTaskId.GENERATE_NOW_INTERPRETATION -> base.copy(
            allowed = base.allowed + DataSourceCategory.CONTEXT_EXCEPTIONS,
            structuredOutput = true,
            timeWindowDays = 1,
            allowedMemoryTypes = setOf(MemoryType.CONTEXT),
        )

        ReasoningTaskId.EXPLAIN_CURRENT_STATE -> base.copy(
            allowed = base.allowed + DataSourceCategory.CONTEXT_EXCEPTIONS + DataSourceCategory.USER_CORRECTIONS,
            timeWindowDays = 7,
            allowedMemoryTypes = setOf(MemoryType.CONTEXT, MemoryType.CORRECTION),
        )

        ReasoningTaskId.FIND_LONGITUDINAL_PATTERN -> base.copy(
            allowed = setOf(
                DataSourceCategory.PORTRAIT_HISTORY,
                DataSourceCategory.BASELINE,
                DataSourceCategory.CONTEXT_EXCEPTIONS,
            ),
            maxEvidenceItems = 40,
            timeWindowDays = 28,
            allowedMemoryTypes = setOf(MemoryType.CONTEXT, MemoryType.CORRECTION),
        )

        // 个人问题：允许对话历史（用户自己的话），但原始通知/音频/麦克风仍然硬禁止
        ReasoningTaskId.ANSWER_PERSONAL_QUESTION -> ContextPolicy(
            allowed = setOf(
                DataSourceCategory.TODAY_AGGREGATE,
                DataSourceCategory.BASELINE,
                DataSourceCategory.PORTRAIT_HISTORY,
                DataSourceCategory.CONTEXT_EXCEPTIONS,
                DataSourceCategory.USER_CORRECTIONS,
                DataSourceCategory.PREFERENCES,
                DataSourceCategory.CONVERSATION_HISTORY,
            ),
            prohibited = NEVER_ALLOWED,
            maxEvidenceItems = 30,
            structuredOutput = false,
            timeWindowDays = 28,
            allowedMemoryTypes = setOf(
                MemoryType.CONTEXT, MemoryType.CORRECTION,
                MemoryType.USER_CONFIRMED, MemoryType.PREFERENCE,
            ),
        )

        ReasoningTaskId.SUMMARIZE_WEEK -> base.copy(
            allowed = setOf(
                DataSourceCategory.PORTRAIT_HISTORY,
                DataSourceCategory.BASELINE,
                DataSourceCategory.CONTEXT_EXCEPTIONS,
            ),
            maxEvidenceItems = 40,
            timeWindowDays = 7,
            allowedMemoryTypes = setOf(MemoryType.CONTEXT, MemoryType.CORRECTION),
        )

        ReasoningTaskId.SUMMARIZE_MONTH -> base.copy(
            allowed = setOf(
                DataSourceCategory.PORTRAIT_HISTORY,
                DataSourceCategory.BASELINE,
                DataSourceCategory.CONTEXT_EXCEPTIONS,
            ),
            maxEvidenceItems = 40,
            timeWindowDays = 28,
            allowedMemoryTypes = setOf(MemoryType.CONTEXT, MemoryType.CORRECTION),
        )

        ReasoningTaskId.SYNTHESIZE_MEMORY -> base.copy(
            allowed = setOf(
                DataSourceCategory.BASELINE,
                DataSourceCategory.PORTRAIT_HISTORY,
                DataSourceCategory.USER_CORRECTIONS,
                DataSourceCategory.CONTEXT_EXCEPTIONS,
            ),
            maxEvidenceItems = 40,
            timeWindowDays = 28,
            allowedMemoryTypes = setOf(
                MemoryType.OBSERVATION, MemoryType.CONTEXT, MemoryType.CORRECTION,
                MemoryType.USER_CONFIRMED, MemoryType.DERIVED_PATTERN,
            ),
        )

        ReasoningTaskId.CLASSIFY_MEMORY_VALUE -> base.copy(
            allowed = setOf(DataSourceCategory.TODAY_AGGREGATE, DataSourceCategory.CONTEXT_EXCEPTIONS),
            maxEvidenceItems = 6,
            timeWindowDays = 1,
        )

        ReasoningTaskId.PROPOSE_ACTION -> base.copy(
            allowed = setOf(
                DataSourceCategory.TODAY_AGGREGATE,
                DataSourceCategory.BASELINE,
                DataSourceCategory.PREFERENCES,
                DataSourceCategory.CONTEXT_EXCEPTIONS,
            ),
            timeWindowDays = 7,
            allowedMemoryTypes = setOf(MemoryType.CONTEXT, MemoryType.PREFERENCE),
        )

        ReasoningTaskId.INTERPRET_USER_CORRECTION -> base.copy(
            allowed = setOf(DataSourceCategory.USER_CORRECTIONS, DataSourceCategory.CONTEXT_EXCEPTIONS),
            maxEvidenceItems = 8,
            timeWindowDays = 7,
            allowedMemoryTypes = setOf(MemoryType.CORRECTION),
        )
    }
}
