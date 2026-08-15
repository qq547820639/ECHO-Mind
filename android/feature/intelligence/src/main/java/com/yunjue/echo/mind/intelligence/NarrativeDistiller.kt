package com.yunjue.echo.mind.intelligence

/**
 * ERA 22 §30/§31 — Narrative Distillation（Evidence → Reasoning → Distillation → ECHO voice 的最后一环）。
 *
 * AI 输出不直接展示原始 response：无论 Provider 是谁、模型风格如何，
 * 蒸馏后的文本必须落在 ECHO voice 的约束内：
 * - 剥掉 Provider 招牌句式（句首填充语 + 句尾「希望/如果/请问/基于」式礼貌句）；
 * - 去感叹号堆叠、波浪线与装饰符号；
 * - 「您」→「你」（陪伴口吻，非客服口吻）；
 * - 最多保留 2 句（克制；AI 输出更长 ≠ 更懂用户）；
 * - 确定性：同一输入恒同输出（换模型后同一意思的文本蒸馏结果一致 → 人格稳定）。
 */
object NarrativeDistiller {

    /** 句首填充语（按长度降序匹配，可重复剥离）。 */
    private val PREFIX_FILLER = Regex(
        "^(作为一个ai助手|作为一个ai|作为一个人工智能|根据数据分析|根据数据显示|数据显示|数据表明|" +
            "综上所述|以下是对你的观察|以下是|基于以上|我认为|我注意到|哇|呀|嘿|啊|哦)[！!，,：: ]*",
        RegexOption.IGNORE_CASE,
    )

    /** 句尾礼貌/招牌从句（从这些词起截断到结尾；从句内部可含句号）。 */
    private val TRAILING_CLAUSE = Regex("(希望|如果|如有|请问|基于|下面)[^！？!?]*$")

    fun distill(raw: String): String {
        var text = raw.trim()

        // 1. 句尾礼貌/招牌从句整段剥离
        text = text.replace(TRAILING_CLAUSE, "")
        // 1.5 连接词残片（综上所述/总而言之）全局清除
        text = text.replace("综上所述", "").replace("总而言之", "")

        // 2. 句首填充语反复剥离（「作为一个AI助手，我认为…」类嵌套）
        var changed = true
        while (changed) {
            val before = text
            text = PREFIX_FILLER.replace(text, "")
            changed = text != before
        }

        // 3. 去装饰：感叹号堆叠/波浪线/表情符号/多余空白/重复句号
        text = text.replace(Regex("[!！]{2,}"), "。")
            .replace(Regex("[~～]{2,}"), "")
            .replace(Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]"), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[。]{2,}"), "。")

        // 4. 「您」→「你」（ECHO 是陪伴者，不是客服）
        text = text.replace("您", "你")

        // 5. 分句（保留结尾标点语义；纯标点残句丢弃）
        val sentences = text.split(Regex("(?<=[。！？!?])"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.any { c -> c !in "，,、；;：:。" } }
        val capped = sentences.take(MAX_SENTENCES)

        // 6. 规范结尾：最后一句以 。 收尾（疑问句除外）
        val joined = capped.joinToString("")
        val normalized = when {
            joined.isEmpty() -> ""
            joined.last() in setOf('。', '？', '!', '！') -> joined
            else -> "$joined。"
        }
        return normalized.trim().take(MAX_CHARS).trim()
    }

    const val MAX_SENTENCES = 2
    const val MAX_CHARS = 80
}
