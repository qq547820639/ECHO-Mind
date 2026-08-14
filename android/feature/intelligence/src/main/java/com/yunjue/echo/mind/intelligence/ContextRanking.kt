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

    /** 返回按优先级排序后的证据（原列表顺序被打断，输出确定性）。 */
    fun rank(task: ReasoningTaskId, items: List<EvidenceItem>): List<RankedEvidence> =
        items.map { item ->
            val tierScore = tier(item.category) * 10f
            val confidence = item.confidence.coerceIn(0f, 1f)
            val timeScore = if (item.timeRange != null) 1f else 0f
            val density = (120 - item.text.length.coerceAtMost(120)) / 120f * 0.5f
            RankedEvidence(item, tierScore + confidence * 2f + timeScore + density)
        }.sortedByDescending { it.score }
}
