package com.yunjue.echo.mind.memory

/**
 * ERA 23 §32 — EchoSelfModel：ECHO 的用户自我模型快照。
 *
 * 不是一个巨大 JSON，而是五个领域：
 * Rhythm / Context / Preferences / Corrections / Stable Patterns
 * （ERA 31 BATCH 3 起 Interaction Preferences 并入 Preferences——Self Model Value Audit 删除无消费方字段）。
 * 由记忆确定性构建（[buildSelfModel]），供 What ECHO Knows / 推理上下文 / Journey 共用；
 * 敏感内容（SENSITIVE）只进 [corrections] 域，不出现在公开叙事（[echoKnowsLines] 中过滤）。
 */
data class EchoSelfModel(
    /** 稳定行为模式（§33/§34/§35 生命周期结果）。 */
    val rhythmPatterns: List<StablePattern>,
    /** 活跃上下文例外（出差/休假/冲刺…）。 */
    val contexts: List<ContextExceptionInfo>,
    /** 用户偏好原文（PREFERENCE + USER_CONFIRMED；含交互类偏好）。 */
    val preferences: List<String>,
    /** 用户纠正原文（敏感；仅应用内）。 */
    val corrections: List<String>,
    /** 快照生成时刻（epoch ms）。 */
    val generatedAt: Long,
) {
    /** 稳定模式（CONFIRMED 且无矛盾）。 */
    val confirmedPatterns: List<StablePattern>
        get() = rhythmPatterns.filter { it.state == PatternState.CONFIRMED }

    /** 正在被质疑的模式（WEAKENING/CONFLICTING/OUTDATED）。 */
    val challengedPatterns: List<StablePattern>
        get() = rhythmPatterns.filter { it.state in setOf(PatternState.WEAKENING, PatternState.CONFLICTING, PatternState.OUTDATED) }
}

fun buildSelfModel(
    memories: List<EchoMemory>,
    now: Long,
    promotionConfig: PatternPromotionConfig = PatternPromotionConfig(),
): EchoSelfModel {
    val active = memories.filter { !it.deleted }
    val observations = active.filter { it.type == MemoryType.OBSERVATION }
    val corrections = active.filter { it.type == MemoryType.CORRECTION }
    val contexts = active.filter { it.type == MemoryType.CONTEXT }
    val preferences = active.filter { it.type == MemoryType.PREFERENCE }
    val confirmed = active.filter { it.type == MemoryType.USER_CONFIRMED }

    val patterns = promotePatterns(observations, emptyList(), corrections, contexts, now, promotionConfig)

    val contextInfos = contexts
        .mapNotNull { contextExceptionInfo(it.content) }
        .sortedByDescending { it.date ?: "" }

    // ERA 31 BATCH 3（Self Model Value Audit）：interactionPreferences 无生产消费方，
    // 交互类偏好不再单独切分，统一并入 preferences（What ECHO Knows 一并展示）。
    val preferenceTexts = (preferences + confirmed)
        .sortedByDescending { it.lastConfirmedAt }
        .map { it.content.trim() }
        .distinct()

    val correctionTexts = corrections
        .sortedByDescending { it.lastConfirmedAt }
        .map { it.content.trim() }
        .distinct()

    return EchoSelfModel(
        rhythmPatterns = patterns,
        contexts = contextInfos,
        preferences = preferenceTexts,
        corrections = correctionTexts,
        generatedAt = now,
    )
}

/**
 * ERA 25 §45（Batch 3 先导）— What ECHO Knows 自然语言行：
 * 「你的工作日通常在 09:10 左右明显开始」，而不是 baseline_activation_start = 550。
 * 只输出 PUBLIC 可展示行；纠正原文（敏感）不出现在这里。
 */
fun echoKnowsLines(model: EchoSelfModel, maxLines: Int = 6): List<String> = buildList {
    model.confirmedPatterns.take(2).forEach { p ->
        add("我观察到：${p.content}（已持续 ${p.occurrenceCount} 次观察）")
    }
    model.challengedPatterns.take(1).forEach { p ->
        add("之前关于「${p.content}」的判断最近有些出入，我还在观察。")
    }
    model.contexts.take(2).forEach { c ->
        val date = c.date?.let { "（$it 起）" } ?: ""
        add("你告诉过我：${contextExceptionKindLabel(c.kind)}$date")
    }
    model.preferences.take(2).forEach { p ->
        add("你说过：$p")
    }
    if (isEmpty() && model.corrections.isEmpty()) {
        add("我还在慢慢积累关于你的了解。")
    }
}.take(maxLines)

/**
 * ERA 23 §36 — Memory Consolidation：
 * many short memories → one consolidated memory。
 * 例：20 条「最近晚结束」不再永久保存 20 条，可合成
 * 「过去六周工作日晚间结束时间持续后移」级别的单一记忆。
 * 确定性模板（语义合成由 SYNTHESIZE_MEMORY 任务在 AI 层接力）。
 */
data class ConsolidatedMemory(
    val content: String,
    val sourceMemoryIds: List<String>,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val occurrenceCount: Int,
)

fun consolidateObservations(
    observations: List<EchoMemory>,
    now: Long,
    minGroupSize: Int = 4,
    maxConsolidated: Int = 5,
): List<ConsolidatedMemory> {
    val dayMs = 86_400_000L
    return observations
        .filter { !it.deleted && it.type == MemoryType.OBSERVATION }
        .groupBy { normalizeKey(it.content) }
        .filterValues { it.size >= minGroupSize }
        .map { (_, group) ->
            val first = group.minOf { it.createdAt }
            val last = group.maxOf { it.lastConfirmedAt.coerceAtLeast(it.createdAt) }
            val spanDays = ((last - first) / dayMs).coerceAtLeast(0)
            val content = group.first().content.trim().replace(Regex("\\s+"), " ")
            ConsolidatedMemory(
                content = when {
                    spanDays >= 7 -> "过去 ${spanDays} 天持续观察到：$content"
                    else -> "最近多次观察到：$content"
                },
                sourceMemoryIds = group.map { it.id }.distinct(),
                firstSeenAt = first,
                lastSeenAt = last,
                occurrenceCount = group.size,
            )
        }
        .sortedWith(
            compareByDescending<ConsolidatedMemory> { it.occurrenceCount }
                .thenByDescending { it.lastSeenAt },
        )
        .take(maxConsolidated)
}
