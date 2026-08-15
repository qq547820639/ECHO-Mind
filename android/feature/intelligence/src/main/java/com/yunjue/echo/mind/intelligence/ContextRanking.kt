package com.yunjue.echo.mind.intelligence

/**
 * ERA 15 §68 — Context Ranking（证据排序）。
 *
 * 优先层级（§75 记忆排序同源）：
 *   1. USER_CORRECTIONS（用户纠正永远最高）
 *   2. CONTEXT_EXCEPTIONS（用户解释的特殊时期）
 *   3. PREFERENCES / BASELINE（用户确认事实与基线）
 *   4. PORTRAIT_HISTORY / TODAY_AGGREGATE（观察事实）
 * 同级再按：confidence → 时间新鲜度（timeRange 有值优先）→ 文本长度（信息密度）。
 *
 * ERA 55（ADR-059 第 1 轮）：`task` 参数此前为死参数（§61 式审计发现）——
 * 现实现 §68 的 task relevance 维度：任务→证据类粗粒度亲和加成（+0.5 封顶，
 * 远低于跨 tier 的 10 分差——**亲和只影响同 tier 内部排序，永不跨越纠错/上下文例外优先层级**）。
 * 细粒度任务相关性仍由分类策略在检索层（EvidenceAssembler/策略）决定。
 */
object ContextRanker {

    data class RankedEvidence(val item: EvidenceItem, val score: Float)

    private fun tier(category: DataSourceCategory): Int = when (category) {
        DataSourceCategory.USER_CORRECTIONS -> 5
        DataSourceCategory.CONTEXT_EXCEPTIONS -> 4
        DataSourceCategory.PREFERENCES -> 3
        DataSourceCategory.BASELINE -> 3
        DataSourceCategory.PORTRAIT_HISTORY -> 2
        DataSourceCategory.TODAY_AGGREGATE -> 2
        else -> 1
    }

    /** 任务 → 证据类粗粒度亲和（§68 task relevance；0.5 封顶，不跨 tier）。 */
    private fun taskAffinity(task: ReasoningTaskId, category: DataSourceCategory): Float = when (task) {
        ReasoningTaskId.EXPLAIN_CURRENT_STATE,
        ReasoningTaskId.GENERATE_NOW_INTERPRETATION,
        -> if (category == DataSourceCategory.TODAY_AGGREGATE) 0.5f else 0f
        ReasoningTaskId.FIND_LONGITUDINAL_PATTERN,
        ReasoningTaskId.SUMMARIZE_WEEK,
        ReasoningTaskId.SUMMARIZE_MONTH,
        -> if (category == DataSourceCategory.PORTRAIT_HISTORY) 0.5f else 0f
        ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
        -> if (category == DataSourceCategory.PREFERENCES) 0.3f else 0f
        ReasoningTaskId.PROPOSE_ACTION,
        -> if (category == DataSourceCategory.CONTEXT_EXCEPTIONS) 0.3f else 0f
        else -> 0f
    }

    /** 返回按优先级排序后的证据（原列表顺序被打断，输出确定性）。 */
    fun rank(task: ReasoningTaskId, items: List<EvidenceItem>): List<RankedEvidence> =
        items.map { item ->
            val tierScore = tier(item.category) * 10f
            val confidence = item.confidence.coerceIn(0f, 1f)
            val timeScore = if (item.timeRange != null) 1f else 0f
            val density = (120 - item.text.length.coerceAtMost(120)) / 120f * 0.5f
            RankedEvidence(item, tierScore + confidence * 2f + timeScore + density + taskAffinity(task, item.category))
        }.sortedByDescending { it.score }
}
