package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.PORTRAIT_TREND_DIMENSIONS
import com.yunjue.echo.mind.presence.EchoVisualParameters

/**
 * ERA 16 §81/§82 — 五个尺度（Day/Week/Month/Season/Year），每层都有：
 *
 * - Visual（聚合视觉参数）
 * - Facts（事实摘要）
 * - Patterns（窗口内重复出现的维度规律）
 * - Exceptions（窗口内命中上下文例外）
 * - Narrative（叙事文本，由应用层注入；AI 失败 = deterministic）
 * - Evidence（证据 id 列表）
 *
 * 纯函数装配；任何一层无数据时如实为空（不编造）。
 */

/** 一个尺度层的完整记忆结构（§82 六要素）。 */
data class JourneyLayer(
    val scale: JourneyScale,
    val visual: EchoVisualParameters?,
    val facts: List<String>,
    val patterns: List<String>,
    val exceptions: List<String>,
    val narrative: String?,
    val evidenceIds: List<String>,
)

/** 窗口证据 id：只有实际存在视觉参数的日期才产生证据（portrait:yyyy-MM-dd）。 */
fun journeyEvidenceIds(days: List<JourneyDay>): List<String> =
    days.filter { it.visualParams != null }.map { "portrait:${it.date}" }

/** 窗口事实摘要：取每日 headline + summary（去重、去空、限量）。 */
fun journeyWindowFacts(days: List<JourneyDay>, limit: Int = 8): List<String> =
    days.asSequence()
        .flatMap { sequenceOf(it.headline, it.summary) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(limit)
        .toList()

/**
 * 窗口模式：对每个趋势维度统计最常出现的值（出现天数 ≥ 2 才算模式）。
 * 输出中性句式「N 天中有 M 天 RHYTHM 相似」；无重复 → 空列表。
 */
fun deriveWindowPatterns(days: List<JourneyDay>): List<String> {
    if (days.size < 2) return emptyList()
    val patterns = mutableListOf<String>()
    for (dimension in PORTRAIT_TREND_DIMENSIONS) {
        val counts = days
            .mapNotNull { it.dimensionValues[dimension] }
            .groupingBy { it }
            .eachCount()
        val dominant = counts.maxByOrNull { it.value } ?: continue
        if (dominant.value >= 2) {
            patterns.add("${days.size} 天中有 ${dominant.value} 天 $dimension ${dimensionValueLabel(dominant.key)}")
        }
    }
    return patterns
}

/** 维度值 → 中性中文标签（仅覆盖画像时间线实际使用的值）。 */
fun dimensionValueLabel(value: String): String = when (value) {
    "SIMILAR", "VERY_SIMILAR" -> "相似"
    "MORE" -> "更多"
    "LESS" -> "更少"
    "EARLIER" -> "更早"
    "LATER" -> "更晚"
    "MORE_FRAGMENTED" -> "更碎片化"
    "MORE_CONCENTRATED" -> "更集中"
    "IRREGULAR" -> "不规律"
    else -> value
}

/**
 * §81/§82 — 装配某一尺度的完整 JourneyLayer（纯函数）。
 *
 * @param contextExceptions date → kind（窗口内命中日期进入 exceptions）
 * @param narrative 应用层叙事（AI 或 deterministic fallback；null = 无叙事）
 */
fun assembleJourneyLayer(
    scale: JourneyScale,
    days: List<JourneyDay>,
    contextExceptions: Map<String, String>,
    narrative: String?,
): JourneyLayer {
    val sorted = days.sortedBy { it.date }
    val dateSet = sorted.map { it.date }.toSet()
    val exceptions = contextExceptions.entries
        .filter { it.key in dateSet }
        .map { (date, kind) -> "$date ${contextExceptionLabel(kind)}" }
        .sorted()
    return JourneyLayer(
        scale = scale,
        visual = journeyAggregateOfDays(sorted),
        facts = journeyWindowFacts(sorted),
        patterns = deriveWindowPatterns(sorted),
        exceptions = exceptions,
        narrative = narrative,
        evidenceIds = journeyEvidenceIds(sorted),
    )
}

/** 上下文例外 kind → 中性中文标签（与 Me 页同一词表；未知 kind 原样展示）。 */
fun contextExceptionLabel(kind: String): String = when (kind) {
    "travel" -> "旅行"
    "holiday" -> "休假"
    "work_crunch" -> "工作紧张期"
    "exam" -> "考试"
    "illness" -> "生病"
    "event" -> "特殊事件"
    "user_defined", "user-defined", "other" -> "其他特殊时期"
    else -> kind
}
