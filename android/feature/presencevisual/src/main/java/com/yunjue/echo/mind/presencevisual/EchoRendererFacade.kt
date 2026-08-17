package com.yunjue.echo.mind.presencevisual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.render.ColorSpace
import com.yunjue.echo.mind.visual.render.EchoInteractionSpec
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.MotionPolicy
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import com.yunjue.echo.mind.visual.surface.defaultQualityFor
import com.yunjue.echo.mind.visual.surface.motionScaleFor
import com.yunjue.echo.mind.visual.surface.reducedMotionFor

/**
 * EchoRendererFacade — V3 §P/§Q/§R 渲染层统一收口（Capability Router 唯一门面）。
 *
 * 所有消费者（App Compose / Visual Lab / Wallpaper / Dream / Journey）一律经本 facade：
 * - Compose surface → [Organism]（单 surface 一次高成本会话，委托既有 EchoOrganism）；
 * - 非 Compose 会话（Wallpaper/Dream/离屏导出）→ [createSession]（crop→compute→backend
 *   dispatch 全部在 [EchoRenderSession] 内，帧路径只喂 clock + 可选 interaction）。
 *
 * 后端解析规则（§Q）：
 * - LEGACY → CANVAS；
 * - STANDARD/ADVANCED → RuntimeShader 可用走 AGSL（ADVANCED 另需 RuntimeColorFilter，
 *   否则降级 AGSL 并给 reason）；不可用 → CANVAS fallback + reason；
 * - ULTRA → 按 ADVANCED 请求处理（永不自动启用，§97）；
 * - quality = request.quality ?: defaultQualityFor(surface)，环境快照可 worseOf 降级；
 * - hdr 恒 false（§T：真实 Display.HdrCapabilities 能力存在前不声称 HDR）。
 */
data class EchoRenderRequest(
    val genome: EchoVisualGenome,
    val surface: EchoSurface,
    val motion: MotionPolicy = MotionPolicy.NORMAL,
    val maturityName: String,
    val requestedTier: EchoRenderTier = EchoRenderTier.LEGACY,
    /** null → defaultQualityFor(surface)（环境可再降级）。 */
    val quality: EchoRenderQuality? = null,
    val interaction: EchoInteractionSpec = EchoInteractionSpec(),
)

/** 后端解析结果（§Q：requested vs resolved + backendName + 降级 reason）。 */
data class BackendResolution(
    val requestedTier: EchoRenderTier,
    val resolvedTier: EchoRenderTier,
    /** "CANVAS" | "AGSL" | "AGSL_ADVANCED"。 */
    val backendName: String,
    val quality: EchoRenderQuality,
    val reducedMotion: Boolean,
    val motionScale: Float,
    /** §T：恒 false（真实 HDR 能力存在前 BLOCKED）。 */
    val hdr: Boolean,
    val wideColorGamut: Boolean,
    /** 降级原因（如 "AGSL unavailable (API<33)"）；null = 按请求解析。 */
    val reason: String? = null,
)

object EchoRendererFacade {

    const val BACKEND_CANVAS = "CANVAS"
    const val BACKEND_AGSL = "AGSL"
    const val BACKEND_AGSL_ADVANCED = "AGSL_ADVANCED"

    /**
     * §Q 后端解析（纯函数，JVM 可测）。env 为 null 时用进程级缓存能力探测。
     */
    fun resolve(request: EchoRenderRequest, env: EchoEnvironmentSnapshot? = null): BackendResolution {
        val agslAvailable = env?.runtimeShader ?: AgslEchoBackend.isAvailable()
        val requested = request.requestedTier
        // §97：ULTRA 请求按 ADVANCED 解析（永不自动启用）
        val effectiveRequest = when (requested) {
            EchoRenderTier.ULTRA -> EchoRenderTier.ADVANCED
            else -> requested
        }
        var resolvedTier = EchoRenderTier.LEGACY
        var backendName = BACKEND_CANVAS
        var reason: String? = null
        when {
            effectiveRequest == EchoRenderTier.LEGACY -> {
                resolvedTier = EchoRenderTier.LEGACY
                backendName = BACKEND_CANVAS
            }
            !agslAvailable -> {
                resolvedTier = EchoRenderTier.LEGACY
                backendName = BACKEND_CANVAS
                reason = if (Build.VERSION.SDK_INT < 33) {
                    "AGSL unavailable (API<33)"
                } else {
                    "AGSL unavailable (RuntimeShader)"
                }
            }
            effectiveRequest == EchoRenderTier.ADVANCED && !AgslEchoBackend.isAdvancedAvailable() -> {
                resolvedTier = EchoRenderTier.STANDARD
                backendName = BACKEND_AGSL
                reason = if (Build.VERSION.SDK_INT < 36) {
                    "ADVANCED unavailable (API<36) — downgraded to AGSL"
                } else {
                    "ADVANCED unavailable (RuntimeColorFilter) — downgraded to AGSL"
                }
            }
            effectiveRequest == EchoRenderTier.ADVANCED -> {
                resolvedTier = EchoRenderTier.ADVANCED
                backendName = BACKEND_AGSL_ADVANCED
            }
            else -> {
                resolvedTier = EchoRenderTier.STANDARD
                backendName = BACKEND_AGSL
            }
        }
        val baseQuality = request.quality ?: defaultQualityFor(request.surface)
        val quality = if (env != null) {
            EchoRenderEnvironment.worseOf(baseQuality, env.quality)
        } else {
            baseQuality
        }
        return BackendResolution(
            requestedTier = requested,
            resolvedTier = resolvedTier,
            backendName = backendName,
            quality = quality,
            reducedMotion = reducedMotionFor(request.motion),
            motionScale = motionScaleFor(request.motion),
            hdr = false, // §T：恒 false（BLOCKED：真实 HdrCapabilities 能力存在前）
            wideColorGamut = env?.wideGamut ?: false,
            reason = reason,
        )
    }

