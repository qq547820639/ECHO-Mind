package com.yunjue.echo.mind.visual.render

import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.motion.EchoMotionState
import com.yunjue.echo.mind.visual.motion.MotionEvaluator
import com.yunjue.echo.mind.visual.surface.EchoVisualSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OrganismFrameComputer — V3 帧求值器（纯函数，无 Android 依赖，JVM 可测）。
 *
 * 管线：EchoVisualSpec → EchoSceneCompiler → EchoRenderPacket
 *        →（缓存拓扑 OrganismTopology + MotionEvaluator）→ OrganismFrame。
 *
 * 确定性：同一 spec + 视口 + options → 逐值相同的帧（golden / Journey 重建前提）。
 * 禁止 frame-random noise（§15）；一切随机源来自 identitySeed 的稳定拓扑缓存（§32）。
 *
 * 算法对应：§14 三层拓扑 / §15 3D filament 谐波场 / §16 behind-core 遮挡 /
 * §17 Fibonacci 粒子 / §18 空心核 / §27 呼吸 / §28 慢轨道 / §29 触摸形变 / §41 低数据降级。
 */
object OrganismFrameComputer {

    private const val TWO_PI = 2f * PI.toFloat()

    /** 渲染侧附加输入（不属业务 spec；由 host 按设备/偏好填充）。 */
    data class EchoRenderOptions(
        val maturityName: String = "KNOWN",
        val tier: EchoRenderTier = EchoRenderTier.LEGACY,
        val quality: EchoRenderQuality = EchoRenderQuality.NORMAL,
        val reducedMotion: Boolean = false,
        val lowPower: Boolean = false,
        /** §42 Sensing Disabled：motion × .30（identity 保留，非 error screen）。 */
        val motionScale: Float = 1f,
        /** §42 Sensing Disabled：detail × .55。 */
        val detailScale: Float = 1f,
        /** §45 Correction 视觉反馈：距用户纠正的秒数（null = 无进行中脉冲）。 */
        val correctionPulseAgeSeconds: Float? = null,
        /** §54 Awakening 时间线：halo 0→.55 渐入（1 = 正常）。 */
        val haloScale: Float = 1f,
        /** §54 Awakening 时间线：outer ring alpha 0→1。 */
        val ringAlphaScale: Float = 1f,
        /** §54 first breath .985→1.018→1.000（覆盖呼吸缩放；null = 正常呼吸）。 */
        val breathScaleOverride: Float? = null,
        val hdrEligible: Boolean = false,
        val interaction: EchoInteractionSpec = EchoInteractionSpec(),
    )

    /** 帧求值共享上下文（hot path 单次分配；detekt 参数收敛）。 */
    private data class FrameCtx(
        val identity: EchoIdentitySpec,
        val field: EchoFieldSpec,
        val cosA: Float,
        val sinA: Float,
        val baseR: Float,
        val sx: Float,
        val sy: Float,
        val coreInner: Float,
        val coreOuter: Float,
        val primary: Argb,
        val secondary: Argb,
        val warm: Argb,
        val touch: EchoInteractionSpec,
        val detailScale: Float,
    )

    /** 一条弧线的采样描述（plane basis + 弧程 + 谐波参数；§15）。 */
    private data class ArcSpec(
        val plane: FilamentPlane,
        val arcStart: Float,
        val arcLength: Float,
        val baseRadiusRatio: Float,
        val freq: Float,
        val phase: Float,
        val depthWarpAmp: Float,
        val lobeCount: Int = 0,
        val lobeAmp: Float = 0f,
    )

