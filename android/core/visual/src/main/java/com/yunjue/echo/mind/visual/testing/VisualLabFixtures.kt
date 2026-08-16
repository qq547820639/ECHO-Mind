package com.yunjue.echo.mind.visual.testing

import com.yunjue.echo.mind.visual.math.DeterministicRandom
import com.yunjue.echo.mind.visual.model.EchoVisualGenome

/**
 * VisualLabFixtures — V3 §33/§81 视觉夹具（debug-only Visual Lab 与视觉测试共用）。
 *
 * 只构建 genome 参数（确定性），不改变任何产品语义；预设名与 §33 一致：
 * SEED / KNOWN_DAY28 / QUIET / LOW_DATA / MATURE。
 */
object VisualLabFixtures {

    enum class Preset { SEED, KNOWN_DAY28, QUIET, LOW_DATA, MATURE }

    /** Lab 默认 identity（固定 seed；换 identity 用 [withSeed]）。 */
    const val LAB_SEED = 7L

    /** 可调参数（全部 0..1；映射到既有 genome 字段，不创建重复业务字段）。 */
    data class Knobs(
        val flow: Float = 0.54f,
        val coherence: Float = 0.68f,
        val turbulence: Float = 0.31f,
        val particleDensity: Float = 0.64f,
        val depth: Float = 0.76f,
        val coreOpenness: Float = 0.58f,
        val structureComplexity: Float = 0.77f,
        val halo: Float = 0.55f,
        val exposure: Float = 0.70f,
        val warmAccent: Float = 0.23f,
        val motion: Float = 1.0f,
    )

    /** 预设 → genome（确定性；KNOWN_DAY28 命中 §35 Reference 目标映射）。 */
    fun genomeFor(preset: Preset, seed: Long = LAB_SEED): EchoVisualGenome = when (preset) {
        Preset.SEED -> base(seed).copy(
            coherence = 0.30f, turbulence = 0.18f, particleDensity = 0.30f,
            depth = 0.40f, coreIntensity = 0.15f, filamentDensity = 0.35f,
            haloIntensity = 0.35f, luminance = 0.55f, dataClarity = 0.45f, radialSpread = 0.30f,
        )
        Preset.KNOWN_DAY28 -> base(seed).copy(
            // §35 Reference 目标：flow .54 coherence .68 turbulence .31 particleDensity .64
            // coreOpenness .58 dispersion .34 depth .76 brightness .70 structureComplexity .77
            coherence = 0.68f, turbulence = 0.31f, particleDensity = 0.64f,
            depth = 0.76f, coreIntensity = 0.58f, filamentDensity = 0.72f,
            haloIntensity = 0.55f, luminance = 0.70f, dataClarity = 0.95f, radialSpread = 0.34f,
            driftRate = 0.54f,
        )
        Preset.QUIET -> base(seed).copy(
            coherence = 0.72f, turbulence = 0.08f, particleDensity = 0.28f,
            depth = 0.66f, coreIntensity = 0.62f, filamentDensity = 0.40f,
            haloIntensity = 0.42f, luminance = 0.50f, dataClarity = 0.92f, radialSpread = 0.26f,
            driftRate = 0.18f, momentIntensity = 0.02f,
        )
        Preset.LOW_DATA -> base(seed).copy(
            coherence = 0.38f, turbulence = 0.24f, particleDensity = 0.30f,
            depth = 0.45f, coreIntensity = 0.42f, filamentDensity = 0.44f,
            haloIntensity = 0.40f, luminance = 0.52f, dataClarity = 0.22f, radialSpread = 0.30f,
        )
        Preset.MATURE -> base(seed).copy(
            coherence = 0.74f, turbulence = 0.30f, particleDensity = 0.72f,
            depth = 0.80f, coreIntensity = 0.90f, filamentDensity = 0.85f,
            haloIntensity = 0.62f, luminance = 0.72f, dataClarity = 0.98f, radialSpread = 0.40f,
            driftRate = 0.58f,
        )
    }

    /** 预设 → maturity 名（§13 乘数输入）。 */
    fun maturityFor(preset: Preset): String = when (preset) {
        Preset.SEED -> "SEED"
        Preset.KNOWN_DAY28 -> "KNOWN"
        Preset.QUIET -> "KNOWN"
        Preset.LOW_DATA -> "DISCOVERING"
        Preset.MATURE -> "MATURE"
    }

    /** 滑杆覆盖（Lab 调参；只允许在 0..1 范围内调）。 */
    fun withKnobs(genome: EchoVisualGenome, k: Knobs): EchoVisualGenome = genome.copy(
        driftRate = k.flow.coerceIn(0f, 1f),
        coherence = k.coherence.coerceIn(0f, 1f),
        turbulence = k.turbulence.coerceIn(0f, 1f),
        particleDensity = k.particleDensity.coerceIn(0f, 1f),
        depth = k.depth.coerceIn(0f, 1f),
        coreIntensity = k.coreOpenness.coerceIn(0f, 1f),
        filamentDensity = k.structureComplexity.coerceIn(0f, 1f),
        haloIntensity = k.halo.coerceIn(0f, 1f),
        luminance = k.exposure.coerceIn(0f, 1f),
        pulseRate = 6.8f + (1f - k.motion.coerceIn(0.05f, 1f)) * 4f,
    )

    /** 换 identity（identity diversity 评审：几何维度随之变化，不只换色）。 */
    fun withSeed(genome: EchoVisualGenome, seed: Long): EchoVisualGenome = genome.copy(
        identitySeed = seed,
        identityPhase = frac(DeterministicRandom.mix(seed, 7)),
        spectralBias = frac(DeterministicRandom.mix(seed, 1)),
    )

    private fun base(seed: Long): EchoVisualGenome = EchoVisualGenome(
        identitySeed = seed,
        identityTopology = 0.65f,
        identityPhase = frac(DeterministicRandom.mix(seed, 7)),
        seasonPhase = 0.3f,
        dayComposition = 0.47f,
        coherence = 0.6f,
        radialSpread = 0.34f,
        orbitalEccentricity = 0.45f,
        particleDensity = 0.6f,
        filamentDensity = 0.7f,
        driftRate = 0.5f,
        pulseRate = 8.2f,
        turbulence = 0.3f,
        luminance = 0.7f,
        spectralBias = frac(DeterministicRandom.mix(seed, 1)),
        coreIntensity = 0.58f,
        haloIntensity = 0.55f,
        dataClarity = 0.95f,
        depth = 0.76f,
        momentIntensity = 0f,
    )

    private fun frac(v: Long): Float = (v ushr 40 and 0xFFFFFF).toFloat() / 16777215f
}
