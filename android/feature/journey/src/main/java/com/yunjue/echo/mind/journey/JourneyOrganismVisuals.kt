package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PORTRAIT_TREND_DIMENSIONS
import com.yunjue.echo.mind.model.echoMaturity
import com.yunjue.echo.mind.presence.EchoVisualParameters
import com.yunjue.echo.mind.presence.maturityOpenness
import com.yunjue.echo.mind.visual.model.EchoPortraitSnapshot
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.model.PORTRAIT_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.visual.model.VisualGenomeCompiler
import com.yunjue.echo.mind.visual.render.OrganismFrame
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import kotlin.math.abs

/**
 * JourneyOrganismVisuals — Journey 的新版 organism portrait（visual-runtime）。
 *
 * 与旧 [journeyDayParams]（单环+圆点）平行的新实现：画像维度 → EchoVisualParameters →
 * VisualGenomeCompiler（机械编译，中性恒定 identity）→ 9 层 organism 帧。
 * **确定性重建**（同一天 + 同一 identity → 同一帧，PORTRAIT_CANONICAL_TIME_SECONDS 固定）。
 * 纯 Kotlin（经 core:visual），无 Android 依赖，JVM 可测。
 *
 * V3 §H/§J：禁止在本文件硬编码 identityTopology/orbitalEccentricity/luminance——
 * 恒定身份一律来自 [VisualGenomeCompiler.neutralIdentity]（canonical 单一定义），
 * 亮度经 params.brightness = [CANONICAL_DAY_BRIGHTNESS] 进入编译。
 */
object JourneyOrganismVisuals {

    /** Journey portrait 基准亮度（白天 canonical；一致性优先）。 */
    private const val CANONICAL_DAY_BRIGHTNESS = 0.7f

    /** 画像 + identity seed → 当日 organism genome（确定性；无画像 = null）。 */
    fun genomeFor(portrait: DailyPortraitDto?, identitySeed: Long): EchoVisualGenome? {
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
        val maturity = echoMaturity(portrait.baselineDays)
        val openness = maturityOpenness(maturity)
        val regularity = when (value("RHYTHM")) {
            "SIMILAR", "VERY_SIMILAR" -> 0.7f
            "IRREGULAR" -> 0.3f
            else -> 0.5f
        }

        // 日期 → 确定性相位（同一天永远同一帧；不同天有差异，但同一 identity 可辨）。
        // 用「年/月/日数字确定性混合」而非 String.hashCode()（后者对相邻日期可能低位碰撞）。
        val dayPhase = frac(dayNumber(portrait.date) * 2654435761L)

        val params = EchoVisualParameters(
            flowSpeed = ((activation * 0.6f + density * 0.4f) * 0.7f).coerceIn(0f, 1f),
            coherence = coherence,
            turbulence = deviation,
            particleDensity = (density * 0.8f + 0.15f).coerceIn(0f, 1f),
            coreOpenness = openness,
            dispersion = ((1f - coherence) * 0.5f + 0.2f).coerceIn(0.15f, 0.8f),
            pulsePeriodSeconds = (5.6f - activation * 1.8f).coerceIn(3.6f, 6f),
            depth = (0.3f + regularity * 0.7f).coerceIn(0f, 1f),
            brightness = CANONICAL_DAY_BRIGHTNESS,
            contrast = (0.4f + deviation * 0.6f).coerceIn(0f, 1f),
            accentIntensity = (0.3f + coherence * 0.7f).coerceIn(0f, 1f),
            structureComplexity = openness,
            dataClarity = (0.4f + portrait.baselineDays.coerceIn(0, 28) / 28f * 0.6f).coerceIn(0f, 1f),
            haloIntensity = (0.4f + coherence * 0.5f).coerceIn(0f, 1f),
            momentIntensity = 0f, // Journey 缩略帧无瞬时调制
            filamentDensity = (0.5f + coherence * 0.4f).coerceIn(0f, 1f),
            seasonPhase = frac(maturity.ordinal * 0.2f),
            dayComposition = dayPhase,
        )
        return VisualGenomeCompiler.compile(params, VisualGenomeCompiler.neutralIdentity(identitySeed))
    }

    /** 画像 → 确定性 organism 帧（同一天同一帧）。 */
    fun frameFor(
        portrait: DailyPortraitDto?,
        identitySeed: Long,
        width: Float,
        height: Float,
    ): OrganismFrame? {
        val genome = genomeFor(portrait, identitySeed) ?: return null
        val spec = SurfacePolicy.crop(
            genome = genome,
            surface = EchoSurface.APP_PRIVATE,
            clockSeconds = PORTRAIT_CANONICAL_TIME_SECONDS,
        )
        return OrganismFrameComputer.compute(spec, width, height)
    }

    /** 画像 → Journey 视觉记忆 snapshot（存参数不存图）。 */
    fun snapshotFor(
        portrait: DailyPortraitDto,
        identitySeed: Long,
        evidenceSummaryRef: String? = null,
    ): EchoPortraitSnapshot? {
        val genome = genomeFor(portrait, identitySeed) ?: return null
        return EchoPortraitSnapshot(
            date = portrait.date,
            genome = genome,
            identityRevision = EchoVisualGenome.CURRENT_REVISION,
            compositionRevision = EchoVisualGenome.CURRENT_REVISION,
            evidenceSummaryRef = evidenceSummaryRef,
        )
    }

    /** snapshot → 确定性重建帧（§11：Journey 每天可重建相同 portrait）。 */
    fun reconstructFrame(snapshot: EchoPortraitSnapshot, width: Float, height: Float): OrganismFrame {
        val spec = SurfacePolicy.crop(
            genome = snapshot.genome,
            surface = EchoSurface.APP_PRIVATE,
            clockSeconds = PORTRAIT_CANONICAL_TIME_SECONDS,
        )
        return OrganismFrameComputer.compute(spec, width, height)
    }

    /**
     * EchoVisualParameters → EchoVisualGenome 机械映射（V3 删除纪律迁移桥：
     * 历史 Canonical Daily State / QA 链存的是 EchoVisualParameters——
     * 经 canonical 编译器 + 中性恒定身份编译，不新增语义）。
     */
    fun genomeFromParams(
        params: EchoVisualParameters,
        seed: Long,
        dayComposition: Float = 0.47f,
    ): EchoVisualGenome = VisualGenomeCompiler.compile(
        params, VisualGenomeCompiler.neutralIdentity(seed),
    ).copy(dayComposition = dayComposition)

    /** Long → [0,1)：取模 1e6 的小数部分（保留低位差异，相邻日期可辨）。 */
    private fun frac(v: Long): Float {
        val m = 1_000_000L
        val r = (v % m + m) % m
        return r.toFloat() / m.toFloat()
    }
    private fun frac(v: Float): Float = v - kotlin.math.floor(v)

    /** "YYYY-MM-DD" → 确定性序数（年*372+月*31+日；非法回退 0）。 */
    private fun dayNumber(date: String): Long {
        val parts = date.split("-")
        if (parts.size != 3) return 0L
        val y = parts[0].toLongOrNull() ?: return 0L
        val m = parts[1].toLongOrNull() ?: return 0L
        val d = parts[2].toLongOrNull() ?: return 0L
        return y * 372L + m * 31L + d
    }
}
