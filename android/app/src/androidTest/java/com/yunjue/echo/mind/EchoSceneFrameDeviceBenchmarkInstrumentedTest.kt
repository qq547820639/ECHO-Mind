package com.yunjue.echo.mind
import com.yunjue.echo.mind.model.echoMaturity

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.journey.JourneyScale
import com.yunjue.echo.mind.journey.JourneySyncStatus
import com.yunjue.echo.mind.journey.assembleJourneyUiState
import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.presence.WallpaperRenderController
import com.yunjue.echo.mind.presence.deriveIdentityGenome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * ERA 43 — 设备锚点性能烟测（CI connected-test API 34/36 emulator 执行）：
 *
 * - §65 硬指标设备烟测：不可见 → renderActive=false / onTouch()=false（真机状态机路径）；
 * - 首帧计算设备锚点：computeEchoSceneFrame × 1000 在真实 ART 运行时（非 JVM）计时；
 * - §108 设备锚点：Journey 365 天完整 UI 状态装配在设备上计时。
 *
 * 预算取「模拟器可稳定通过」的宽松上界（CI 模拟器无硬件加速时约 10-50× 慢）；
 * 实测数字以 info 日志逐次记录（对应 PRESENCE_BENCHMARKS §2「逐次记录」）。
 * 真机严格基线（CPU/GPU/wakeups/battery）由部署侧 adb 采样执行，不在本测试伪造。
 */
@RunWith(AndroidJUnit4::class)
class EchoSceneFrameDeviceBenchmarkInstrumentedTest {

    private fun measureMs(iterations: Int, block: () -> Unit): Double {
        block() // warmup（ART JIT）
        var best = Double.MAX_VALUE
        repeat(iterations) {
            val start = System.nanoTime()
            block()
            best = minOf(best, (System.nanoTime() - start) / 1_000_000.0)
        }
        return best
    }

    @Test
    fun wallpaperControllerInvisibleZeroRenderOnDevice() {
        val controller = WallpaperRenderController()
        controller.onVisibilityChanged(false)
        assertFalse(controller.renderActive)
        assertFalse(controller.onTouch())

        controller.onVisibilityChanged(true)
        controller.onSurfaceChanged()
        assertTrue(controller.renderActive)

        controller.onDestroy()
        assertFalse(controller.renderActive)
        controller.onVisibilityChanged(true)
        assertFalse("destroy 后永久停止", controller.renderActive)
    }

    @Test
    fun sceneFrameComputationDeviceAnchor() {
        val state = com.yunjue.echo.mind.model.EchoPresenceState(
            updatedAt = java.time.Instant.EPOCH,
            maturity = echoMaturity(30),
            identityGenome = deriveIdentityGenome(42L, 0.6f, PresenceMotionLevel.DEFAULT),
        )
        val params = EchoVisualMapper.map(state = state, hourOfDay = 14f, surface = SurfaceMode.APP)
        val ms = measureMs(3) {
            repeat(1000) {
                com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                    spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                        com.yunjue.echo.mind.journey.JourneyOrganismVisuals.genomeFromParams(params, 42L),
                        com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                        JOURNEY_CANONICAL_TIME_SECONDS,
                    ),
                    width = 1080f,
                    height = 2340f,
                )
            }
        }
        android.util.Log.i(
            "EchoDeviceBench",
            "scene_frame_1000_ms=$ms deviceAnchor(emulatorBudget<10000)",
        )
        assertTrue("设备首帧计算 1000 次耗时 ${"%.1f".format(ms)}ms 超出模拟器预算 10000ms", ms < 10000.0)
    }

    @Test
    fun journey365AssemblyDeviceAnchor() {
        val start = LocalDate.of(2026, 1, 1)
        val portraits = (0 until 365).map { i ->
            DailyPortraitDto(
                date = start.plusDays(i.toLong()).toString(),
                status = "READY",
                confidence = "HIGH",
                baselineDays = i % 120,
                headline = listOf("接近"),
                summary = "今天和平时很接近。",
                dimensions = mapOf(
                    "MOVEMENT" to PortraitDimensionDto(value = "SIMILAR", metric = "movement_index", z = 0.4),
                    "RHYTHM" to PortraitDimensionDto(value = "SIMILAR", metric = "active_start_minute", z = 0.4),
                ),
            )
        }
        val timeline = PortraitTimelineUiState(days = 365, loading = false, portraits = portraits)
        val ms = measureMs(3) {
            assembleJourneyUiState(
                scale = JourneyScale.YEAR,
                timeline = timeline,
                permissionEnabled = true,
                narrative = null,
                runtimeAvailability = null,
                runtimeDiagnostics = null,
                showEvidence = false,
                intelligenceAvailable = false,
                syncStatus = JourneySyncStatus(consent = true, permissionEnabled = true),
                journeySeed = 42L,
            )
        }
        android.util.Log.i(
            "EchoDeviceBench",
            "journey_365_assembly_ms=$ms deviceAnchor(emulatorBudget<10000)",
        )
        assertTrue("Journey 365 天装配耗时 ${"%.1f".format(ms)}ms 超出模拟器预算 10000ms", ms < 10000.0)
    }
}
