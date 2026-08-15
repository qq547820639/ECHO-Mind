package com.yunjue.echo.mind.memory

/**
 * ERA 32 R03 — 记忆内容人话化（单一事实源）。
 *
 * 内部存储格式（画像反馈：不太像（原因：…）（原判断：…）/ 问答反馈：像我（问：…）/
 * 特殊时期：<kind>（<note>）@<date>）不得进入任何用户可见回答与 AI Provider 上下文：
 * - 确定性回答路径（DeterministicPersonalAnswerProvider，ERA 31 R37 起）
 * - AI Provider 路径（EvidenceAssembler，ERA 32 R03 起——此前只修了确定性路径）
 * 两处同源调用本文件，禁止各自实现第二份解析。
 */
object MemoryHumanizer {

    /** ERA 31 R37：纠正记忆内部格式前缀（解析锚点）。 */
    const val CORRECTION_REASON_PREFIX = "画像反馈：不太像（原因："

    /** 「画像反馈：不太像（原因：旅行）（原判断：…）」→「旅行——当时我说的是「…」」。 */
    fun humanizeCorrection(content: String): String {
        val reason = content
            .substringAfter(CORRECTION_REASON_PREFIX, missingDelimiterValue = "")
            .substringBefore("）（原判断", missingDelimiterValue = "")
            .substringBefore("）")
            .ifBlank { content.take(24) }
        val original = content
            .substringAfter("原判断：", missingDelimiterValue = "")
            .substringBefore("）")
        return if (original.isNotBlank()) "$reason——当时我说的是「${original.take(40)}」" else reason
    }

    /** 「问答反馈：像我（问：…）」→「问「…」的回答」；未知格式取前 40 字兜底。 */
    fun humanizeConfirmed(content: String): String {
        val prefix = "问答反馈：像我（问："
        return if (content.startsWith(prefix)) {
            "问「${content.removePrefix(prefix).removeSuffix("）").take(40)}」的回答"
        } else {
            content.take(40)
        }
    }

    /** 「特殊时期：旅行（见客户）@2026-01-25」→「旅行：见客户（从 2026-01-25 开始）」。 */
    fun humanizeContext(content: String): String {
        val info = contextExceptionInfo(content) ?: return content.take(24)
        val note = info.note.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
        val date = info.date?.let { "（从 $it 开始）" } ?: ""
        return "${info.kind}$note$date".ifBlank { content.take(24) }
    }
}
