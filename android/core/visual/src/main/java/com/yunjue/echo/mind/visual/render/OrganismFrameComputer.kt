package com.yunjue.echo.mind.visual.render

import com.yunjue.echo.mind.visual.math.DeterministicRandom
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.noise.OrganicNoise
import com.yunjue.echo.mind.visual.render.ColorSpace.withAlpha
import com.yunjue.echo.mind.visual.surface.EchoVisualSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * OrganismFrameComputer — 把裁剪后的 [EchoVisualSpec] 计算为确定性 [OrganismFrame]。
 *
 * 渲染管线的算法核心（纯函数，无 Android 依赖，JVM 可测）：
 *   输入 spec（含 genome + capabilities + clockSeconds）+ 视口 → 输出完整 9 层帧。
 * 同一 spec + 视口 → 逐值相同的帧（golden test / Journey 重建前提）。
 *
 * 层次实现对应 ECHO_VISUAL_CONSTITUTION §二：ambient field / membrane / filament /
 * orbital / particle / core glow / halo / ripple / warm accent。
 */
object OrganismFrameComputer {

    private const val TWO_PI = 2f * PI.toFloat()

    /** 计算一帧（确定性）。width/height 仅用于宽高比校正，粒子坐标仍归一化输出。 */
    fun compute(spec: EchoVisualSpec, width: Float, height: Float): OrganismFrame {
        val g = spec.genome
        val seed = g.identitySeed
        val t = spec.clockSeconds
        val cap = spec.capabilities
        val aspect = if (height > 0f) width / height else 1f

        // 主色相：identity 派生收敛于青蓝—紫（0.5..0.78），spectralBias 在区间内偏移；暖金单独处理。
        val hue = 0.5f + g.spectralBias.coerceIn(0f, 1f) * 0.28f
        val accent = ColorSpace.hsv(hue, 0.7f, 0.8f)
        val breathe = sin(t / g.pulseRate.coerceAtLeast(1f) * TWO_PI)

        // ---- 1. Ambient field ----
        val lum = g.luminance.coerceIn(0f, 1f)
        val ambient = AmbientField(
            centerColor = ColorSpace.hsv(hue, 0.5f, 0.05f + lum * 0.08f),
            edgeColor = ColorSpace.BG_EDGE,
            grainIntensity = (0.2f + g.dataClarity * 0.5f) * cap.motionComplexity,
        )

        // ---- 2. Membrane（filament 网状闭合曲面） ----
        val membraneRadius = 0.16f + g.radialSpread * 0.12f + breathe * 0.015f * g.coreIntensity
        val membranePoints = membraneOutline(seed, g, t, membraneRadius, aspect)
        val membrane = Membrane(
            outline = membranePoints,
            strokeColor = accent.withAlpha(0.55f + g.coherence * 0.3f),
            strokeAlpha = 0.4f + g.coherence * 0.4f,
            strokeWidthFraction = 0.004f,
        )

        // ---- 3. Filament 网络 ----
        val filaments = buildFilaments(seed, g, t, membraneRadius, aspect, cap.motionComplexity)

        // ---- 4. Orbital 轨迹 ----
        val orbitals = buildOrbitals(seed, g, t, membraneRadius, accent, cap.motionComplexity)

        // ---- 5. 粒子系统 ----
        val particles = buildParticles(seed, g, t, membraneRadius, accent, aspect, cap.motionComplexity)

        // ---- 6. 核心光斑 ----
        val coreGlow = CoreGlow(
            radiusFraction = (0.05f + g.coreIntensity * 0.06f + breathe * 0.01f).coerceIn(0.03f, 0.2f),
            color = ColorSpace.hsv(hue, 0.5f, 0.95f),
            intensity = 0.5f + g.coreIntensity * 0.5f,
        )

        // ---- 7. 环境光晕（同心） ----
        val halos = buildHalos(g, membraneRadius, cap.motionComplexity)

        // ---- 8. 涟漪（来自 momentIntensity 的瞬时响应；不改 identity） ----
        val ripples = buildRipples(seed, g, t, membraneRadius)

        // ---- 9. 暖金高光（仅 allowWarmAccent；<5% 面积） ----
        val warm = if (cap.allowWarmAccent) {
            listOf(
                WarmAccent(
                    x = 0.5f + cos(g.identityPhase * TWO_PI) * membraneRadius * 0.6f,
                    y = 0.5f + sin(g.identityPhase * TWO_PI) * membraneRadius * 0.6f,
                    radiusFraction = 0.03f,
                    alpha = 0.5f,
                ),
            )
        } else {
            emptyList()
        }

        return OrganismFrame(
            ambientField = ambient,
            membrane = membrane,
            filaments = filaments,
            orbitals = orbitals,
            particles = particles,
            coreGlow = coreGlow,
            halos = halos,
            ripples = ripples,
            warmAccents = warm,
        )
    }

