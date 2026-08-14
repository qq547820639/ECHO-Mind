package com.yunjue.echo.mind.intelligence

/**
 * ERA 5 — AI Narrative Service（AI 叙事编排 + fallback 链，Master Prompt PART 75/76）。
 *
 * 铁律：**AI 失败不得破坏 ECHO**。
 * - 无 Provider / 网络失败 / key 错误 / quota / 结构化输出失败 →
 *   降级到确定性叙事 → 再降级到观察事实；用户永远不面对空白；
 * - AI 输出必须过领域校验（词表门禁）；不合格同样降级（不是重试到天荒地老）；
 * - Provider 依赖注入为函数（JVM 可测；生产接 AiProviderManager）。
 */
class AiNarrativeService(
    private val hasProvider: () -> Boolean,
    private val reason: suspend (EchoReasoningRequest) -> EchoReasoningResponse,
    private val networkAvailable: () -> Boolean = { true },
) {

    /** 叙事结果：层级（供 UI 决定是否展示 AI 徽标）+ 文本 + 依据来源。 */
    data class NarrativeResult(
        val level: NarrativeFallbackLevel,
        val text: String,
        val usedSources: List<DataSourceCategory> = emptyList(),
    )

    /**
     * 今日一句话：AI 叙事 → deterministic 叙事 → 观察事实。
     *
     * @param evidence 已脱敏证据（按相关性排序）
     * @param deterministicText 确定性叙事（现有画像 headline/summary）
     * @param factsText 观察事实（确定性叙事不可用时的兜底）
     */
    suspend fun nowNarrative(
        evidence: List<EvidenceItem>,
        deterministicText: String,
        factsText: String,
    ): NarrativeResult {
        if (!hasProvider() || !networkAvailable()) {
            return deterministicResult(deterministicText, factsText)
        }

        val compiled = EchoContextCompiler.compile(ReasoningTaskId.GENERATE_NOW_INTERPRETATION, evidence)
        val response = reason(
            EchoReasoningRequest(
                task = ReasoningTaskId.GENERATE_NOW_INTERPRETATION.name,
                systemInstruction = compiled.systemInstruction,
                userContent = compiled.userContent,
                outputSchemaHint = "now-narrative-v1",
            )
        )

        if (response.status != ProviderStatus.READY) {
            return when (narrativeFallbackFor(response.status)) {
                NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE -> deterministicResult(deterministicText, factsText)
                else -> NarrativeResult(NarrativeFallbackLevel.OBSERVATION_FACTS, factsText)
            }
        }

        val parsed = StructuredOutputValidator.parseNowNarrative(response.structuredJson ?: response.text)
        if (parsed == null || !StructuredOutputValidator.isSafeNarrative(parsed.statement)) {
            // 结构化输出失败/词表否决 → 降级（不无限重试；重试策略属于 Provider 层）
            return deterministicResult(deterministicText, factsText)
        }

        return NarrativeResult(
            level = NarrativeFallbackLevel.AI_NARRATIVE,
            text = parsed.statement,
            usedSources = compiled.usedSources,
        )
    }

    /**
     * 回答个人问题（ERA 7 Ask ECHO）：grounded 回答 + 依据；
     * 证据不足时明确说不足，禁止编造。
     */
    suspend fun answerQuestion(
        question: String,
        evidence: List<EvidenceItem>,
        conversationHistory: List<EvidenceItem> = emptyList(),
    ): NarrativeResult {
        if (!hasProvider() || !networkAvailable()) {
            return NarrativeResult(
                level = NarrativeFallbackLevel.OBSERVATION_FACTS,
                text = "我现在还不能回答这个问题：还没有连接 AI，或者当前没有网络。",
                usedSources = emptyList(),
            )
        }
        val all = evidence + conversationHistory
        val compiled = EchoContextCompiler.compile(
            ReasoningTaskId.ANSWER_PERSONAL_QUESTION,
            all,
            question = question,
        )
        val response = reason(
            EchoReasoningRequest(
                task = ReasoningTaskId.ANSWER_PERSONAL_QUESTION.name,
                systemInstruction = compiled.systemInstruction,
                userContent = compiled.userContent,
            )
        )
        if (response.status != ProviderStatus.READY) {
            return NarrativeResult(
                level = NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                text = "我现在暂时想不清楚（${providerStatusText(response.status)}）。等你稍后再问，我会基于你的节律数据回答。",
                usedSources = emptyList(),
            )
        }
        val text = response.text.trim()
        val grounding = GroundingValidator.validate(text, evidence, NarrativeFallbackLevel.AI_NARRATIVE)
        if (text.isBlank() || !StructuredOutputValidator.isSafeNarrative(text) || !grounding.passed) {
            return NarrativeResult(
                level = NarrativeFallbackLevel.OBSERVATION_FACTS,
                text = "我能确定的事实是：${evidence.filter { it.type == "observation" }.take(3).joinToString("；") { it.text }.ifBlank { "当前证据不足，先继续陪伴。" }}",
                usedSources = evidence.map { it.category }.distinct(),
            )
        }
        return NarrativeResult(
            level = NarrativeFallbackLevel.AI_NARRATIVE,
            text = text,
            usedSources = compiled.usedSources,
        )
    }

    /**
     * 长期变化叙事（ERA 8 Journey）：FIND_LONGITUDINAL_PATTERN；
     * AI 失败 → 确定性综述（如「最接近：作息；变化较明显：屏幕总量」）。
     */
    suspend fun longitudinalNarrative(
        evidence: List<EvidenceItem>,
        deterministicText: String,
    ): NarrativeResult {
        if (!hasProvider() || !networkAvailable()) {
            return NarrativeResult(
                level = NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                text = deterministicText,
            )
        }
        val compiled = EchoContextCompiler.compile(ReasoningTaskId.FIND_LONGITUDINAL_PATTERN, evidence)
        val response = reason(
            EchoReasoningRequest(
                task = ReasoningTaskId.FIND_LONGITUDINAL_PATTERN.name,
                systemInstruction = compiled.systemInstruction,
                userContent = compiled.userContent,
            )
        )
        if (response.status != ProviderStatus.READY) {
            return NarrativeResult(
                level = NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                text = deterministicText,
            )
        }
        val text = response.text.trim()
        if (text.isBlank() || !StructuredOutputValidator.isSafeNarrative(text)) {
            return NarrativeResult(
                level = NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE,
                text = deterministicText,
            )
        }
        return NarrativeResult(
            level = NarrativeFallbackLevel.AI_NARRATIVE,
            text = text,
            usedSources = compiled.usedSources,
        )
    }

    private fun deterministicResult(deterministicText: String, factsText: String): NarrativeResult {
        val text = deterministicText.ifBlank { factsText }
        return NarrativeResult(
            level = if (deterministicText.isNotBlank()) {
                NarrativeFallbackLevel.DETERMINISTIC_NARRATIVE
            } else {
                NarrativeFallbackLevel.OBSERVATION_FACTS
            },
            text = text,
        )
    }
}
