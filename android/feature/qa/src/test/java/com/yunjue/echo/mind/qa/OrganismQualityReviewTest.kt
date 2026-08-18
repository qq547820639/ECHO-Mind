package com.yunjue.echo.mind.qa

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Organism Quality Pass — 评审工件生成（qa/visual-review/organism-quality/）。
 *
 * 五张固定人眼评审 PNG + Canvas/AGSL A/B appendix + Identity A vs B 对比 + SUMMARY.md，
 * 全部经 production facade session 真实后端渲染（Robolectric NATIVE 在 JVM 执行真实 AGSL；
 * 探针见 AgslJvmProbeTest）。本测试只断言「工件真实生成 + 后端真值落盘」，
 * 视觉门阈值断言由 CanvasFallbackVisualGateTest / AdvancedBackendVisualGate 承担。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrganismQualityReviewTest {

    @Test
    fun renderReviewArtifactsViaProductionFacade() {
        val outDir = File(outputRoot(), "organism-quality")
        outDir.mkdirs()

        val results = OrganismQualityHarness.reviewShots().map { OrganismQualityHarness.renderShot(it, outDir) }

        // 五张固定评审图 + appendix 全部落盘
        listOf(
            "01_KNOWN_DAY28_APP", "02_SEED_APP", "03_QUIET_APP",
            "04_KNOWN_DAY28_WALLPAPER", "APPENDIX_01_CANVAS",
        ).forEach { id ->
            val r = results.first { it.id == id }
            assertTrue("$id PNG 落盘（production 后端 ${r.actualBackend}）", r.pngFile.length() > 0)
            assertTrue("$id metrics JSON 落盘", r.metricsFile.length() > 0)
            assertTrue(
                "$id 后端真值三写（requested/resolved/actual）",
                r.metricsFile.readText().contains("\"requestedBackend\"") &&
                    r.metricsFile.readText().contains("\"resolvedBackend\"") &&
                    r.metricsFile.readText().contains("\"actualBackend\""),
            )
        }

        // 05 Identity A vs B（同一 KNOWN Day28 genome，仅 identity 不同 → 结构必须不同）
        val aBitmap = renderBitmap(OrganismQualityHarness.masterGenome(OrganismQualityHarness.IDENTITY_A))
        val bBitmap = renderBitmap(OrganismQualityHarness.masterGenome(OrganismQualityHarness.IDENTITY_B))
        val sheet = VisualReviewRenderer.contactSheet(
            listOf(
                VisualReviewRenderer.SheetCell("IDENTITY A · seed ${OrganismQualityHarness.IDENTITY_A}", aBitmap),
                VisualReviewRenderer.SheetCell("IDENTITY B · seed ${OrganismQualityHarness.IDENTITY_B}", bBitmap),
            ),
            columns = 2, cellWidth = 420, title = "Identity A vs B — KNOWN Day28 APP",
        )
        VisualReviewRenderer.writePng(sheet, File(outDir, "05_IDENTITY_A_VS_B.png"))
        assertTrue("Identity 对比图落盘", File(outDir, "05_IDENTITY_A_VS_B.png").length() > 0)

        // Advanced 材质输入证据：生产 vector mask（R 冷几何 / G 暖几何；B 深度通道升级后同图可见）
        // ——AGSL raster 需设备 HW canvas（BLOCKED_EXTERNAL_DEVICE），mask 是 JVM 可验证真值。
        runCatching {
            val genome = OrganismQualityHarness.masterGenome()
            val frame = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                    genome, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE, 12f,
                ),
                OrganismQualityHarness.APP_WIDTH.toFloat(), OrganismQualityHarness.APP_HEIGHT.toFloat(),
                com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(maturityName = "KNOWN"),
            )
            val palette = com.yunjue.echo.mind.visual.model.EchoIdentitySpec.derive(genome.identitySeed).palette
            val mask = com.yunjue.echo.mind.presencevisual.AgslEchoBackend.rasterizeMaskForInspection(
                frame, OrganismQualityHarness.APP_WIDTH, OrganismQualityHarness.APP_HEIGHT,
                com.yunjue.echo.mind.visual.render.ColorSpace.lch(palette.warm.l, palette.warm.c, palette.warm.h),
            )
            VisualReviewRenderer.writePng(mask, File(outDir, "APPENDIX_ADVANCED_MASK.png"))
        }
        assertTrue(
            "Advanced mask 证据落盘（JVM 可栅格化 mask 输入；AGSL raster 属设备 HW 门）",
            File(outDir, "APPENDIX_ADVANCED_MASK.png").length() > 0,
        )

        OrganismQualityHarness.writeSummary(
            results, outDir,
            extraNotes = listOf(
                "01/02/03 请求 ADVANCED：API≥36 真机 resolution=AGSL_ADVANCED，API 33–35（含本 JVM）resolution=AGSL；离屏 raster 恒 CANVAS（软件位图无法执行 RuntimeShader——Android 真实约束，reason 落盘）。",
                "AGSL raster 视觉证据 = APPENDIX_ADVANCED_MASK.png（生产 mask 输入）+ 设备 instrumented 门（BLOCKED_EXTERNAL_DEVICE：本环境无真机/模拟器）。",
                "04 Wallpaper 按生产请求 LEGACY/CANVAS + CONSERVE（电池敏感面不默认 AGSL）。",
                "05 为 production facade 同链渲染的双 identity 对比（结构差异 ≠ 换色）。",
                "人眼审美结论 = PENDING_PRINCIPAL_VISUAL_REVIEW；本表只报告自动 guardrail 指标。",
            ),
        )
        assertTrue("SUMMARY.md 落盘", File(outDir, "SUMMARY.md").length() > 0)
    }

    /** production facade session → 真实解析后端 bitmap（ADVANCED 请求）。 */
    private fun renderBitmap(genome: com.yunjue.echo.mind.visual.model.EchoVisualGenome): android.graphics.Bitmap {
        val session = com.yunjue.echo.mind.presencevisual.EchoRendererFacade.createSession(
            com.yunjue.echo.mind.presencevisual.EchoRenderRequest(
                genome = genome,
                surface = com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                maturityName = "KNOWN",
                requestedTier = com.yunjue.echo.mind.visual.render.EchoRenderTier.ADVANCED,
                quality = com.yunjue.echo.mind.visual.render.EchoRenderQuality.NORMAL,
            ),
            OrganismQualityHarness.APP_WIDTH, OrganismQualityHarness.APP_HEIGHT,
        )
        return session.renderToBitmap(OrganismQualityHarness.CANONICAL_CLOCK_NANOS)
    }

    private fun outputRoot(): File {
        System.getProperty("echo.visualReview.out")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, ".git").exists() && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        var root: File? = dir
        while (root != null && !File(root, ".git").exists()) root = root.parentFile
        return root?.resolve("qa/visual-review") ?: File("qa/visual-review")
    }
}
