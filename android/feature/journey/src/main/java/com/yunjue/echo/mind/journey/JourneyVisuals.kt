package com.yunjue.echo.mind.journey
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.echoMaturity

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_TREND_DIMENSIONS
import com.yunjue.echo.mind.presence.EchoVisualParameters
import com.yunjue.echo.mind.presence.maturityOpenness
import kotlin.math.abs
import kotlin.math.max

/**
 * ERA 8 — Journey Visuals（纯 Kotlin，无 Android 依赖）。
 *
 * 每一天的 ECHO Portrait 都是 Journey 中的一个**视觉记忆单元**（Master Prompt PART 7/70）：
 * - 由画像维度确定性映射视觉参数（同一天永远同一帧——Determinism）；
 * - CANONICAL_SNAPSHOT：固定时间点渲染（timeSeconds 固定），Journey 缩略图不需要 AI 生图；
 * - 周/月尺度聚合为代表性参数（视觉逐渐聚合，不是一堆折线图）。
 */

/**
 * 历史画像的成熟度**显式代理**（Organism Quality §34 语义统一）：
 * 生产 Presence 的成熟度 = 「自苏醒锚点起的日历天数」（echoMaturity 单一定义）；
 * Journey 历史重建处没有苏醒锚点，用画像日期相对时间线最早画像日期的**日历天数**
 * 作代理（单调、可越过 28 → 与 Presence 日历语义同构）。
 * [earliestDate] 未知（单画像/无时间线上下文）时退化为 baselineDays
 * （28 天窗口分桶有效日，≤20——对长期用户会低估；仅为兜底，不作为语义）。
 */
fun portraitMaturityProxy(portrait: DailyPortraitDto, earliestDate: String?): EchoMaturity {
    val earliest = earliestDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    val date = runCatching { java.time.LocalDate.parse(portrait.date) }.getOrNull()
    return if (earliest != null && date != null) {
        echoMaturity(java.time.temporal.ChronoUnit.DAYS.between(earliest, date).toInt())
    } else {
        echoMaturity(portrait.baselineDays)
    }
}

/** 画像 → 当日视觉参数（确定性；无画像 = null，渲染器给占位帧）。 */
fun journeyDayParams(portrait: DailyPortraitDto?, earliestDate: String? = null): EchoVisualParameters? {
    if (portrait == null) return null
    val dims = portrait.dimensions
    val value = { key: String -> dims[key]?.value }

    val activation = when (value("MOVEMENT")) {
        "MORE" -> 0.75f
        "LESS" -> 0.35f
        "SIMILAR", "VERY_SIMILAR" -> 0.55f
        else -> 0.4f
    }
    val density = when (value("SCREEN_AMOUNT")) {
        "MORE", "MORE_FRAGMENTED", "MORE_CONCENTRATED" -> 0.7f
        "LESS" -> 0.35f
        else -> 0.5f
    }
    val deviation = PORTRAIT_TREND_DIMENSIONS.mapNotNull { dims[it]?.z }
        .map { abs(it).toFloat() / 2f }
        .maxOrNull()?.coerceIn(0f, 1f) ?: 0.2f
    val known = PORTRAIT_TREND_DIMENSIONS.count { dims[it]?.value in setOf("SIMILAR", "VERY_SIMILAR") }
    val coherence = (0.45f + 0.3f * (known / PORTRAIT_TREND_DIMENSIONS.size.toFloat())).coerceIn(0.2f, 0.8f)
    val openness = maturityOpenness(portraitMaturityProxy(portrait, earliestDate))
    val regularity = when (value("RHYTHM")) {
        "SIMILAR", "VERY_SIMILAR" -> 0.7f
        "IRREGULAR" -> 0.3f
        else -> 0.5f
    }

    return EchoVisualParameters(
        flowSpeed = ((activation * 0.6f + density * 0.4f) * 0.7f).coerceIn(0f, 1f),
        coherence = coherence,
        turbulence = deviation,
        particleDensity = (density * 0.8f + 0.15f).coerceIn(0f, 1f),
        coreOpenness = openness,
        dispersion = ((1f - coherence) * 0.5f + 0.2f).coerceIn(0.15f, 0.8f),
        pulsePeriodSeconds = 5.6f - activation * 1.8f,
        depth = (0.3f + regularity * 0.7f).coerceIn(0.3f, 1f),
        brightness = 0.7f, // CANONICAL_SNAPSHOT：白天基准亮度（Journey 一致性优先）
        contrast = (0.4f + deviation * 0.6f).coerceIn(0f, 1f),
        accentIntensity = (0.3f + coherence * 0.7f).coerceIn(0f, 1f),
        structureComplexity = openness,
    )
}

