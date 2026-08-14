package com.yunjue.echo.mind.intelligence

/**
 * ERA 15 §67 — Question Classification（正式六分类）。
 *
 * 用户自由文本 → 推理任务 + 置信度 + 命中信号；纯函数（JVM 可测）。
 * 未命中任何信号 → ANSWER_PERSONAL_QUESTION（默认安全类）。
 */
object QuestionClassifier {

    data class Classification(
        val task: ReasoningTaskId,
        val confidence: Float,
        val matchedSignals: List<String>,
    )

    /** 信号词（命中即加权；顺序即优先级：先匹配先得）。 */
    private val SIGNALS: List<Pair<ReasoningTaskId, List<String>>> = listOf(
        ReasoningTaskId.SUMMARIZE_MONTH to listOf("这个月", "本月", "月总结", "月度"),
        ReasoningTaskId.SUMMARIZE_WEEK to listOf("这周", "本周", "周总结", "一周"),
        ReasoningTaskId.FIND_LONGITUDINAL_PATTERN to listOf(
            "最近", "这段时间", "变化", "趋势", "以前", "过去", "越来越", "长期", "原来",
        ),
        ReasoningTaskId.EXPLAIN_CURRENT_STATE to listOf(
            "现在", "今天", "此刻", "为什么不一样", "怎么回事", "当前", "状态",
        ),
        ReasoningTaskId.PROPOSE_ACTION to listOf(
            "建议", "怎么办", "该做什么", "推荐", "帮我想想", "怎么做",
        ),
        ReasoningTaskId.ANSWER_PERSONAL_QUESTION to listOf(
            "我", "我的", "习惯", "喜欢", "了解", "什么样",
        ),
    )

    fun classify(question: String): Classification {
        val q = question.trim()
        if (q.isBlank()) return Classification(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 0f, emptyList())
        val matched = mutableListOf<String>()
        for ((task, keywords) in SIGNALS) {
            for (kw in keywords) {
                if (q.contains(kw)) {
                    matched.add(kw)
                    val confidence = (matched.size.toFloat() / 3f).coerceIn(0.34f, 0.95f)
                    return Classification(task, confidence, matched.toList())
                }
            }
        }
        return Classification(ReasoningTaskId.ANSWER_PERSONAL_QUESTION, 0.34f, emptyList())
    }
}
