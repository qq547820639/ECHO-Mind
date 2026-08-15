package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.intelligence.AiNarrativeService
import com.yunjue.echo.mind.intelligence.PersonalAnswerEngine
import com.yunjue.echo.mind.intelligence.PersonalAnswerInputs
import com.yunjue.echo.mind.intelligence.PersonalContextWindow
import com.yunjue.echo.mind.intelligence.PersonalDayFacts
import com.yunjue.echo.mind.model.MemoryType
import com.yunjue.echo.mind.ports.EchoMemoryReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * ERA 31 BATCH 2 — 确定性个人回答的生产数据桥。
 *
 * 把生产数据（窗口化画像+逐日聚合 / 上下文记忆 / 纠正记忆 / Presence 季节漂移）
 * 组装为 [PersonalAnswerEngine] 输入。无 Provider/离线时 Ask ECHO 由它回答
 * 「只有我的 ECHO 才可能回答」的问题；隐私边界不变（全部只读本地数据）。
 */
class DeterministicPersonalAnswerProvider(
    private val portraitDataSource: LocalPortraitDataSource,
    private val memoryReader: EchoMemoryReader,
    private val userId: () -> String,
    private val seasonDrift: () -> Float,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val zoneId: () -> ZoneId = { ZoneId.systemDefault() },
) {

    companion object {
        /** 引擎最长需要 181 天（半年对比）；§108 窗口化加载只查该范围。 */
        const val TIMELINE_DAYS = 181

        /** ERA 31 R37：纠正记忆内部格式前缀（人话化解析锚点）。 */
        const val CORRECTION_REASON_PREFIX = "画像反馈：不太像（原因："
    }

    suspend fun answer(question: String): AiNarrativeService.DeterministicPersonalResult? {
        val end = today()
        val zone = zoneId()
        val (portraits, aggregates) = portraitDataSource.computeTimelineWithAggregates(
            userId(), TIMELINE_DAYS, end, zone,
        )
        if (portraits.isEmpty()) return null
        val firstDate = LocalDate.parse(portraits.first().date)
        val dayFacts = portraits.map { p ->
            val date = LocalDate.parse(p.date)
            val agg = aggregates[date]
            PersonalDayFacts(
                date = date,
                activeStartMinute = agg?.activeStartMinute,
                activeEndMinute = agg?.activeEndMinute,
                screenOnMinutes = agg?.screenOnMinutes ?: 0.0,
                lateScreenMinutes = agg?.lateScreenMinutes ?: 0.0,
                activeHourSpread = agg?.activeHourSpread,
                movementIndex = agg?.movementIndex,
                dimensions = p.dimensions,
                hasPortrait = p.dimensions.isNotEmpty(),
            )
        }
        val lastIndex = dayFacts.size - 1
        val contextWindows = memoryReader.memoriesByType(MemoryType.CONTEXT)
            .filter { !it.deleted }
            .mapNotNull { memory ->
                val created = Instant.ofEpochMilli(memory.createdAt).atZone(zone).toLocalDate()
                val fromDay = ChronoUnit.DAYS.between(firstDate, created).toInt()
                if (fromDay < 0) {
                    null
                } else {
                    // ERA 31 R18：结构化上下文用 kind 做标签（如「旅行」），
                    // 避免把「特殊时期：旅行（…）」整串截断进回答；非结构化兜底原逻辑。
                    val info = com.yunjue.echo.mind.memory.contextExceptionInfo(memory.content)
                    PersonalContextWindow(
                        fromDay = fromDay.coerceAtMost(lastIndex),
                        toDay = lastIndex,
                        label = info?.kind?.takeIf { it.isNotBlank() } ?: memory.content.take(24),
                    )
                }
            }
        val corrections = memoryReader.memoriesByType(MemoryType.CORRECTION)
            .filter { !it.deleted }
            .map { humanizeCorrection(it.content) }
        val confirmed = memoryReader.memoriesByType(MemoryType.USER_CONFIRMED)
            .filter { !it.deleted }
            .map { humanizeConfirmed(it.content) }
        val result = PersonalAnswerEngine.answer(
            question = question,
            inputs = PersonalAnswerInputs(
                dayIndex = lastIndex,
                days = dayFacts,
                contextWindows = contextWindows,
                seasonDrift = seasonDrift(),
                userCorrections = corrections,
                userConfirmed = confirmed,
            ),
        ) ?: return null
        return AiNarrativeService.DeterministicPersonalResult(
            text = result.text,
            // ERA 31 R24：依据如实透传（引擎标注真实用到的数据源）——
            // 纠正/上下文/确认类回答不再一律显示「参考了：历史画像」。
            usedSources = result.usedSources,
        )
    }

    /**
     * ERA 31 R37：纠正记忆内容说人话——回放问题（「我纠正过你的那次…」）不再把
     * 「画像反馈：不太像（原因：旅行）（原判断：…）」整串内部格式甩给用户。
     */
    private fun humanizeCorrection(content: String): String {
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

    /** ERA 31 R37：确认记忆内容说人话（「问答反馈：像我（问：…）」→「问「…」的回答」）。 */
    private fun humanizeConfirmed(content: String): String {
        val prefix = "问答反馈：像我（问："
        return if (content.startsWith(prefix)) {
            "问「${content.removePrefix(prefix).removeSuffix("）").take(40)}」的回答"
        } else {
            content.take(40)
        }
    }
}
