package com.yunjue.echo.mind.intelligence

/**
 * ERA 15 §67 — Question Classification（六分类）+ ERA 22 §26 升级。
 *
 * v2（Product Quality Era）：从「关键词先命中先得」升级为**结构化规则分类器**：
 * - 意图优先（建议类绝不误入趋势类）；
 * - 显式周/月总结需要总结意图词，避免「这个月和上个月的区别」被误判成月报；
 * - 「我说过/记得」优先进入个人问题（用户自述召回，触发 CORRECTION/CONTEXT 检索）；
 * - 未命中 → ANSWER_PERSONAL_QUESTION（默认安全类，deterministic fallback 保留）。
 *
 * 纯函数、零依赖、provider neutral；置信度 = 命中信号数（0.34..0.95）。
 */
object QuestionClassifier {

    data class Classification(
        val task: ReasoningTaskId,
        val confidence: Float,
        val matchedSignals: List<String>,
    )

    private val ACTION_WORDS = listOf("建议", "怎么办", "该做什么", "推荐", "怎么做", "帮我想想")
    private val WEEK_WORDS = listOf("这周", "本周", "周总结", "一周", "星期", "这几天")
    private val MONTH_WORDS = listOf("这个月", "本月", "月总结", "月度", "一个月", "这一个月")
    private val MONTH_SUMMARY_WORDS = listOf("总结", "怎么样", "如何", "回顾", "说说", "讲讲")
    private val TODAY_WORDS = listOf("今天", "此刻", "现在", "当前", "今天为什么")
    private val EXPLAIN_WORDS = listOf("为什么", "怎么回事", "不一样", "状态", "怎么样", "异常", "反常")
    private val LONGITUDINAL_WORDS = listOf(
        "最近", "这段时间", "变化", "趋势", "以前", "过去", "越来越", "长期", "原来",
        "上个月", "区别", "对比", "比较", "哪几天", "最像", "相似", "半年", "这半年", "变晚", "变早", "后移", "前移",
        "周末", "工作日", "作息", "稳定", "波动",
    )
    private val PERSONAL_WORDS = listOf("我", "我的", "习惯", "喜欢", "了解", "什么样", "说过", "记得", "熟悉")

    private fun hits(q: String, words: List<String>): List<String> = words.filter { q.contains(it) }

    private fun confidenceFor(matched: List<String>): Float =
        (matched.size.toFloat() / 3f).coerceIn(0.34f, 0.95f)

    fun classify(question: String): Classification {
        val q = question.trim()
        if (q.isBlank()) return Classification(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 0f, emptyList())

        // 1. 行动意图（最高优先：建议类绝不误入其他任务）
        val actionHits = hits(q, ACTION_WORDS)
        if (actionHits.isNotEmpty()) {
            return Classification(ReasoningTaskId.PROPOSE_ACTION, confidenceFor(actionHits), actionHits)
        }

        // 2. 显式周总结（周词本身即周范围意图）
        val weekHits = hits(q, WEEK_WORDS)
        if (weekHits.isNotEmpty()) {
            return Classification(ReasoningTaskId.SUMMARIZE_WEEK, confidenceFor(weekHits), weekHits)
        }

        // 3. 显式月总结（月词 + 总结意图；「这个月和上个月的区别」走趋势类）
        val monthHits = hits(q, MONTH_WORDS)
        val monthSummaryHits = hits(q, MONTH_SUMMARY_WORDS)
        if (monthHits.isNotEmpty() && monthSummaryHits.isNotEmpty()) {
            return Classification(
                ReasoningTaskId.SUMMARIZE_MONTH,
                confidenceFor(monthHits + monthSummaryHits),
                monthHits + monthSummaryHits,
            )
        }

        // 4. 当前状态解释（今天/此刻 + 为什么/不一样/状态）
        val todayHits = hits(q, TODAY_WORDS)
        val explainHits = hits(q, EXPLAIN_WORDS)
        if (todayHits.isNotEmpty() && explainHits.isNotEmpty()) {
            return Classification(
                ReasoningTaskId.EXPLAIN_CURRENT_STATE,
                confidenceFor(todayHits + explainHits),
                todayHits + explainHits,
            )
        }

        // 4.5 用户自述召回（「我说过/记得」→ 个人问题，触发 CORRECTION/CONTEXT 检索）
        val recallHits = hits(q, listOf("说过", "记得"))
        if (recallHits.isNotEmpty()) {
            return Classification(
                ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
                confidenceFor(recallHits),
                recallHits,
            )
        }

        // 5. 纵向趋势
        val longHits = hits(q, LONGITUDINAL_WORDS)
        if (longHits.isNotEmpty()) {
            return Classification(
                ReasoningTaskId.FIND_LONGITUDINAL_PATTERN,
                confidenceFor(longHits),
                longHits,
            )
        }

        // 6. 个人问题（含默认 fallback）
        val personalHits = hits(q, PERSONAL_WORDS)
        if (personalHits.isNotEmpty()) {
            return Classification(
                ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
                confidenceFor(personalHits),
                personalHits,
            )
        }

        return Classification(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 0.34f, emptyList())
    }
}
