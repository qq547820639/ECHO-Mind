package com.yunjue.echo.mind.intelligence

/**
 * ERA 15 §71/§72 — EchoAnswer schema + Grounding Validator。
 *
 * 检查（§72）：
 * - Evidence exists（AI 叙事必须有证据，否则降级）；
 * - Confidence valid（0..1）；
 * - Observed / Interpreted / Felt 边界（禁止推断性词表）；
 * - Affective restrictions（affective 未 opt-in 时禁止情绪断言）。
 */
enum class InterpretationLevel {
    /** 观察事实（OBSERVED：数据直接陈述）。 */
    OBSERVATION,

    /** 解释（INTERPRETED：基于证据的模式解释）。 */
    INTERPRETATION,

    /** 用户自述（FELT/USER_STATEMENT：用户自己说的，最高优先）。 */
    USER_STATEMENT,
}

data class EchoAnswer(
    val text: String,
    val evidenceIds: List<String> = emptyList(),
    val confidence: Float = 0f,
    val interpretationLevel: InterpretationLevel = InterpretationLevel.OBSERVATION,
    val timeRange: String? = null,
    val provider: String = "openai-compatible",
    val model: String? = null,
    val fallbackUsed: Boolean = false,
)

data class GroundingReport(
    val passed: Boolean,
    val problems: List<String>,
    val interpretationLevel: InterpretationLevel,
    val citedEvidenceIds: List<String>,
)

object GroundingValidator {

    /** 推断性/情感断言禁词（§72 Felt 边界 + PERSONA 契约第 5 条）。 */
    private val BANNED_INFERENCE_WORDS = listOf(
        "焦虑", "抑郁", "孤独", "压力过大", "情绪低落", "社交退缩", "心理异常", "精神疾病",
        "emo", "崩溃了", "很痛苦",
    )

    /**
     * ERA 22 §29 — claim-evidence compatibility：
     * 心理/状态断言词只有「用户自己说过」才能出现在回答里。
     * 证据只是行为观察（如「屏幕使用晚 40 分钟」）时，模型不得生成「你最近压力很大」。
     */
    private val CLAIM_WORDS_REQUIRING_USER_STATEMENT = listOf(
        "压力", "疲惫", "累垮", "沮丧", "失眠", "心烦", "崩溃", "心情不好", "情绪不好", "撑不住",
    )

    /** 用户自述证据类型（只有这些来源里的原话可以支撑心理/状态断言）。 */
    private val USER_STATEMENT_TYPES = setOf("correction", "context_exception", "memory")

    fun validate(
        text: String,
        evidence: List<EvidenceItem>,
        fallbackLevel: NarrativeFallbackLevel,
    ): GroundingReport {
        val problems = mutableListOf<String>()

        // 1. 情感/推断禁词（任何层级都不允许出现）
        // 「emo」是拉丁词，用单词边界避免误伤 automatic/system/emotion 等含子串的词；
        // 中文词组天然具备边界，plain contains 足够。
        if (Regex("\\bemo\\b").containsMatchIn(text))
            problems.add("违反 Felt 边界（推断性词汇：emo）")
        for (word in BANNED_INFERENCE_WORDS) {
            if (word != "emo" && text.contains(word))
                problems.add("违反 Felt 边界（推断性词汇：$word）")
        }
        if (com.yunjue.echo.mind.model.containsBlockedVocabulary(text)) {
            problems.add("命中产品禁词表（containsBlockedVocabulary）")
        }

        // 1.5 ERA 22 §29 claim-evidence compatibility：状态断言必须有用户自述支撑
        for (word in CLAIM_WORDS_REQUIRING_USER_STATEMENT) {
            if (text.contains(word)) {
                val supported = evidence.any {
                    it.type in USER_STATEMENT_TYPES && it.text.contains(word)
                }
                if (!supported) {
                    problems.add("claim-evidence compatibility 失败：文本断言「$word」但证据中没有用户自述支持")
                }
            }
        }

        // 2. Evidence exists：AI 叙事必须有证据
        val meaningful = evidence.filter { it.text.isNotBlank() }
        if (fallbackLevel == NarrativeFallbackLevel.AI_NARRATIVE && meaningful.isEmpty()) {
            problems.add("AI 叙事缺少证据支撑（Evidence exists 失败）")
        }

        // 3. Confidence valid
        if (evidence.any { it.confidence !in 0f..1f }) {
            problems.add("证据置信度越界（0..1）")
        }

        // 4. 解释层级：AI 叙事 = INTERPRETATION；观察事实 = OBSERVATION
        val level = when (fallbackLevel) {
            NarrativeFallbackLevel.AI_NARRATIVE -> InterpretationLevel.INTERPRETATION
            else -> InterpretationLevel.OBSERVATION
        }

        // 5. 证据引用（id 或 label/text 命中）
        val cited = evidence.filter { e ->
            e.id.isNotBlank() && (text.contains(e.id) || text.contains(e.label.take(6)))
        }.mapNotNull { it.id.ifBlank { null } }

        return GroundingReport(
            passed = problems.isEmpty(),
            problems = problems,
            interpretationLevel = level,
            citedEvidenceIds = cited,
        )
    }

    /** 生成校验后的 EchoAnswer（§71；校验失败时以 observation 降级文案兜底）。 */
    fun buildAnswer(
        text: String,
        evidence: List<EvidenceItem>,
        fallbackLevel: NarrativeFallbackLevel,
        model: String? = null,
    ): EchoAnswer {
        val report = validate(text, evidence, fallbackLevel)
        // ERA 59（§71 收官）：timeRange 由被引用证据的时间范围导出（最早~最晚；无则 null）
        val citedTimeRanges = evidence
            .filter { e -> e.id.isNotBlank() && e.id in report.citedEvidenceIds }
            .mapNotNull { it.timeRange }
            .distinct()
            .sorted()
        val timeRange = when (citedTimeRanges.size) {
            0 -> null
            1 -> citedTimeRanges.single()
            else -> "${citedTimeRanges.first()}~${citedTimeRanges.last()}"
        }
        return EchoAnswer(
            text = if (report.passed) text
            else "我能确定的事实是：${evidence.filter { it.type == "observation" }.take(3).joinToString("；") { it.text }.ifBlank { "当前证据不足。" }}",
            evidenceIds = report.citedEvidenceIds,
            confidence = if (report.passed) 0.5f else 0.2f,
            interpretationLevel = report.interpretationLevel,
            timeRange = timeRange,
            provider = "openai-compatible",
            model = model,
            fallbackUsed = !report.passed,
        )
    }
}
