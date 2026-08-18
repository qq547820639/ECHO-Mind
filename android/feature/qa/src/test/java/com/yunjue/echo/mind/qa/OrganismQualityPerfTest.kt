package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.presencevisual.AgslEchoBackend
import com.yunjue.echo.mind.presencevisual.OrganismCanvasRenderer
import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.render.ColorSpace
import com.yunjue.echo.mind.visual.render.OrganismFrameComputer
import com.yunjue.echo.mind.visual.surface.EchoSurface
import com.yunjue.echo.mind.visual.surface.SurfacePolicy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Organism Quality §36 — 性能证据（JVM 可测子集；Robolectric NATIVE 真实 android.graphics）。
 *
 * 测量：CPU 帧求值（几何）/ Canvas raster / AGSL mask raster（Advanced 输入成本）/
 * 热路径分配（粗粒度）。GPU shader 执行/帧率/电池/显存 = 真机项（BLOCKED_EXTERNAL_DEVICE）。
 * 输出 stdout 证据（qa/reports/ECHO_ORGANISM_QUALITY_PERFORMANCE.md 引用）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrganismQualityPerfTest {

    private val W = 1080
    private val H = 2340

    @Test
    fun masterFramePerformanceEvidence() {
        val genome = OrganismQualityHarness.masterGenome()
        val spec = SurfacePolicy.crop(genome, EchoSurface.APP_PRIVATE, 12f)
        val options = OrganismFrameComputer.EchoRenderOptions(maturityName = "KNOWN")

        // 预热（拓扑缓存/JIT）
        repeat(5) { OrganismFrameComputer.compute(spec, W.toFloat(), H.toFloat(), options) }
        val frame = OrganismFrameComputer.compute(spec, W.toFloat(), H.toFloat(), options)

        // 1. CPU 几何（帧求值）
        val t0 = System.nanoTime()
        repeat(30) { OrganismFrameComputer.compute(spec, W.toFloat(), H.toFloat(), options) }
        val computeMs = (System.nanoTime() - t0) / 1_000_000.0 / 30

        // 2. Canvas raster（LEGACY 正式后端）
        val bitmap = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val t1 = System.nanoTime()
        repeat(10) { OrganismCanvasRenderer.draw(canvas, frame, W.toFloat(), H.toFloat()) }
        val canvasMs = (System.nanoTime() - t1) / 1_000_000.0 / 10

        // 3. AGSL mask raster（Advanced 材质输入的 CPU 成本；shader 执行属设备 GPU）
        val identity = EchoIdentitySpec.derive(genome.identitySeed)
        val warmColor = ColorSpace.lch(
            identity.palette.warm.l, identity.palette.warm.c, identity.palette.warm.h,
        )
        val t2 = System.nanoTime()
        repeat(10) { AgslEchoBackend.rasterizeMaskForInspection(frame, W, H, warmColor) }
        val maskMs = (System.nanoTime() - t2) / 1_000_000.0 / 10

        // 4. 热路径分配（粗粒度：100 帧 compute 的堆增量）
        runGc()
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        repeat(100) { OrganismFrameComputer.compute(spec, W.toFloat(), H.toFloat(), options) }
        runGc()
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val allocKbPer100 = (after - before).coerceAtLeast(0) / 1024.0

        println(
            "ORGANISM_PERF compute=" + "%.2f".format(computeMs) + "ms" +
                " canvasRaster=" + "%.2f".format(canvasMs) + "ms" +
                " agslMaskRaster=" + "%.2f".format(maskMs) + "ms" +
                " allocPer100Frames=" + "%.0f".format(allocKbPer100) + "KB" +
                " particles=" + frame.particles.size +
                " rings=" + frame.structuralRings.size +
                " longs=" + frame.longFilaments.size +
                " fragments=" + frame.localFragments.size,
        )
    }

    @Suppress("ExplicitGarbageCollectionCall") // 测量用堆基线整理（非生产代码路径）
    private fun runGc() {
        System.gc()
        Thread.sleep(50)
    }
}
