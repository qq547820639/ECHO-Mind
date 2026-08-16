package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_TREND_DIMENSIONS
import com.yunjue.echo.mind.presence.EchoVisualParameters

/**
 * v3.1 §25/§26 — Journey Domain：Journey 不直接消费 Portrait DTO 表现层结构。
 *
 * [JourneyDay] / [JourneyPeriod] 是 Journey 的领域模型；
 * 视觉参数在领域层一次装配（[buildJourneyDays]），UI 只渲染。
 * 视觉聚合 deterministic（同输入同输出）。
 */

/** 单个时间节点（一天的 ECHO 视觉记忆单元 + 事实摘要）。 */
data class JourneyDay(
    val date: String,
    val baselineDays: Int,
    val headline: String,
    val summary: String,
    val dimensionValues: Map<String, String>,
    /** 预装配的当日视觉参数（旧单环渲染；null = 无画像，渲染弥散占位，不编造）。 */
    val visualParams: EchoVisualParameters?,
    /** visual-runtime：当日 organism genome（9 层渲染；优先于 visualParams）。 */
    val genome: com.yunjue.echo.mind.visual.model.EchoVisualGenome? = null,
)

/** 一段时间窗口（聚合视觉 + 代表日）。 */
data class JourneyPeriod(
    val days: List<JourneyDay>,
    val aggregateParams: EchoVisualParameters?,
    val representative: JourneyDay?,
    /** visual-runtime：聚合 organism genome（优先于 aggregateParams）。 */
    val aggregateGenome: com.yunjue.echo.mind.visual.model.EchoVisualGenome? = null,
)

/** Portrait DTO → JourneyDay（纯映射；视觉参数一次装配）。 */
fun buildJourneyDays(portraits: List<DailyPortraitDto>): List<JourneyDay> =
    portraits.sortedBy { it.date }.map { dto ->
        JourneyDay(
            date = dto.date,
            baselineDays = dto.baselineDays,
            headline = dto.headline.joinToString(" · "),
            summary = dto.summary,
            dimensionValues = PORTRAIT_TREND_DIMENSIONS.associateWith { key ->
                dto.dimensionValue(key) ?: ""
            }.filterValues { it.isNotBlank() },
            visualParams = journeyDayParams(dto),
        )
    }

/**
 * visual-runtime：带 identity seed 的 JourneyDay 装配（同时携带 9 层 organism genome）。
 * SAME ECHO：Journey portrait 与主 ECHO 共享同一 identitySeed。
 */
fun buildJourneyDaysWithOrganism(
    portraits: List<DailyPortraitDto>,
    identitySeed: Long,
): List<JourneyDay> =
    portraits.sortedBy { it.date }.map { dto ->
        JourneyDay(
            date = dto.date,
            baselineDays = dto.baselineDays,
            headline = dto.headline.joinToString(" · "),
            summary = dto.summary,
            dimensionValues = PORTRAIT_TREND_DIMENSIONS.associateWith { key ->
                dto.dimensionValue(key) ?: ""
            }.filterValues { it.isNotBlank() },
            visualParams = journeyDayParams(dto),
            genome = JourneyOrganismVisuals.genomeFor(dto, identitySeed),
        )
    }

/** 代表日：SIMILAR 维度数最多的日子（平手取最近一天）；空列表 → null。 */
fun journeyRepresentativeDay(days: List<JourneyDay>): JourneyDay? {
    if (days.isEmpty()) return null
    return days.maxByOrNull { day ->
        day.dimensionValues.values.count { it in setOf("SIMILAR", "VERY_SIMILAR") }
    }
}

/** 视觉聚合（deterministic：逐参数平均；空列表 → null）。 */
fun journeyAggregateOfDays(days: List<JourneyDay>): EchoVisualParameters? {
    val params = days.mapNotNull { it.visualParams }
    if (params.isEmpty()) return null
    val avg = { f: (EchoVisualParameters) -> Float -> params.map(f).average().toFloat() }
    return EchoVisualParameters(
        flowSpeed = avg { it.flowSpeed },
        coherence = avg { it.coherence },
        turbulence = avg { it.turbulence },
        particleDensity = avg { it.particleDensity },
        coreOpenness = avg { it.coreOpenness },
        dispersion = avg { it.dispersion },
        pulsePeriodSeconds = avg { it.pulsePeriodSeconds },
        depth = avg { it.depth },
        brightness = avg { it.brightness },
        contrast = avg { it.contrast },
        accentIntensity = avg { it.accentIntensity },
        structureComplexity = avg { it.structureComplexity },
    )
}

/** 按 [chunkDays] 分组并组装 JourneyPeriod（从旧到新；不足一组也成组）。 */
fun buildJourneyPeriods(days: List<JourneyDay>, chunkDays: Int): List<JourneyPeriod> {
    if (days.isEmpty() || chunkDays <= 0) return emptyList()
    return days.chunked(chunkDays).map { chunk ->
        JourneyPeriod(
            days = chunk,
            aggregateParams = journeyAggregateOfDays(chunk),
            representative = journeyRepresentativeDay(chunk),
        )
    }
}

/** 聚合 genome：chunk 内代表日的 genome（视觉聚合 = 代表日，同一 ECHO 的周/月代表帧）。 */
private fun aggregateGenomeOfDays(days: List<JourneyDay>) =
    journeyRepresentativeDay(days)?.genome

/** visual-runtime：携带 organism genome 的 JourneyPeriod 装配（与 buildJourneyPeriods 同分组逻辑）。 */
fun buildJourneyPeriodsWithOrganism(days: List<JourneyDay>, chunkDays: Int): List<JourneyPeriod> {
    if (days.isEmpty() || chunkDays <= 0) return emptyList()
    return days.chunked(chunkDays).map { chunk ->
        JourneyPeriod(
            days = chunk,
            aggregateParams = journeyAggregateOfDays(chunk),
            representative = journeyRepresentativeDay(chunk),
            aggregateGenome = aggregateGenomeOfDays(chunk),
        )
    }
}
