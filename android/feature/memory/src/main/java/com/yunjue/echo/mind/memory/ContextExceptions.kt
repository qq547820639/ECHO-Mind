package com.yunjue.echo.mind.memory

/**
 * ERA 16 §78/§86 — 上下文例外（Context Exception）结构化内容格式。
 *
 * 用户自述「最近在出差」进入 CONTEXT 记忆（§79 用户解释优先）。
 * 为了让 Journey 年视图能画出「特殊阶段」的时间范围，内容携带可选日期：
 *
 *   特殊时期：<kind>（<note>）@<yyyy-MM-dd>
 *
 * - 无日期时仍是合法上下文记忆（不进入 Journey 时间线，但推理仍可检索）；
 * - 解析失败一律返回 null（不编造日期）。
 */

/** 解析后的上下文例外（kind / note / date 三要素）。 */
data class ContextExceptionInfo(
    val kind: String,
    val note: String,
    val date: String?,
)

/** 组装上下文例外记忆内容（kind 为空回退「其他」）。 */
fun contextExceptionContent(kind: String, note: String, date: String?): String {
    val body = buildString {
        append("特殊时期：")
        append(kind.ifBlank { "其他" })
        if (note.isNotBlank()) {
            append('（')
            append(note)
            append('）')
        }
    }
    val normalizedDate = date?.trim()
    return if (normalizedDate.isNullOrBlank() || !CONTEXT_DATE_REGEX.matches(normalizedDate)) body
    else "$body@$normalizedDate"
}

/** 解析上下文例外记忆内容；非该格式 → null。 */
fun contextExceptionInfo(content: String): ContextExceptionInfo? {
    val match = CONTEXT_EXCEPTION_REGEX.matchEntire(content.trim()) ?: return null
    return ContextExceptionInfo(
        kind = match.groupValues[1].trim(),
        note = match.groupValues[2].trim(),
        date = match.groupValues[3].ifEmpty { null },
    )
}

/** 上下文例外 kind → 中性中文标签（Journey / Me 共用词表）。 */
fun contextExceptionKindLabel(kind: String): String = when (kind) {
    "travel" -> "旅行"
    "holiday" -> "休假"
    "work_crunch" -> "工作紧张期"
    "exam" -> "考试"
    "illness" -> "生病"
    "event" -> "特殊事件"
    "user_defined", "user-defined", "other" -> "其他特殊时期"
    else -> kind
}

private val CONTEXT_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

// T5-P2-3：note 为自由文本，可含（ ）@（如「见客户（重要）@公司」）——
// 解析按结构而非字符排除：kind 到首个（为止；note 贪婪匹配到最后一个）；
// 日期仅认结尾的 @yyyy-MM-dd。贪婪回溯取「最长 note + 合法尾缀」切分，
// 与编码侧组装结构（note 原样夹在（ ）内、@date 可选后缀）roundtrip 闭合。
private val CONTEXT_EXCEPTION_REGEX = Regex(
    """特殊时期：([^（]+?)(?:（(.*)）)?(?:@(\d{4}-\d{2}-\d{2}))?"""
)
