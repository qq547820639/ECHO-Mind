package com.yunjue.echo.mind.qa

import android.graphics.Bitmap
import com.yunjue.echo.mind.presencevisual.EchoRenderRequest
import com.yunjue.echo.mind.presencevisual.EchoRendererFacade
import com.yunjue.echo.mind.presencevisual.VisualLabMetrics
import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.model.EchoVisualGenome
import com.yunjue.echo.mind.visual.render.EchoRenderTier
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.MotionPolicy
import com.yunjue.echo.mind.visual.testing.VisualLabFixtures
import java.io.File

/**
 * Organism Quality Pass — production-renderer 评审工件生成器（qa/visual-review/organism-quality/）。
 *
 * 纪律：
 * - 所有 PNG / metrics 均经 **production facade session**
 *   （EchoRendererFacade.createSession → renderToBitmap：真实后端 dispatch——JVM Robolectric
 *   NATIVE 可执行真实 AGSL；CANVAS 亦为生产正式后端）；
 * - 不重新实现 QA mirror renderer；
 * - 后端真值三元组 requestedBackend / resolvedBackend / actualBackend + 降级 reason 必写。
 *
 * MASTER_REFERENCE（本轮唯一第一参考）：
 * PROFILE_A_STABLE identity（seed 7710）× KNOWN Day28 reference genome（V3 §35 Lab fixture）
 * · APP_PRIVATE · 412×915dp（1080×2340 @2.625）· NORMAL motion · 请求 ADVANCED tier
 * （API≥36 真机解析 AGSL_ADVANCED；API 33–35 / JVM 解析 AGSL——记录 reason，不伪装）。
 */
object OrganismQualityHarness {

    /** 412×915dp @ 2.625x（与 VisualReviewRenderer APP 视口一致）。 */
    const val APP_WIDTH = VisualReviewRenderer.APP_WIDTH
    const val APP_HEIGHT = VisualReviewRenderer.APP_HEIGHT
    const val WALLPAPER_WIDTH = VisualReviewRenderer.WALLPAPER_WIDTH
    const val WALLPAPER_HEIGHT = VisualReviewRenderer.WALLPAPER_HEIGHT

    /** 评审 canonical 时刻（秒→nanos；与 Lab / Journey 同基准正午锚）。 */
    const val CANONICAL_CLOCK_NANOS = 12_000_000_000L

    /** 评审 identity（QaProfiles 长程 fixture 的真实 identitySeed）。 */
    val IDENTITY_A: Long = QaProfiles.A_STABLE.identitySeed
    val IDENTITY_B: Long = QaProfiles.B_NIGHT_OWL.identitySeed

    data class ShotSpec(
        val id: String,
        val genome: EchoVisualGenome,
        val maturityName: String,
        val surface: EchoSurface,
        val width: Int,
        val height: Int,
        val requestedTier: EchoRenderTier,
        val motion: MotionPolicy = MotionPolicy.NORMAL,
        /** null = defaultQualityFor(surface)。 */
        val quality: com.yunjue.echo.mind.visual.render.EchoRenderQuality? =
            if (surface == EchoSurface.APP_PRIVATE) {
                com.yunjue.echo.mind.visual.render.EchoRenderQuality.NORMAL
            } else {
                null
            },
    )

    data class ShotResult(
        val id: String,
        val pngFile: File,
        val metricsFile: File,
        val requestedBackend: String,
        val resolvedBackend: String,
        val actualBackend: String,
        val reason: String?,
        val metrics: VisualLabMetrics.Metrics,
        val gate: VisualLabMetrics.GateResult,
    ) {
        val usedProductionFacade: Boolean get() = true
    }

    /** MASTER_REFERENCE genome：PROFILE_A identity × KNOWN Day28 reference。 */
    fun masterGenome(seed: Long = IDENTITY_A): EchoVisualGenome =
        VisualLabFixtures.withSeed(VisualLabFixtures.genomeFor(VisualLabFixtures.Preset.KNOWN_DAY28), seed)

    fun seedGenome(seed: Long = IDENTITY_A): EchoVisualGenome =
        VisualLabFixtures.withSeed(VisualLabFixtures.genomeFor(VisualLabFixtures.Preset.SEED), seed)