/** CANONICAL_SNAPSHOT 时间点：固定 12.0s（同一天永远同一帧）。 */
const val JOURNEY_CANONICAL_TIME_SECONDS = 12f



/** 代表日：SIMILAR 维度数最多的日子（平手取最近一天）；空列表 → -1。 */
fun journeyRepresentativeIndex(portraits: List<DailyPortraitDto>): Int {
    if (portraits.isEmpty()) return -1
    return portraits.indices.maxByOrNull { i ->
        val dims = portraits[i].dimensions
        PORTRAIT_TREND_DIMENSIONS.count { dims[it]?.value in setOf("SIMILAR", "VERY_SIMILAR") }
    } ?: portraits.size - 1
}

/** 周/月视觉聚合：逐参数平均（确定性；空列表 → null）。 */
fun journeyAggregateParams(portraits: List<DailyPortraitDto>): EchoVisualParameters? {
    val params = portraits.mapNotNull { journeyDayParams(it) }
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

/** 时间尺度（Journey 的 Day/Week/Month/Season/Year；PART 69 的 Moment..Year 全尺度）。 */
enum class JourneyScale { DAY, WEEK, MONTH, SEASON, YEAR }

/** 每尺度默认窗口天数（backend 画像窗口上限已扩至 365）。 */
fun journeyWindowDays(scale: JourneyScale): Int = when (scale) {
    JourneyScale.DAY -> 7
    JourneyScale.WEEK -> 28
    JourneyScale.MONTH -> 28
    JourneyScale.SEASON -> 90
    JourneyScale.YEAR -> 365
}

/** 视觉聚合分组天数（视觉逐渐聚合，不是折线图）。 */
fun journeyChunkDays(scale: JourneyScale): Int = when (scale) {
    JourneyScale.DAY -> 1
    JourneyScale.WEEK -> 7
    JourneyScale.MONTH -> 30
    JourneyScale.SEASON -> 30
    JourneyScale.YEAR -> 30
}

/** 按 [chunkDays] 把 [portraits] 从旧到新分组（不足一组也成组）。 */
fun journeyGroups(portraits: List<DailyPortraitDto>, chunkDays: Int): List<List<DailyPortraitDto>> {
    if (portraits.isEmpty() || chunkDays <= 0) return emptyList()
    val sorted = portraits.sortedBy { it.date }
    return sorted.chunked(chunkDays)
}

/** 周聚合分组（7 天一组；保留旧函数名兼容既有调用与测试语义）。 */
fun journeyWeekGroups(portraits: List<DailyPortraitDto>): List<List<DailyPortraitDto>> =
    journeyGroups(portraits, 7)

/** 视觉成熟度单调性（跨天成长断言用）：SEED→MATURE 阶段索引。 */
fun maturityStage(maturity: EchoMaturity): Int = maturity.ordinal

/** 跨天视觉成长度：结构复杂度随 baselineDays 单调不减（Journey 视觉成长的断言锚点）。 */
fun growthScore(portraits: List<DailyPortraitDto>): Float {
    val sorted = portraits.sortedBy { it.date }
    if (sorted.isEmpty()) return 0f
    val first = journeyDayParams(sorted.first())?.structureComplexity ?: 0f
    val last = journeyDayParams(sorted.last())?.structureComplexity ?: 0f
    return max(0f, last - first)
}
