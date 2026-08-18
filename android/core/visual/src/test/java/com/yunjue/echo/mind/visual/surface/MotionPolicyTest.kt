package com.yunjue.echo.mind.visual.surface

import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8-P2-8 补缺：MotionPolicy 降级群直接锁定（此前仅 compile 末端间接覆盖）。
 *
 * V3 §L/§M 全矩阵：
 * - motionScaleFor：NORMAL 1f / QUIET 0.7f / REDUCED 0.5f（velocity/amplitude/parallax 联合缩放）；
 * - reducedMotionFor：仅 REDUCED 为 true（无障碍 Reduced Motion 编译期分支）；
 * - defaultQualityFor：7 个 surface 的默认渲染质量预算（正交于 MotionPolicy）。
 */
class MotionPolicyTest {

    @Test
    fun motionScaleForFullMatrix() {
        assertEquals(1f, motionScaleFor(MotionPolicy.NORMAL))
        assertEquals(0.7f, motionScaleFor(MotionPolicy.QUIET))
        assertEquals(0.5f, motionScaleFor(MotionPolicy.REDUCED))
    }

    @Test
    fun motionScaleOrderingQuietBetweenNormalAndReduced() {
        assertTrue(
            "缩放序必须 NORMAL > QUIET > REDUCED（降级只减动效）",
            motionScaleFor(MotionPolicy.NORMAL) > motionScaleFor(MotionPolicy.QUIET) &&
                motionScaleFor(MotionPolicy.QUIET) > motionScaleFor(MotionPolicy.REDUCED),
        )
    }

    @Test
    fun reducedMotionForOnlyReducedOptedIn() {
        assertTrue(reducedMotionFor(MotionPolicy.REDUCED))
        assertFalse(reducedMotionFor(MotionPolicy.NORMAL))
        assertFalse(reducedMotionFor(MotionPolicy.QUIET))
    }

    @Test
    fun defaultQualityForAllSurfaces() {
        assertEquals(EchoRenderQuality.NORMAL, defaultQualityFor(EchoSurface.APP_PRIVATE))
        assertEquals(EchoRenderQuality.NORMAL, defaultQualityFor(EchoSurface.APP_EVIDENCE))
        assertEquals(EchoRenderQuality.NORMAL, defaultQualityFor(EchoSurface.JOURNEY_PRIVATE))
        assertEquals(EchoRenderQuality.CONSERVE, defaultQualityFor(EchoSurface.WALLPAPER_VISUAL_ONLY))
        assertEquals(EchoRenderQuality.CONSERVE, defaultQualityFor(EchoSurface.LOCK_PUBLIC_SAFE))
        assertEquals(EchoRenderQuality.CONSERVE, defaultQualityFor(EchoSurface.DREAM_AMBIENT))
        assertEquals(EchoRenderQuality.MINIMAL, defaultQualityFor(EchoSurface.WRIST_PUBLIC_SAFE))
    }

    @Test
    fun defaultQualityNeverExceedsNormalNorBelowMinimal() {
        for (surface in EchoSurface.entries) {
            val quality = defaultQualityFor(surface)
            assertTrue(
                "${surface} 默认质量须落在 NORMAL..MINIMAL 内",
                quality.ordinal >= EchoRenderQuality.NORMAL.ordinal &&
                    quality.ordinal <= EchoRenderQuality.MINIMAL.ordinal,
            )
        }
    }
}