    /** 膜轮廓：极坐标采样 + 有机噪声起伏（coherence 平滑 / turbulence 破碎，中性）。 */
    private fun membraneOutline(
        seed: Long,
        g: EchoVisualGenome,
        t: Float,
        baseRadius: Float,
        aspect: Float,
    ): List<MembranePoint> {
        val segments = 48
        val pts = ArrayList<MembranePoint>(segments)
        for (i in 0 until segments) {
            val theta = i.toFloat() / segments * TWO_PI + g.identityPhase * TWO_PI
            val bump = OrganicNoise.membraneRadius(
                seed, theta, g.coherence, g.turbulence, t, g.driftRate,
            )
            // 拓扑：identityTopology 高 → 更接近正圆；低 → 更不规则
            val irregular = (1f - g.identityTopology) * 0.35f
            val r = baseRadius * (1f + (bump - 0.5f) * 2f * (0.1f + irregular + g.orbitalEccentricity * 0.2f))
            pts += MembranePoint(
                x = 0.5f + cos(theta) * r * aspect.coerceIn(0.6f, 1.4f),
                y = 0.5f + sin(theta) * r,
            )
        }
        return pts
    }

    /** filament 网络：连接膜上采样点 + 内部弦，密度由 filamentDensity 决定。 */
    private fun buildFilaments(
        seed: Long,
        g: EchoVisualGenome,
        t: Float,
        baseRadius: Float,
        aspect: Float,
        motion: Float,
    ): List<Filament> {
        val count = (g.filamentDensity * 28f * motion).roundToInt().coerceIn(0, 40)
        if (count == 0) return emptyList()
        val list = ArrayList<Filament>(count)
        val cx = 0.5f
        val cy = 0.5f
        for (i in 0 until count) {
            val a1 = DeterministicRandom.range(seed, i * 4 + 1, 0f, TWO_PI) +
                g.identityPhase * TWO_PI + t * g.driftRate * 0.05f
            val a2 = a1 + DeterministicRandom.range(seed, i * 4 + 2, 0.4f, 2.2f)
            val r1 = baseRadius * DeterministicRandom.range(seed, i * 4 + 3, 0.3f, 1.0f)
            val r2 = baseRadius * DeterministicRandom.range(seed, i * 4 + 4, 0.3f, 1.0f)
            val wob = OrganicNoise.fbm1(seed + i, t * g.turbulence * 0.5f, octaves = 2) * g.turbulence * 0.1f
            list += Filament(
                x1 = cx + cos(a1) * (r1 + wob) * aspect.coerceIn(0.6f, 1.4f),
                y1 = cy + sin(a1) * (r1 + wob),
                x2 = cx + cos(a2) * (r2 - wob) * aspect.coerceIn(0.6f, 1.4f),
                y2 = cy + sin(a2) * (r2 - wob),
                alpha = (0.08f + g.coherence * 0.25f) * (0.5f + DeterministicRandom.at(seed, i * 4 + 5) * 0.5f),
                widthFraction = 0.0012f,
            )
        }
        return list
    }

    /** 轨道轨迹：1 主环 + 由 structureComplexity/离心率派生的次级椭圆环。 */
    private fun buildOrbitals(
        seed: Long,
        g: EchoVisualGenome,
        t: Float,
        baseRadius: Float,
        accent: Argb,
        motion: Float,
    ): List<Orbital> {
        val extra = (g.orbitalEccentricity * 2f * motion).roundToInt().coerceIn(0, 3)
        val list = ArrayList<Orbital>(1 + extra)
        // 主轨道
        list += Orbital(
            radiusFraction = baseRadius * 1.25f,
            eccentricity = g.orbitalEccentricity * 0.3f,
            rotationRadians = t * g.driftRate * 0.1f + g.identityPhase,
            alpha = 0.18f + g.coherence * 0.2f,
            widthFraction = 0.0018f,
        )
        for (k in 1..extra) {
            list += Orbital(
                radiusFraction = baseRadius * (1.25f + 0.3f * k),
                eccentricity = (g.orbitalEccentricity * (0.3f + 0.2f * k)).coerceIn(0f, 0.9f),
                rotationRadians = -t * g.driftRate * 0.07f + g.identityPhase + k * 0.9f,
                alpha = (0.12f + g.coherence * 0.12f) / k,
                widthFraction = 0.0013f,
            )
        }
        return list
    }

