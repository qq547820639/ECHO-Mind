package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.memory.EchoMemory
import com.yunjue.echo.mind.memory.MemoryType
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.dimensionDisplayName
import com.yunjue.echo.mind.model.dimensionValueText

/**
 * ERA 5/7 — Evidence Assembler：把领域对象转成 Context Compiler 的 EvidenceItem。
 *
 * 只产出已脱敏、可追溯的结构化事实；原始通知内容/音频永不出现在这里。
 */
object EvidenceAssembler {

    /** 画像 → 今日证据（facts 的事实句 + 维度对比；全部 TODAY_AGGREGATE）。 */
    fun fromPortrait(portrait: DailyPortraitDto?): List<EvidenceItem> {
        if (portrait == null) return emptyList()
        val items = mutableListOf<EvidenceItem>()
        portrait.facts.forEach { fact ->
            val parts = buildList {
                if (fact.todayText.isNotBlank()) add("今天：${fact.todayText}")
                if (fact.baselineText.isNotBlank()) add("平常：${fact.baselineText}")
                if (fact.deltaText.isNotBlank()) add("变化：${fact.deltaText}")
            }
            if (parts.isNotEmpty()) {
                items.add(
                    EvidenceItem(
                        category = DataSourceCategory.TODAY_AGGREGATE,
                        label = fact.label.ifBlank { "今日观察" },
                        text = parts.joinToString("；"),
                    )
                )
            }
        }
        portrait.dimensions.forEach { (key, dim) ->
            items.add(
                EvidenceItem(
                    category = DataSourceCategory.TODAY_AGGREGATE,
                    label = "今日维度",
                    text = "${dimensionDisplayName(key)}：${dimensionValueText(key, dim.value)}",
                )
            )
        }
        if (portrait.summary.isNotBlank()) {
            items.add(
                EvidenceItem(
                    category = DataSourceCategory.BASELINE,
                    label = "今日概要",
                    text = portrait.summary,
                )
            )
        }
        if (portrait.baselineDays > 0) {
            items.add(
                EvidenceItem(
                    category = DataSourceCategory.BASELINE,
                    label = "个人基线",
                    text = "基线已积累 ${portrait.baselineDays} 天",
                )
            )
        }
        return items
    }

    /** 记忆 → 证据（按类型映射数据源类别；会话历史由调用方单独给出）。 */
    fun fromMemories(memories: List<EchoMemory>): List<EvidenceItem> =
        memories.map { memory ->
            EvidenceItem(
                category = when (memory.type) {
                    MemoryType.CONTEXT -> DataSourceCategory.CONTEXT_EXCEPTIONS
                    MemoryType.CORRECTION -> DataSourceCategory.USER_CORRECTIONS
                    MemoryType.USER_CONFIRMED, MemoryType.PREFERENCE -> DataSourceCategory.PREFERENCES
                    else -> DataSourceCategory.TODAY_AGGREGATE
                },
                label = memoryTypeLabel(memory.type),
                text = memory.content,
            )
        }

    /** 画像时间线 → 历史证据（FIND_LONGITUDINAL_PATTERN；按旧到新，日期入 label）。 */
    fun fromPortraitHistory(portraits: List<DailyPortraitDto>): List<EvidenceItem> =
        portraits.sortedBy { it.date }.map { portrait ->
            val line = portrait.headline.joinToString(" · ")
                .ifBlank { portrait.summary }
                .ifBlank { portrait.dimensions.entries.joinToString("；") { (k, d) -> "${dimensionDisplayName(k)}:${dimensionValueText(k, d.value)}" } }
            EvidenceItem(
                category = DataSourceCategory.PORTRAIT_HISTORY,
                label = portrait.date,
                text = line,
            )
        }

    private fun memoryTypeLabel(type: MemoryType): String = when (type) {
        MemoryType.CONTEXT -> "你告诉过我"
        MemoryType.CORRECTION -> "你纠正过我"
        MemoryType.USER_CONFIRMED -> "你确认过"
        MemoryType.PREFERENCE -> "你的偏好"
        else -> "我记录过"
    }
}