    /** 各向同性半径尺度（球体在任意宽高比下保持圆形；minDim 基准）。 */
    private fun isoX(aspect: Float): Float = if (aspect >= 1f) 1f / aspect else 1f
    private fun isoY(aspect: Float): Float = if (aspect >= 1f) 1f else aspect

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 计算一帧（确定性）。 */
    fun compute(
        spec: EchoVisualSpec,
        width: Float,
        height: Float,
        options: EchoRenderOptions = EchoRenderOptions(),
    ): OrganismFrame {
        val quality = if (options.lowPower && options.quality == EchoRenderQuality.NORMAL) {
            EchoRenderQuality.CONSERVE
        } else {
            options.quality
        }
        val packet = EchoSceneCompiler.compile(
            spec = spec,
            viewportWidth = width,
            viewportHeight = height,
            maturityName = options.maturityName,
            tier = options.tier,
            quality = quality,
            reducedMotion = options.reducedMotion,
            motionScale = options.motionScale,
            hdrEligible = options.hdrEligible,
            interaction = options.interaction,
        )
        val identity = packet.identity
        val field = packet.field
        val motionBase = MotionEvaluator.evaluate(packet.motion, spec.clockSeconds, packet.interaction)
        // §45：Correction 脉冲（halo -8% + filament phase 暂停 150ms 后 converge；identity 不变）
        val motion = options.correctionPulseAgeSeconds?.let { ageMs ->
            val (haloDelta, pauseSeconds) = MotionEvaluator.correctionPulse((ageMs * 1000f).toLong())
            if (haloDelta == 0f && pauseSeconds == 0f) {
                motionBase
            } else {
                val phaseOmega = TWO_PI / packet.motion.filamentPhaseSeconds.coerceAtLeast(4f) *
                    packet.motion.filamentPhaseScale
                motionBase.copy(
                    haloMultiplier = motionBase.haloMultiplier * (1f + haloDelta),
                    filamentPhase = motionBase.filamentPhase - phaseOmega * pauseSeconds,
                )
            }
        } ?: motionBase
        val profile = EchoSceneCompiler.qualityProfile(quality)
        val topo = OrganismTopologyBuilder.topologyFor(identity, quality, options.maturityName)

        val aspect = if (height > 0f) width / height else 1f

        // 调色板（LCh 感知语义；§12）
        val primary = ColorSpace.lch(
            identity.palette.primary.l, identity.palette.primary.c, identity.palette.primary.h,
        )
        val secondary = ColorSpace.lch(
            identity.palette.secondary.l, identity.palette.secondary.c, identity.palette.secondary.h,
        )
        val warm = ColorSpace.lch(identity.palette.warm.l, identity.palette.warm.c, identity.palette.warm.h)

        // 视觉半径 R（minDim 归一化；呼吸只缩放表现，不改 identity）
        val breathScale = options.breathScaleOverride ?: motion.breathScale
        val baseR = (0.19f + field.dispersion * 0.11f + field.coreOpenness * 0.02f) *
            identity.membraneBias * breathScale

        // 低数据降级（§41：颜色不变红，只降丰富度/alpha/远晕）
        val clarity = field.dataClarity.coerceIn(0f, 1f)
        val filamentClarity = lerp(0.72f, 1f, clarity)
        val particleClarity = lerp(0.78f, 1f, clarity)
        val farHaloClarity = lerp(0.65f, 1f, clarity)

        // ---- 1. Ambient field（近黑；视觉质量来自大量 black + 少量真亮，§24） ----
        val exposure = field.exposure.coerceIn(0f, 1f)
        val ambient = AmbientField(
            centerColor = ColorSpace.lch(
                0.038f + exposure * 0.045f,
                identity.palette.primary.c * 0.55f, identity.palette.primary.h,
            ),
            // §24：42% 半径处已落到近黑——画面质量来自大量 black + 少量真亮
            midColor = ColorSpace.lch(
                0.016f + exposure * 0.014f,
                identity.palette.primary.c * 0.58f, identity.palette.primary.h,
            ),
            edgeColor = ColorSpace.lch(
                0.008f + exposure * 0.010f,
                identity.palette.primary.c * 0.60f, identity.palette.primary.h,
            ),
            grainIntensity = (0.25f + clarity * 0.5f) * spec.capabilities.motionComplexity,
        )

        // Daily 层相位：同一天恒定、跨天可辨（Journey 时间流逝）；identity 拓扑不变。
        val dailyPhase = spec.genome.dayComposition * TWO_PI
        val ctx = FrameCtx(
            identity = identity, field = field,
            cosA = cos(motion.globalRotation * identity.chirality),
            sinA = sin(motion.globalRotation * identity.chirality),
            baseR = baseR, sx = isoX(aspect), sy = isoY(aspect),
            coreInner = identity.coreRatio * 0.85f, coreOuter = identity.coreRatio * 1.30f,
            primary = primary, secondary = secondary, warm = warm,
            touch = packet.interaction,
            detailScale = options.detailScale.coerceIn(0f, 1f),
        )
        val samples = samplesFor(quality)
        var touchBudget = if (ctx.touch.active) 5 else 0 // §29：最多 5 条 front filament

        // ---- 2. Structural Rings（identity skeleton） ----
        val rings = topo.rings.mapIndexed { i, ring ->
            sampleStroke(
                arc = ArcSpec(
                    plane = ring.plane, arcStart = 0f, arcLength = TWO_PI,
                    baseRadiusRatio = ring.radiusRatio, freq = identity.baseFrequency.toFloat(),
                    phase = ring.phase + dailyPhase * 0.5f + motion.filamentPhase * 0.3f,
                    depthWarpAmp = 0.05f,
                    lobeCount = identity.lobeCount, lobeAmp = ring.lobeHarmonicAmp,
                ),
                samples = samples, ctx = ctx,
                // 外环更淡（视觉质量集中于 .9R 内；skeleton 仍清晰可辨）
                baseAlpha = (0.40f + field.coherence * 0.34f) * filamentClarity * (1f - i * 0.12f) *
                    options.ringAlphaScale.coerceIn(0f, 1f),
                color = primary, widthFraction = 0.0028f,
            )
        }

        // ---- 3. Long Filaments（跨半球弧；§16 遮挡在采样内烘焙） ----
        val longs = topo.longFilaments.mapIndexed { i, f ->
            val useTouch = ctx.touch.active && touchBudget > 0
            if (useTouch) touchBudget--
            sampleStroke(
                arc = ArcSpec(
                    plane = f.plane, arcStart = f.arcStart, arcLength = f.arcLength,
                    baseRadiusRatio = f.baseRadiusRatio,
                    freq = (identity.baseFrequency + f.freqOffset).toFloat(),
                    phase = f.phase + dailyPhase + motion.filamentPhase,
                    depthWarpAmp = f.depthWarpAmp * (0.4f + field.dispersion),
                ),
                samples = samples, ctx = ctx,
                baseAlpha = (0.26f + field.coherence * 0.42f) * filamentClarity,
                color = if (i % 3 == 2) secondary else primary,
                widthFraction = 0.0021f, glow = 0.35f, consumeTouch = useTouch,
            )
        }

        // ---- 4. Local Fragments（短弧生命纹理） ----
        val frags = topo.fragments.map { f ->
            evalFragment(
                frag = f, phase = f.phase + dailyPhase * 0.8f + motion.filamentPhase * 0.7f,
                ctx = ctx, baseAlpha = (0.20f + field.coherence * 0.30f) * filamentClarity,
            )
        }

        // ---- 5. 核心（hollow core，§18） ----
        val cavityRadius = identity.coreRatio * (0.85f + field.coreOpenness * 0.30f)
        val coreCavity = CoreCavity(
            radiusFraction = cavityRadius * baseR,
            darkColor = ColorSpace.lch(0.018f, identity.palette.primary.c * 0.5f, identity.palette.primary.h),
            atmosphereColor = ColorSpace.lch(
                0.13f + field.coreOpenness * 0.10f + exposure * 0.04f,
                identity.palette.primary.c * 0.8f, identity.palette.primary.h,
            ),
        )
        val knots = topo.coreKnots.map { k ->
            val p = rotY(k.offset, ctx.cosA, ctx.sinA) * motion.breathScale
            CoreKnotV(
                x = 0.5f + p.x * baseR * ctx.sx,
                y = 0.5f + p.y * baseR * ctx.sy,
                radiusFraction = k.radiusRatio,
                color = if (k.warm && spec.capabilities.allowWarmAccent) warm else secondary,
                alpha = 0.42f + 0.32f * field.coreOpenness,
            )
        }
        val strands = buildCoreStrands(topo, cavityRadius, motion.breathScale, ctx)

        // ---- 6. 粒子（Fibonacci 基 + 慢迁移；§17/§30/§31/§41） ----
        val particles = evalParticles(
            topo = topo, motion = motion, profile = profile,
            particleClarity = particleClarity,
            allowWarm = spec.capabilities.allowWarmAccent, ctx = ctx,
        )

        // ---- 7. Halo（远层；§31 far halo 可降级；§41 低数据 ×.65） ----
        val halos = ArrayList<Halo>(2)
        val haloBase = field.halo.coerceIn(0f, 1f) * motion.haloMultiplier
        halos += Halo(
            radiusFraction = baseR * 1.24f,
            alpha = (0.011f + haloBase * 0.028f) * (0.5f + spec.capabilities.motionComplexity * 0.5f) *
                options.haloScale.coerceIn(0f, 1f),
            widthFraction = 0.0028f,
        )
        if (profile.farHaloEnabled && field.halo > 0.25f) {
            halos += Halo(
                radiusFraction = baseR * 1.50f,
                alpha = (0.007f + haloBase * 0.016f) * farHaloClarity * options.haloScale.coerceIn(0f, 1f),
                widthFraction = 0.0018f,
            )
        }

        // ---- 8. 前膜（§18 front membrane：前半球壳层微光） ----
        val frontMembrane = FrontMembrane(
            radiusFraction = baseR * 0.94f,
            color = primary,
            alpha = 0.028f + field.coherence * 0.038f,
        )

        // ---- 9. 涟漪（§29 触摸 1 个 ripple；moment 瞬时响应保留既有语义） ----
        val ripples = ArrayList<Ripple>(2)
        if (ctx.touch.active && ctx.touch.envelope > 0.01f) {
            ripples += Ripple(
                x = ctx.touch.touchX, y = ctx.touch.touchY,
                radiusFraction = (0.10f + (1f - ctx.touch.envelope) * 0.30f) * baseR / 0.25f,
                alpha = ctx.touch.envelope * 0.30f,
            )
        }
        if (spec.genome.momentIntensity > 0.05f) {
            val p = spec.clockSeconds % 1.6f / 1.6f
            ripples += Ripple(
                x = 0.5f, y = 0.5f,
                radiusFraction = baseR * (1f + p * 0.8f),
                alpha = (1f - p) * 0.18f * spec.genome.momentIntensity,
            )
        }

        // ---- 10. 暖金高光（极少量；仅 allowWarmAccent surface） ----
        val warmAccents = if (spec.capabilities.allowWarmAccent) {
            knots.filter { it.color == warm }.take(1).map {
                WarmAccent(it.x, it.y, it.radiusFraction * 1.6f, it.alpha * 0.5f)
            }
        } else {
            emptyList()
        }

        return OrganismFrame(
            ambientField = ambient,
            halos = halos,
            structuralRings = rings,
            longFilaments = longs,
            localFragments = frags,
            coreStrands = strands,
            coreCavity = coreCavity,
            coreKnots = knots,
            particles = particles,
            frontMembrane = frontMembrane,
            ripples = ripples,
            warmAccents = warmAccents,
        )
    }