    fun quietGenome(seed: Long = IDENTITY_A): EchoVisualGenome =
        VisualLabFixtures.withSeed(VisualLabFixtures.genomeFor(VisualLabFixtures.Preset.QUIET), seed)

    /** 本轮固定五张人眼评审 shot + engineering appendix 定义。 */
    fun reviewShots(): List<ShotSpec> = listOf(
        ShotSpec(
            id = "01_KNOWN_DAY28_APP",
            genome = masterGenome(),
            maturityName = "KNOWN",
            surface = EchoSurface.APP_PRIVATE,
            width = APP_WIDTH, height = APP_HEIGHT,
            requestedTier = EchoRenderTier.ADVANCED,
        ),
        ShotSpec(
            id = "02_SEED_APP",
            genome = seedGenome(),
            maturityName = "SEED",
            surface = EchoSurface.APP_PRIVATE,
            width = APP_WIDTH, height = APP_HEIGHT,
            requestedTier = EchoRenderTier.ADVANCED,
        ),
        ShotSpec(
            id = "03_QUIET_APP",
            genome = quietGenome(),
            maturityName = "KNOWN",
            surface = EchoSurface.APP_PRIVATE,
            width = APP_WIDTH, height = APP_HEIGHT,
            requestedTier = EchoRenderTier.ADVANCED,
        ),
        ShotSpec(
            id = "04_KNOWN_DAY28_WALLPAPER",
            genome = masterGenome(),
            maturityName = "KNOWN",
            surface = EchoSurface.WALLPAPER_VISUAL_ONLY,
            width = WALLPAPER_WIDTH, height = WALLPAPER_HEIGHT,
            // 生产 Wallpaper 请求：LEGACY/CANVAS + CONSERVE（电池敏感面不默认 AGSL）
            requestedTier = EchoRenderTier.LEGACY,
        ),
        ShotSpec(
            id = "APPENDIX_01_CANVAS",
            genome = masterGenome(),
            maturityName = "KNOWN",
            surface = EchoSurface.APP_PRIVATE,
            width = APP_WIDTH, height = APP_HEIGHT,
            requestedTier = EchoRenderTier.LEGACY,
        ),
    )

    /** 渲染单张：production facade session → 真实后端 PNG + metrics JSON（含后端真值）。 */
    fun renderShot(spec: ShotSpec, outDir: File): ShotResult {
        val request = EchoRenderRequest(
            genome = spec.genome,
            surface = spec.surface,
            motion = spec.motion,
            maturityName = spec.maturityName,
            requestedTier = spec.requestedTier,
            quality = spec.quality,
        )
        val session = EchoRendererFacade.createSession(request, spec.width, spec.height)
        val resolution = session.resolution
        // 离屏软件位图无法执行 RuntimeShader（Android 真实约束）——离屏 raster 恒走生产
        // CANVAS 后端；AGSL 材质证据以 mask 输入 + 设备 HW 门承担（见 APPENDIX_MASK）。
        val bitmap = session.renderToBitmap(CANONICAL_CLOCK_NANOS)

        val identity = EchoIdentitySpec.derive(spec.genome.identitySeed)
        val baseR = OrganismFrameComputer.baseRadiusFor(
            spec.genome.radialSpread, spec.genome.coreIntensity, identity.membraneBias,
        )
        val metrics = VisualLabMetrics.compute(
            bitmap, spec.width / 2f, spec.height / 2f, baseR * minOf(spec.width, spec.height),
        )
        val gate = VisualLabMetrics.evaluate(metrics)

        outDir.mkdirs()
        val pngFile = File(outDir, "${spec.id}.png")
        pngFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val metricsFile = File(outDir, "${spec.id}.metrics.json")
        metricsFile.writeText(metricsJson(spec, resolution, session, metrics, gate))
        bitmap.recycle()
        return ShotResult(
            id = spec.id,
            pngFile = pngFile,
            metricsFile = metricsFile,
            requestedBackend = request.requestedTier.name,
            resolvedBackend = resolution.resolvedTier.name,
            actualBackend = session.offscreenActualBackend,
            reason = session.offscreenReason,
            metrics = metrics,
            gate = gate,
        )
    }

