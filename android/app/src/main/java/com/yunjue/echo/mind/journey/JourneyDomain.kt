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
    /** 预装配的当日视觉参数（null = 无画像，渲染弥散占位，不编造）。 */
    val visualParams: EchoVisualParameters?,
)

/** 一段时间窗口（聚合视觉 + 代表日）。 */
data class JourneyPeriod(
    val days: List<JourneyDay>,
    val aggregateParams: EchoVisualParameters?,
    val representative: JourneyDay?,
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