    /** §77：filament samples 56（NORMAL）→ 48（CONSERVE）→ 40（MINIMAL）。 */
    private fun samplesFor(quality: EchoRenderQuality): Int = when (quality) {
        EchoRenderQuality.NORMAL -> 56
        EchoRenderQuality.CONSERVE -> 48
        EchoRenderQuality.MINIMAL -> 40
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun rotY(p: Vec3, cosA: Float, sinA: Float): Vec3 =
        Vec3(p.x * cosA + p.z * sinA, p.y, -p.x * sinA + p.z * cosA)

    /**
     * §15/§16 丝/环采样：稳定 plane basis + 确定性谐波场 + 3D 投影 + behind-core 遮挡。
     */
    private fun sampleStroke(
        arc: ArcSpec,
        samples: Int,
        ctx: FrameCtx,
        baseAlpha: Float,
        color: Argb,
        widthFraction: Float,
        glow: Float = 0f,
        consumeTouch: Boolean = false,
    ): FilamentStroke {
        val pts = ArrayList<StrokePoint>(samples + 1)
        val f = arc.freq
        for (i in 0..samples) {
            val theta = arc.arcStart + arc.arcLength * i / samples
            // §15 deterministic harmonic field（禁止 frame-random noise）
            val noise = (
                sin(f * theta + arc.phase) +
                    .50f * sin((f + 3f) * theta + arc.phase * 1.73f) +
                    .25f * sin((2f * f + 1f) * theta - arc.phase * .61f)
                ) / 1.75f
            val secondaryWave = sin(2f * f * theta - arc.phase * 1.13f)
            var r = arc.baseRadiusRatio * (
                1f + .024f * ctx.field.turbulence * noise +
                    .012f * (1f - ctx.field.coherence) * secondaryWave
                )
            if (arc.lobeCount > 0 && arc.lobeAmp > 0f) {
                r *= 1f + arc.lobeAmp * cos(arc.lobeCount * theta + arc.phase)
            }
            val depthWarp = arc.depthWarpAmp * sin(f * 0.5f * theta + arc.phase * 0.7f)
            var p = arc.plane.u * (cos(theta) * r) + arc.plane.v * (sin(theta) * r) +
                arc.plane.normal * depthWarp
            p = rotY(p, ctx.cosA, ctx.sinA)

            // §29 触摸形变（仅 front、有限条数；Gaussian w = exp(-d²/2σ²)）
            var dx = 0f
            var dy = 0f
            val touch = ctx.touch
            if (consumeTouch && p.z > 0f && touch.envelope > 0.01f) {
                val tx = (touch.touchX - 0.5f) / (ctx.sx * ctx.baseR)
                val ty = (touch.touchY - 0.5f) / (ctx.sy * ctx.baseR)
                val ddx = p.x - tx
                val ddy = p.y - ty
                val d2 = ddx * ddx + ddy * ddy
                val w = exp(-d2 / (2f * touch.sigma * touch.sigma))
                val push = touch.maxDeformation * touch.envelope * w
                val len = sqrt(d2).coerceAtLeast(1e-4f)
                dx = ddx / len * push
                dy = ddy / len * push
            }

            val perspective = 1f + .10f * p.z
            val px = 0.5f + (p.x + dx) * ctx.baseR * ctx.sx * perspective
            val py = 0.5f + (p.y + dy) * ctx.baseR * ctx.sy * perspective

            // 深度明暗（front/middle/back 三层空间关系）+ §16 behind-core 遮挡
            var alpha = baseAlpha * (0.55f + 0.45f * (p.z + 1f) * 0.5f)
            if (p.z < 0f) {
                val r2 = sqrt(p.x * p.x + p.y * p.y)
                alpha *= smoothstep(ctx.coreInner, ctx.coreOuter, r2)
            }
            pts += StrokePoint(px, py, alpha.coerceIn(0f, 1f))
        }
        return FilamentStroke(pts, color, widthFraction, glow)
    }

    /** 局部碎片求值（短弧；圆心偏置在壳层，不穿过中心）。 */
    private fun evalFragment(
        frag: LocalFragmentTopo,
        phase: Float,
        ctx: FrameCtx,
        baseAlpha: Float,
        samples: Int = 18,
    ): FilamentStroke {
        val pts = ArrayList<StrokePoint>(samples + 1)
        val breatheOffset = 1f + 0.01f * sin(phase)
        for (i in 0..samples) {
            val theta = frag.arcStart + frag.arcLength * i / samples
            val local = frag.plane.u * (cos(theta) * frag.radiusRatio) +
                frag.plane.v * (sin(theta) * frag.radiusRatio)
            var p = (frag.center + local) * breatheOffset
            p = rotY(p, ctx.cosA, ctx.sinA)
            val perspective = 1f + .10f * p.z
            val px = 0.5f + p.x * ctx.baseR * ctx.sx * perspective
            val py = 0.5f + p.y * ctx.baseR * ctx.sy * perspective
            var alpha = baseAlpha * (0.5f + 0.5f * (p.z + 1f) * 0.5f)
            if (p.z < 0f) {
                val r2 = sqrt(p.x * p.x + p.y * p.y)
                alpha *= smoothstep(ctx.coreInner, ctx.coreOuter, r2)
            }
            pts += StrokePoint(px, py, alpha.coerceIn(0f, 1f))
        }
        return FilamentStroke(pts, ctx.secondary, 0.0016f, 0.2f)
    }

    /** 核心内部细缕（连接 stable knots 区域的短弧；低 alpha）。 */
    private fun buildCoreStrands(
        topo: OrganismTopology,
        cavityRadius: Float,
        breathScale: Float,
        ctx: FrameCtx,
    ): List<FilamentStroke> {
        val knots = topo.coreKnots
        if (knots.size < 2) return emptyList()
        val strands = ArrayList<FilamentStroke>(knots.size)
        for (i in knots.indices) {
            val a = knots[i].offset * breathScale
            val b = knots[(i + 1) % knots.size].offset * breathScale
            val pts = ArrayList<StrokePoint>(9)
            for (s in 0..8) {
                val t = s / 8f
                // 轻微下垂的弦（确定性；不随帧变化）
                val sag = sin(t * PI.toFloat()) * 0.05f * cavityRadius
                val px = a.x + (b.x - a.x) * t
                val py = a.y + (b.y - a.y) * t + sag
                pts += StrokePoint(
                    x = 0.5f + px * ctx.baseR * ctx.sx,
                    y = 0.5f + py * ctx.baseR * ctx.sy,
                    alpha = (0.30f + ctx.field.coreOpenness * 0.30f) *
                        sin(t * PI.toFloat()).coerceIn(0.2f, 1f),
                )
            }
            strands += FilamentStroke(pts, ctx.secondary, 0.0013f)
        }
        return strands
    }

    /** §17 Fibonacci 粒子求值（分类亮度上限；glint 极少强亮；遮挡与丝一致）。 */
    private fun evalParticles(
        topo: OrganismTopology,
        motion: EchoMotionState,
        profile: EchoSceneCompiler.QualityProfile,
        particleClarity: Float,
        allowWarm: Boolean,
        ctx: FrameCtx,
    ): List<SceneParticleV3> {
        val density = ctx.field.particleDensity.coerceIn(0f, 1f)
        val target = ((40 + 120 * density) * profile.particleScale * particleClarity *
            ctx.field.maturityMultiplier * ctx.detailScale).toInt().coerceIn(0, topo.particles.size)
        if (target <= 0) return emptyList()
        val rot = motion.particleRotation * ctx.identity.chirality
        val cr = cos(rot)
        val sr = sin(rot)
        val out = ArrayList<SceneParticleV3>(target)
        val touch = ctx.touch
        for (i in 0 until target) {
            val pb = topo.particles[i]
            if (pb.kind == ParticleKind.GLINT && !profile.glintsEnabled) continue
            var p = pb.dir * pb.shellRadius
            p = rotY(p, cr, sr)
            // §29：触点附近粒子轻微响应
            var ox = 0f
            var oy = 0f
            if (touch.active && p.z > 0f && touch.envelope > 0.01f) {
                val tx = (touch.touchX - 0.5f) / (ctx.sx * ctx.baseR)
                val ty = (touch.touchY - 0.5f) / (ctx.sy * ctx.baseR)
                val ddx = p.x - tx
                val ddy = p.y - ty
                val d2 = ddx * ddx + ddy * ddy
                val w = exp(-d2 / (2f * touch.sigma * touch.sigma))
                ox = ddx * touch.maxDeformation * touch.envelope * w * 0.6f
                oy = ddy * touch.maxDeformation * touch.envelope * w * 0.6f
            }
            val perspective = 1f + .10f * p.z
            val px = 0.5f + (p.x + ox) * ctx.baseR * ctx.sx * perspective
            val py = 0.5f + (p.y + oy) * ctx.baseR * ctx.sy * perspective
            val classCap = when (pb.kind) {
                ParticleKind.AMBIENT -> 0.34f
                ParticleKind.BRIGHT -> if (profile.secondaryGlintsEnabled) 0.62f else 0.40f
                ParticleKind.GLINT -> 0.95f // 只有极少 glint 可以达到强亮
            }
            var alpha = classCap * (0.45f + 0.55f * pb.sizeJitter) *
                (0.5f + 0.5f * (p.z + 1f) * 0.5f) * (0.55f + ctx.field.coherence * 0.45f)
            // 远壳层粒子淡出（§24 视觉质量集中于 .9R 内；粒子场拓扑不变，只降远层可见度）
            alpha *= lerp(1f, 0.28f, smoothstep(0.68f, 1.02f, pb.shellRadius))
            if (p.z < 0f) {
                val r2 = sqrt(p.x * p.x + p.y * p.y)
                alpha *= smoothstep(ctx.coreInner, ctx.coreOuter, r2)
            }
            val size = (0.0022f + pb.sizeJitter * 0.0048f) *
                (0.7f + ctx.field.depth * 0.5f) * if (pb.kind == ParticleKind.GLINT) 1.25f else 1f
            out += SceneParticleV3(
                x = px, y = py,
                radiusFraction = size,
                alpha = alpha.coerceIn(0f, 1f),
                kind = pb.kind,
                color = when {
                    pb.warm && allowWarm -> ctx.warm
                    pb.kind == ParticleKind.AMBIENT -> ctx.secondary
                    else -> ctx.primary
                },
            )
        }
        return out
    }
}