    /** 粒子系统：密度由 particleDensity；轨道由 driftRate；流线由 filament 纹理倾向。 */
    private fun buildParticles(
        seed: Long,
        g: EchoVisualGenome,
        t: Float,
        baseRadius: Float,
        accent: Argb,
        aspect: Float,
        motion: Float,
    ): List<OrganismParticle> {
        val count = (8 + g.particleDensity * 40f * motion).roundToInt().coerceIn(0, 64)
        val list = ArrayList<OrganismParticle>(count)
        val clarity = g.dataClarity.coerceIn(0f, 1f)
        for (i in 0 until count) {
            val r1 = DeterministicRandom.at(seed, i * 5 + 1)
            val r2 = DeterministicRandom.at(seed, i * 5 + 2)
            val r3 = DeterministicRandom.at(seed, i * 5 + 3)
            val orbitSpeed = g.driftRate * (0.2f + r2 * 0.8f)
            val angle = r1 * TWO_PI + t * orbitSpeed * 0.3f + g.identityPhase * TWO_PI
            val spread = baseRadius * (1.1f + r2 * g.radialSpread * 1.6f + g.orbitalEccentricity * 0.6f)
            val px = 0.5f + cos(angle) * spread * aspect.coerceIn(0.6f, 1.4f)
            val py = 0.5f + sin(angle) * spread
            val sizeBase = (0.0025f + r3 * 0.007f) * (0.6f + g.coreIntensity * 0.6f)
            // 数据清晰度低 → 更淡更稀（视觉降级，非危险色）
            val alpha = (0.1f + g.coherence * 0.5f) * (0.4f + r1 * 0.6f) * (0.4f + clarity * 0.6f)
            val tangentX = -sin(angle)
            val tangentY = cos(angle)
            val useStreak = g.filamentDensity > 0.5f && r3 > 0.5f
            if (useStreak) {
                list += OrganismParticle(
                    x = px, y = py,
                    radiusFraction = sizeBase * 0.6f,
                    alpha = alpha,
                    streakDirX = tangentX, streakDirY = tangentY,
                    streakLength = (0.004f + r3 * 0.012f) * (0.4f + g.driftRate * 0.8f),
                )
            } else {
                list += OrganismParticle(x = px, y = py, radiusFraction = sizeBase, alpha = alpha)
            }
        }
        return list
    }

    /** 环境光晕：1 主 halo + 由 haloIntensity 派生的远环。 */
    private fun buildHalos(g: EchoVisualGenome, baseRadius: Float, motion: Float): List<Halo> {
        val list = ArrayList<Halo>(3)
        list += Halo(
            radiusFraction = baseRadius * 1.6f,
            alpha = (0.10f + g.haloIntensity * 0.25f) * (0.5f + motion * 0.5f),
            widthFraction = 0.0022f,
        )
        if (g.haloIntensity > 0.4f) {
            list += Halo(
                radiusFraction = baseRadius * 2.1f,
                alpha = 0.05f + g.haloIntensity * 0.12f,
                widthFraction = 0.0015f,
            )
        }
        return list
    }

    /** 涟漪：momentIntensity 瞬时 >0 时产生扩散环（衰减，不改 identity）。 */
    private fun buildRipples(seed: Long, g: EchoVisualGenome, t: Float, baseRadius: Float): List<Ripple> {
        if (g.momentIntensity <= 0.05f) return emptyList()
        val phase = t % 1.2f / 1.2f // 1.2s 一周期
        val list = ArrayList<Ripple>(2)
        for (k in 0..1) {
            val p = (phase + k * 0.5f) % 1f
            list += Ripple(
                radiusFraction = baseRadius * (1f + p * 0.9f),
                alpha = (1f - p) * 0.25f * g.momentIntensity,
            )
        }
        return list
    }
}
