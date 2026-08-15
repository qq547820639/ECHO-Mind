package com.yunjue.echo.mind.intelligence

/**
 * ERA 5 — EchoContextCompiler（核心资产，Master Prompt PART 31/32/33）。
 *
 * 任何 LLM 请求前必须经过它：Task → Context Policy → 检索证据 →
 * 剔除禁止数据 → 应用 Privacy Budget → 编译最小上下文 → Provider。
 *
 * 模型默认**不拥有数据库访问权**；Personal AI 的价值来自 Relevant context，
 * 不是 Maximum context。
 */

/** ERA 15 §70 — EchoEvidence 统一 schema（已脱敏、已按类别归类的结构化事实）。 */
enum class EvidenceSensitivity { PERSONAL, SENSITIVE }

data class EvidenceItem(
    val category: DataSourceCategory,
    val label: String,
    val text: String,
    val id: String = "",
    val type: String = "", // observation | memory | correction | context_exception
    val timeRange: String? = null,
    val source: String = "",
    val value: String? = null,
    val baseline: String? = null,
    val comparison: String? = null,
    val confidence: Float = 0.5f,
    val provenance: String = "",
    val sensitivity: EvidenceSensitivity = EvidenceSensitivity.PERSONAL,
)

/** 编译结果（system 指令 + 用户内容 + 来源清单，供「依据」UI 使用）。 */
data class CompiledContext(
    val systemInstruction: String,
    val userContent: String,
    val usedSources: List<DataSourceCategory>,
    val excludedSources: List<DataSourceCategory>,
)

object EchoContextCompiler {

    /**
     * ECHO 人格系统指令（PERSONA Contract 的机器执行版；任何 Provider 都必须执行）。
     * 原则：Evidence before interpretation / Never pretend certainty /
     * User self-report outranks inference / Do not pathologize / Do not moralize /
     * Respect silence / Explain when asked。
     */
    const val SYSTEM_INSTRUCTION =
        "你是 ECHO，一个生活在用户手机里的个人 AI。你的价值来自理解用户自己的生活节律，而不是通用知识。" +
            "规则：1) 先陈述证据再给解释，证据来自用户给你的材料，绝不编造数据；" +
            "2) 不确定就说「还不确定」，绝不假装确定；" +
            "3) 用户对自己的陈述永远优先于你的推断；" +
            "4) 不把普通行为病理化、不做任何医学/心理诊断、不评判好坏；" +
            "5) 只用行为观察语言（如「开始得晚一些」「屏幕互动更零散」），" +
            "禁止使用：焦虑、抑郁、孤独、压力过大、情绪低落、社交退缩、心理异常、精神疾病等推断性词汇；" +
            "6) 回答用中文，简短、克制、像陪伴而不是监控；" +
            "7) 每条判断都要能回答「为什么这么说」。"

    /**
     * 编译最小上下文（纯函数，JVM 可测）。
     *
     * @param question 用户问题（ANSWER_PERSONAL_QUESTION 等任务时给出；null = 无显式问题）
     */
    fun compile(
        task: ReasoningTaskId,
        evidence: List<EvidenceItem>,
        question: String? = null,
    ): CompiledContext {
        val policy = contextPolicyFor(task)

        // 1. 剔除禁止数据 + 不在 allowed 内的数据（最小权限原则）
        val (kept, excluded) = evidence.partition {
            it.category !in policy.prohibited && it.category in policy.allowed
        }

        // 2. ERA 15 §68/§69：Context Ranking + Budget（证据/记忆/token 三重上限）
        val ranked = ContextRanker.rank(task, kept).map { it.item }
        val memoryItems = ranked.filter { it.type == "memory" || it.type == "correction" || it.type == "context_exception" }
        val nonMemoryItems = ranked.filter { it !in memoryItems }
        val budgeted = nonMemoryItems.take(policy.maxEvidenceItems) + memoryItems.take(policy.maxMemories)
        // token 预算：保守 3 字符/token；超限截断文本
        var tokens = 0
        val capped = budgeted.mapNotNull { item ->
            val cost = item.text.length / 3 + 20
            if (tokens + cost > policy.maxTokens) {
                val room = ((policy.maxTokens - tokens) * 3).coerceAtLeast(0)
                if (room <= 12) return@mapNotNull null
                tokens += policy.maxTokens - tokens
                item.copy(text = item.text.take(room) + "…")
            } else {
                tokens += cost
                item
            }
        }

        // 3. 编译最小上下文：只有结构化事实，不塞原始数据
        val evidenceBlock = if (capped.isEmpty()) {
            "（今天没有可用的节律数据。）"
        } else {
            // ERA 59（§70 收官）：baseline/comparison 字段显式进入编译文本（结构化对比，非合并长句）
            capped.joinToString("\n") { item ->
                val contrast = buildString {
                    if (!item.baseline.isNullOrBlank()) append("（平常：${item.baseline}）")
                    if (!item.comparison.isNullOrBlank()) append("（变化：${item.comparison}）")
                }
                "- [${item.label}] ${item.text}$contrast"
            }
        }
        val questionBlock = question?.takeIf { it.isNotBlank() }?.let { "用户的问题：$it\n" } ?: ""
        val taskHint = when (task) {
            ReasoningTaskId.GENERATE_NOW_INTERPRETATION ->
                "任务：基于以上证据，用一句话描述用户「现在/今天」的节律状态。"
            ReasoningTaskId.ANSWER_PERSONAL_QUESTION ->
                "任务：基于以上证据回答用户的问题；证据不足就明确说不足。"
            else -> "任务：基于以上证据给出简短、可追溯的回答。"
        }
        val schemaHint = if (policy.structuredOutput) {
            "只输出 JSON：{\"statement\":\"一句话\",\"confidence\":0到1之间的数,\"evidence_count\":整数}，不要输出其他文字。"
        } else {
            ""
        }

        return CompiledContext(
            systemInstruction = SYSTEM_INSTRUCTION,
            userContent = "$questionBlock$evidenceBlock\n\n$taskHint\n$schemaHint",
            usedSources = capped.map { it.category }.distinct(),
            excludedSources = excluded.map { it.category }.distinct(),
        )
    }
}
