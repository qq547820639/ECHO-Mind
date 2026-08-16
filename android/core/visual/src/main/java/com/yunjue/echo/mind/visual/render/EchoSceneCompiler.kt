package com.yunjue.echo.mind.visual.render

import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.EchoVisualSpec

/**
 * EchoSceneCompiler — V3 §7 渲染编译器（纯函数，JVM 可测）。
 *
 * 管线位置：
 *   EchoPresenceState → GenomeDeriver → EchoVisualGenome → SurfacePolicy.crop → EchoVisualSpec
 *     → **EchoSceneCompiler.compile → EchoRenderPacket** → Backend（LEGACY Canvas / AGSL / ADVANCED）
 *
 * 职责（V3 §8 CPU 侧）：把已裁剪的语义参数编译为 renderer-internal packet——
 * identity 派生（§10–§13）、场参数、材质参数、运动语义参数（§25–§28）、surface 质量/tier（§9/§31）。
 * 不接触 Repository / DB / Observation / Provider（V3 §8 shader/渲染侧禁令由架构保证）。
 */
object EchoSceneCompiler {

    /** §13 成熟度乘数（只影响丰富度，不表示等级/健康/价值）。 */
    fun maturityMultiplier(maturityName: String): Float = when (maturityName) {
        "SEED" -> .42f
        "DISCOVERING" -> .58f
        "EMERGING" -> .74f
        "KNOWN" -> .90f
        "MATURE" -> 1.00f
        else -> .42f
    }

    /** §31 质量降级缩放（Identity / core anatomy / Presence correctness / privacy 永不降级）。 */
    data class QualityProfile(
        val particleScale: Float,
        val filamentScale: Float,
        val glintsEnabled: Boolean,
        val secondaryGlintsEnabled: Boolean,
        val farHaloEnabled: Boolean,
    )

    fun qualityProfile(quality: EchoRenderQuality): QualityProfile = when (quality) {
        EchoRenderQuality.NORMAL -> QualityProfile(1f, 1f, true, true, true)
        EchoRenderQuality.CONSERVE -> QualityProfile(.68f, .76f, true, false, true)
        EchoRenderQuality.MINIMAL -> QualityProfile(.34f, .50f, false, false, false)
    }

    /**
     * 编译一帧的 renderer packet。
     *
     * @param spec 已被 SurfacePolicy 裁剪的渲染输入（含 genome + capabilities + clockSeconds）
     * @param maturityName presence.maturity.name（§13 乘数；编译器不反推 presence）
     * @param tier Capability Router 输出（§9）
     * @param quality 渲染质量（§31；由 power/thermal 策略层决定）
     * @param reducedMotion 系统/用户 Reduced Motion（§30 精确系数在此展开）
     * @param hdrEligible HDR 硬门（§23：API≥34 且显示链路支持且非省电且热态<MODERATE 且非 Wallpaper）
     * @param interaction 当前 transient 交互（默认 idle；Touch 不改状态层）
     * @param canonicalTimeNanos 绝对单调时间（全 Surface 共享时间基准）
     */
    fun compile(
        spec: EchoVisualSpec,
        viewportWidth: Float,
        viewportHeight: Float,
        maturityName: String,
        tier: EchoRenderTier,
        quality: EchoRenderQuality = EchoRenderQuality.NORMAL,
        reducedMotion: Boolean = false,
        motionScale: Float = 1f,
        hdrEligible: Boolean = false,
        interaction: EchoInteractionSpec = EchoInteractionSpec(),
        canonicalTimeNanos: Long = (spec.clockSeconds * 1_000_000_000f).toLong(),
    ): EchoRenderPacket {
        val g = spec.genome
        val maturity = maturityMultiplier(maturityName)

        // ---- §27 呼吸：period 收敛到 6.8–10.8s 窗口（genome.pulseRate 既有 3.6..6s 量纲归一）----
        val baseT = ((g.pulseRate - 3.6f) / 2.4f).coerceIn(0f, 1f)
        var breathPeriod = 6.8f + baseT * 4.0f
        var breathAmplitude = when (spec.surface) {
            EchoSurface.WALLPAPER_VISUAL_ONLY -> 0.016f
            EchoSurface.DREAM_AMBIENT -> 0.020f
            else -> 0.024f // APP / LOCK / WRIST 以 App 基准，材质层再降亮度
        }
        if (spec.surface == EchoSurface.DREAM_AMBIENT) breathPeriod *= 1.18f
        if (reducedMotion) {
            breathAmplitude = 0.007f
            breathPeriod *= 1.45f
        }

        // ---- §28 轨道 / filament 相位周期 ----
        val orbitPeriod = lerp(21f * 60f, 60f * 60f, g.driftRate.coerceIn(0f, 1f))
        val filamentPhase = lerp(26f, 58f, g.filamentDensity.coerceIn(0f, 1f))

        // ---- §30 Reduced Motion / §71 Dream 运动乘数（在此一次性展开，backend 不再判断）----
        var particleVelocity = 1f
        var orbitVelocity = 1f
        var filamentScale = 1f
        when {
            reducedMotion -> {
                particleVelocity = .08f
                orbitVelocity = .06f
                filamentScale = .12f
            }
            spec.surface == EchoSurface.DREAM_AMBIENT -> {
                particleVelocity = .55f
                orbitVelocity = .45f
                filamentScale = .60f
            }
        }

        val field = EchoFieldSpec(
            flow = g.driftRate,
            coherence = g.coherence,
            turbulence = g.turbulence,
            particleDensity = g.particleDensity,
            depth = g.depthOrDerived(),
            coreOpenness = g.coreIntensity,
            dispersion = g.radialSpread,
            halo = g.haloIntensity,
            exposure = g.luminance,
            dataClarity = g.dataClarity,
            maturityMultiplier = maturity,
        )

        val material = EchoMaterialSpec(
            hdrAllowed = hdrEligible && spec.surface != EchoSurface.WALLPAPER_VISUAL_ONLY,
        )

        particleVelocity *= motionScale.coerceIn(0f, 1f)
        orbitVelocity *= motionScale.coerceIn(0f, 1f)
        filamentScale *= motionScale.coerceIn(0f, 1f)
        val motion = EchoMotionSpec(
            breathPeriodSeconds = breathPeriod,
            breathAmplitude = breathAmplitude,
            brightnessPulse = 0.03f,
            orbitPeriodSeconds = orbitPeriod,
            filamentPhaseSeconds = filamentPhase,
            particleVelocity = particleVelocity,
            orbitVelocity = orbitVelocity,
            filamentPhaseScale = filamentScale,
        )

        val surface = EchoSurfaceSpec(
            surface = spec.surface,
            capabilities = spec.capabilities,
            quality = quality,
            tier = tier,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )

        return EchoRenderPacket(
            identity = EchoIdentitySpec.derive(g.identitySeed),
            field = field,
            material = material,
            motion = motion,
            surface = surface,
            interaction = interaction,
            canonicalTimeNanos = canonicalTimeNanos,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    /** genome.depth >= 0 时采用（regularity 语义）；否则由 coherence 派生（向后兼容）。 */
    private fun EchoVisualGenome.depthOrDerived(): Float =
        if (depth >= 0f) depth.coerceIn(0f, 1f) else (0.4f + coherence * 0.6f).coerceIn(0f, 1f)
}