    /**
     * §R Compose 入口：单 surface 一次高成本会话——委托既有 [EchoOrganism]
     * （帧钟/AGSL session 复用/语义描述均由其承载）；quality/tier 随环境快照
     * （rememberEchoEnvironment 5s 轮询）可观察变化。
     */
    @Composable
    fun Organism(
        request: EchoRenderRequest,
        modifier: Modifier = Modifier,
        aggregateDescription: String? = null,
        correctionPulseTrigger: Long = 0,
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val env = rememberEchoEnvironment(context).value
        val resolution = resolve(request, env)
        EchoOrganism(
            genome = request.genome,
            modifier = modifier,
            surface = request.surface,
            motion = request.motion,
            maturityName = request.maturityName,
            aggregateDescription = aggregateDescription,
            options = OrganismFrameComputer.EchoRenderOptions(
                tier = resolution.resolvedTier,
                quality = resolution.quality,
                interaction = request.interaction,
            ),
            correctionPulseTrigger = correctionPulseTrigger.toInt(),
        )
    }

    /** §R 非 Compose 会话（Wallpaper / Dream / 离屏导出）。 */
    fun createSession(request: EchoRenderRequest, width: Int, height: Int): EchoRenderSession =
        EchoRenderSession(request, width, height)
}

/**
 * §R 渲染会话：crop → compute → backend dispatch 全在内；
 * 帧路径只喂 clock（+ 可选 per-frame interaction，如 Wallpaper 触摸涟漪）。
 */
class EchoRenderSession(
    internal val request: EchoRenderRequest,
    val width: Int,
    val height: Int,
) {
    val resolution: BackendResolution = EchoRendererFacade.resolve(request)

    private var agslSession: AgslEchoBackend.AgslSession? = null

    /** 渲染一帧到给定 Canvas（时间基准 = boot-global EchoVisualClock.nowNanos()）。 */
    fun draw(canvas: Canvas, clockNanos: Long, interaction: EchoInteractionSpec = request.interaction) {
        val frame = computeFrame(clockNanos, interaction)
        dispatch(canvas, frame)
    }

    /** 离屏渲染一帧（真实后端：CANVAS 或 AGSL→bitmap；导出/仪器化视觉门共用）。 */
    fun renderToBitmap(clockNanos: Long): Bitmap {
        val frame = computeFrame(clockNanos, request.interaction)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dispatch(Canvas(bitmap), frame)
        return bitmap
    }

    private fun computeFrame(
        clockNanos: Long,
        interaction: EchoInteractionSpec,
    ) = OrganismFrameComputer.compute(
        spec = SurfacePolicy.crop(request.genome, request.surface, clockNanos / 1_000_000_000f),
        width = width.toFloat(),
        height = height.toFloat(),
        options = OrganismFrameComputer.EchoRenderOptions(
            maturityName = request.maturityName,
            tier = resolution.resolvedTier,
            quality = resolution.quality,
            reducedMotion = resolution.reducedMotion,
            motionScale = resolution.motionScale,
            hdrEligible = false, // §T
            interaction = interaction,
        ),
    )

    private fun dispatch(canvas: Canvas, frame: com.yunjue.echo.mind.visual.render.OrganismFrame) {
        val backend = resolution.backendName
        if (backend != EchoRendererFacade.BACKEND_CANVAS && Build.VERSION.SDK_INT >= 33) {
            val session = agslSessionFor(backend == EchoRendererFacade.BACKEND_AGSL_ADVANCED)
            if (session != null) {
                val palette = EchoIdentitySpec.derive(request.genome.identitySeed).palette
                session.draw(
                    canvas = canvas,
                    frame = frame,
                    widthPx = width.toFloat(),
                    heightPx = height.toFloat(),
                    exposure = request.genome.luminance,
                    halo = request.genome.haloIntensity,
                    primaryColor = ColorSpace.lch(palette.primary.l, palette.primary.c, palette.primary.h),
                    secondaryColor = ColorSpace.lch(palette.secondary.l, palette.secondary.c, palette.secondary.h),
                    warmColor = ColorSpace.lch(palette.warm.l, palette.warm.c, palette.warm.h),
                )
                return
            }
        }
        OrganismCanvasRenderer.draw(canvas, frame, width.toFloat(), height.toFloat())
    }

    private fun agslSessionFor(advanced: Boolean): AgslEchoBackend.AgslSession? {
        if (Build.VERSION.SDK_INT < 33) return null
        val s = agslSession
        if (s != null && s.width == width && s.height == height && s.advanced == advanced) return s
        return AgslEchoBackend.AgslSession(width, height, advanced).also { agslSession = it }
    }

    companion object {
        /** §P：Journey minis 命名低预算预设（tier LEGACY / quality MINIMAL / JOURNEY_PRIVATE）。 */
        fun journeyThumbnailRequest(
            genome: EchoVisualGenome,
            maturityName: String = "KNOWN",
        ): EchoRenderRequest = EchoRenderRequest(
            genome = genome,
            surface = EchoSurface.JOURNEY_PRIVATE,
            motion = MotionPolicy.NORMAL,
            maturityName = maturityName,
            requestedTier = EchoRenderTier.LEGACY,
            quality = EchoRenderQuality.MINIMAL,
        )
    }
}
