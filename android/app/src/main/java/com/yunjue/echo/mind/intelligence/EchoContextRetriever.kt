package com.yunjue.echo.mind.intelligence

import com.yunjue.echo.mind.ports.EchoMemoryReader
import com.yunjue.echo.mind.ports.ObservationEvidenceSource
import java.time.LocalDate
import java.time.ZoneId

/**
 * v2 §42 — EchoContextRetriever：Context Compiler 的真实数据检索层。
 *
 * ERA 13.2 §37：只依赖 Domain Ports（ObservationEvidenceSource / EchoMemoryReader），
 * 不再 import data 实现类（LocalPortraitDataSource / MemoryRepository）。
 *
 * Task → ContextPolicy → 实际检索：
 * - TODAY_AGGREGATE / BASELINE ← ObservationEvidenceSource（今日画像，本地只读）；
 * - PORTRAIT_HISTORY ← ObservationEvidenceSource（timeWindowDays 窗口时间线）；
 * - 记忆 ← EchoMemoryReader（按 MemoryPolicy 白名单类型）。
 *
 * 只产出已脱敏 EvidenceItem；原始通知/音频/麦克风永不出现（策略层已硬禁止，
 * 检索层同样不会产生这些类别）。检索失败 → 空证据（上层 fallback，不抛异常）。
 */
class EchoContextRetriever(
    private val observationSource: ObservationEvidenceSource,
    private val memoryReader: EchoMemoryReader,
    private val userId: () -> String,
) {
    /** 按任务策略检索证据（失败降级为空列表；AI fallback 链兜底）。 */
    suspend fun retrieve(task: ReasoningTaskId): List<EvidenceItem> {
        val policy = contextPolicyFor(task)
        val items = mutableListOf<EvidenceItem>()

        // 今日画像（TODAY_AGGREGATE / BASELINE 证据源）
        if (DataSourceCategory.TODAY_AGGREGATE in policy.allowed ||
            DataSourceCategory.BASELINE in policy.allowed
        ) {
            val today = runCatching {
                observationSource.computeToday(
                    userId = userId(),
                    today = LocalDate.now(),
                    zoneId = ZoneId.systemDefault(),
                )
            }.getOrNull()
            items += EvidenceAssembler.fromPortrait(today).filter { it.category in policy.allowed }
        }

        // 历史画像（TimeWindow）
        if (DataSourceCategory.PORTRAIT_HISTORY in policy.allowed) {
            val days = policy.timeWindowDays.coerceIn(1, 365)
            val timeline = runCatching {
                observationSource.computeTimeline(
                    userId = userId(),
                    days = days,
                    endDate = LocalDate.now(),
                    zoneId = ZoneId.systemDefault(),
                )
            }.getOrDefault(emptyList())
            items += EvidenceAssembler.fromPortraitHistory(timeline)
        }

        // 记忆（MemoryPolicy 白名单）
        for (type in policy.allowedMemoryTypes) {
            val memories = runCatching { memoryReader.memoriesByType(type) }.getOrDefault(emptyList())
            items += EvidenceAssembler.fromMemories(memories)
        }

        return items
    }
}
