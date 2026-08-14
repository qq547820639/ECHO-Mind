package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.model.containsBlockedVocabulary

/**
 * ERA 5 — Structured Output 校验/修复（Master Prompt PART 74）。
 *
 * Provider 不支持 native structured output 时：validation → repair → retry → fallback。
 * 所有 AI 结果必须通过 domain validator；本文件负责：
 * 1. JSON 提取/修复（模型常把 JSON 包在解释文字里）；
 * 2. 领域校验（statement 非空、长度上限、词表门禁——心理推断词直接否决）。
 */

/** AI 生成的一日叙事（GENERATE_NOW_INTERPRETATION 的结构化输出）。 */
data class AiNowNarrative(
    val statement: String,
    val confidence: Float,
    val evidenceCount: Int,
)

object StructuredOutputValidator {

    /** 一句话叙事长度上限（克制；超长 = 模型在编故事，直接否决）。 */
    const val MAX_STATEMENT_LENGTH = 200

    /** 从原始输出里提取第一个平衡的 {…}（repair：容忍前后缀解释文字）。 */
    fun extractJsonObject(raw: String?): String? {
        if (raw == null) return null
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 解析结构化叙事（先直接解析，失败再 repair 提取；彻底失败返回 null）。 */
    fun parseNowNarrative(raw: String?): AiNowNarrative? {
        if (raw == null) return null
        val candidates = listOf(raw.trim(), extractJsonObject(raw))
        for (candidate in candidates) {
            if (candidate == null) continue
            val parsed = runCatching {
                val o = org.json.JSONObject(candidate)
                val statement = o.optString("statement").trim()
                val confidence = o.optDouble("confidence", -1.0)
                val count = o.optInt("evidence_count", -1)
                AiNowNarrative(statement, confidence.toFloat(), count)
            }.getOrNull() ?: continue
            if (parsed.statement.isNotBlank()) return parsed
        }
        return null
    }

    /**
     * 领域校验（fail-closed）：任何一条不满足即拒绝（上层走 fallback 链）。
     * - 非空、长度上限；
     * - 不含心理推断 BLOCK 词（PORTRAIT_CONTRACT_V1_OBSERVATION 门禁对 AI 同样生效）；
     * - 不含监控式语言（「检测到」「监测」）。
     */
    fun isSafeNarrative(statement: String): Boolean {
        if (statement.isBlank() || statement.length > MAX_STATEMENT_LENGTH) return false
        if (containsBlockedVocabulary(statement)) return false
        val surveillance = listOf("我检测到", "我正在监测", "我监测到你", "盯着你")
        if (surveillance.any { statement.contains(it) }) return false
        return true
    }
}
