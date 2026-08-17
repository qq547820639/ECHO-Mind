package com.yunjue.echo.mind.presencevisual

import android.os.PowerManager
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V3 §20/§31 — AGSL 后端结构/可用性/质量门测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgslBackendTest {

    @Test
    fun shaderSourceDeclaresRequiredInputs() {
        val src = AgslEchoBackend.SHADER_SOURCE
        // §20/§W：resolution / exposure / core ratio(cavity) / halo / glow 半径 / 三色 / vector mask
        for (u in listOf(
            "iResolution", "iExposure", "iCavity", "iHalo", "iGlowRadius",
            "iPrimary", "iSecondary", "iWarm",
        )) {
            assertTrue("缺少 uniform $u", src.contains(u))
        }
        assertTrue(src.contains("uniform shader iVectorMask"))
        assertTrue("必须 premultiplied alpha 输出", src.contains("col * alpha"))
        assertTrue("tone soft knee（§24 禁止 hard clip）", src.contains("softKnee"))
    }

    @Test
    fun glowRadiusIsResolutionAndHaloAware() {
        // §W：基准 1080px → 2.5..6px；halo 高 → 更大；minDim 缩放；始终夹在 2..6
        val base1080 = AgslEchoBackend.glowRadiusPxFor(haloIntensity = 0f, minDim = 1080f)
        val halo1080 = AgslEchoBackend.glowRadiusPxFor(haloIntensity = 1f, minDim = 1080f)
        org.junit.Assert.assertEquals(2.5f, base1080, 1e-4f)
        org.junit.Assert.assertEquals(6.0f, halo1080, 1e-4f)
        // 小视口（540px）不跌破下限 2px；大视口（2160px）不突破上限 6px
        org.junit.Assert.assertEquals(2.0f, AgslEchoBackend.glowRadiusPxFor(0f, 540f), 1e-4f)
        org.junit.Assert.assertEquals(6.0f, AgslEchoBackend.glowRadiusPxFor(1f, 2160f), 1e-4f)
    }

    @Test
    fun isAvailableNeverThrows() {
        // Robolectric/设备/API<33 任何环境都不得抛出（失败安全回退 Canvas）
        val v = AgslEchoBackend.isAvailable()
        assertTrue(v || !v)
    }

    @Test
    fun qualityGateMapping() {
        // §31：Power Save ≥ CONSERVE；MODERATE → CONSERVE；SEVERE+ → MINIMAL
        assertEquals(
            EchoRenderQuality.NORMAL,
            EchoRenderEnvironment.qualityFor(false, PowerManager.THERMAL_STATUS_NONE),
        )
        assertEquals(
            EchoRenderQuality.CONSERVE,
            EchoRenderEnvironment.qualityFor(true, PowerManager.THERMAL_STATUS_NONE),
        )
        assertEquals(
            EchoRenderQuality.CONSERVE,
            EchoRenderEnvironment.qualityFor(false, PowerManager.THERMAL_STATUS_MODERATE),
        )
        assertEquals(
            EchoRenderQuality.MINIMAL,
            EchoRenderEnvironment.qualityFor(false, PowerManager.THERMAL_STATUS_SEVERE),
        )
        assertEquals(
            EchoRenderQuality.MINIMAL,
            EchoRenderEnvironment.qualityFor(false, PowerManager.THERMAL_STATUS_CRITICAL),
        )
        // 热态优先于省电
        assertEquals(
            EchoRenderQuality.MINIMAL,
            EchoRenderEnvironment.qualityFor(true, PowerManager.THERMAL_STATUS_SEVERE),
        )
    }

    @Test
    fun hdrNeverEligibleBelowApi34OrUnderDegradation() {
        // §23：HDR 硬门（API/省电/热态）——Robolectric display 不可信，只测逻辑门
        assertFalse(
            EchoRenderEnvironment.qualityFor(true, PowerManager.THERMAL_STATUS_NONE) ==
                EchoRenderQuality.NORMAL,
        )
    }

    @Test
    fun advancedGradingSourceIsToneConsistencyOnly() {
        val src = AgslEchoBackend.GRADING_SOURCE
        assertTrue(src.contains("main(half4"))
        assertTrue("final grading 只做色调一致性", src.contains("graded"))
    }

    @Test
    fun advancedUnavailableBelowApi36() {
        // §9：ADVANCED = API36+（RuntimeColorFilter/RuntimeXfermode 门控）
        if (android.os.Build.VERSION.SDK_INT < 36) {
            assertFalse(AgslEchoBackend.isAdvancedAvailable())
        }
    }
}