    private fun metricsJson(
        spec: ShotSpec,
        resolution: com.yunjue.echo.mind.presencevisual.BackendResolution,
        session: com.yunjue.echo.mind.presencevisual.EchoRenderSession,
        m: VisualLabMetrics.Metrics,
        gate: VisualLabMetrics.GateResult,
    ): String {
        fun v(f: Float) = "%.5f".format(f)
        return """
            {
              "id": "${spec.id}",
              "surface": "${spec.surface.name}",
              "maturity": "${spec.maturityName}",
              "viewport": ${spec.width}x${spec.height},
              "identitySeed": ${spec.genome.identitySeed},
              "requestedBackend": "${spec.requestedTier.name}",
              "resolvedBackend": "${resolution.resolvedTier.name}",
              "actualBackend": "${session.offscreenActualBackend}",
              "resolutionBackend": "${resolution.backendName}",
              "reason": ${session.offscreenReason?.let { "\"$it\"" } ?: "null"},
              "quality": "${resolution.quality.name}",
              "metrics": {
                "nearBlackRatio": ${v(m.nearBlackRatio)},
                "highLuminanceRatio": ${v(m.highLuminanceRatio)},
                "extremeGlintRatio": ${v(m.extremeGlintRatio)},
                "warmRatio": ${v(m.warmRatio)},
                "negativeSpaceRatio": ${v(m.negativeSpaceRatio)},
                "visualMassInside": ${v(m.visualMassInside)},
                "organismWidthFraction": ${v(m.organismWidthFraction)},
                "organismHeightFraction": ${v(m.organismHeightFraction)},
                "edgeDensity": ${v(m.edgeDensity)},
                "centerLuminance": ${v(m.centerLuminance)},
                "outerLuminance": ${v(m.outerLuminance)}
              },
              "gate": {
                "nearBlackPass": ${gate.nearBlackPass},
                "highLuminancePass": ${gate.highLuminancePass},
                "extremeGlintPass": ${gate.extremeGlintPass},
                "warmPass": ${gate.warmPass},
                "warmTargetPass": ${gate.warmTargetPass},
                "negativeSpacePass": ${gate.negativeSpacePass},
                "visualMassPass": ${gate.visualMassPass},
                "organismWidthPass": ${gate.organismWidthPass},
                "cavityPass": ${gate.cavityPass},
                "allPass": ${gate.allPass}
              }
            }
        """.trimIndent()
    }

    /** SUMMARY.md：人眼评审 + 自动门总表（后端真值列必含）。 */
    fun writeSummary(results: List<ShotResult>, outDir: File, extraNotes: List<String> = emptyList()) {
        val sb = StringBuilder()
        sb.appendLine("# Organism Quality Pass — 自动指标总表")
        sb.appendLine()
        sb.appendLine("| Shot | 后端（请求→实际） | nearBlack | highLum | glint | warm | negSpace | mass@.9R | bbox W×H | edge | cavity | 门 |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in results) {
            val backend = "${r.requestedBackend}→${r.actualBackend}" +
                (r.reason?.let { "（$it）" } ?: "")
            sb.appendLine(
                "| ${r.id} | $backend " +
                    "| ${pct(r.metrics.nearBlackRatio)} | ${pct(r.metrics.highLuminanceRatio)} " +
                    "| ${pct(r.metrics.extremeGlintRatio)} | ${pct(r.metrics.warmRatio)} " +
                    "| ${pct(r.metrics.negativeSpaceRatio)} | ${pct(r.metrics.visualMassInside)} " +
                    "| ${pct(r.metrics.organismWidthFraction)}×${pct(r.metrics.organismHeightFraction)} " +
                    "| %.3f".format(r.metrics.edgeDensity) +
                    "| %.3f".format(r.metrics.centerLuminance) +
                    "| ${if (r.gate.allPass) "PASS" else "FAIL"} |",
            )
        }
        sb.appendLine()
        sb.appendLine("阈值：nearBlack≥58% · highLum≤4% · glint≤2.5% · warm target≤10%（hard≤15%）· negSpace≥40% · mass@.9R≥82% · bbox 宽 72–82% viewport · cavity<0.45")
        sb.appendLine()
        for (n in extraNotes) sb.appendLine("- $n")
        File(outDir, "SUMMARY.md").writeText(sb.toString())
    }

    private fun pct(f: Float): String = "%.1f%%".format(f * 100)
}
