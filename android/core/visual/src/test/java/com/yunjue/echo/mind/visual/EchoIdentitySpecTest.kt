package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.render.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3 §10–§13 — Identity 确定性/范围/多样性测试。
 *
 * 锁定：same seed 恒同结果；不同 seed 至少 3 个几何维度明显变化（不只换颜色）；
 * palette 感知色域（primary LCh 268°..282° / secondary LCh ≤336° / warm LCh 70°..82°）。
 */
class EchoIdentitySpecTest {

    @Test
    fun sameSeedAlwaysSameIdentity() {
        val a = EchoIdentitySpec.derive(42L)
        val b = EchoIdentitySpec.derive(42L)
        assertEquals(a, b)
    }

    @Test
    fun identityFieldsWithinSpecRanges() {
        for (seed in 1L..200L) {
            val id = EchoIdentitySpec.derive(seed)
            assertTrue("lobeCount 2..5", id.lobeCount in 2..5)
            assertTrue("chirality ±1", id.chirality == -1 || id.chirality == 1)
            assertTrue("coreRatio .29..43", id.coreRatio in .29f.. .43f)
            assertTrue("primaryTilt", id.primaryTilt in -.52f.. .52f)
            assertTrue("secondaryTilt", id.secondaryTilt in -.76f.. .76f)
            assertTrue("baseFrequency 2..5", id.baseFrequency in 2..5)
            assertTrue("orbitalBias", id.orbitalBias in -.12f.. .12f)
            assertTrue("membraneBias", id.membraneBias in .86f..1.14f)
            // Organism Quality §9：LCh hue 268..282（sRGB ~209-210° 深蓝，避免 H≥285° gamut clip）
            assertTrue("primaryHue LCh 268..282", id.palette.primary.h in 268f..282f)
            assertTrue("secondaryHue LCh <= 336 (sRGB ~300)", id.palette.secondary.h <= 336f)
            assertTrue("warmHue LCh 70..82 (sRGB ~28-40)", id.palette.warm.h in 70f..82f)
            // chroma 为 Lab 量纲（真实彩度；旧 0.12 近无彩——灰色线圈根因）。
            // Organism Visual Breakthrough §18：primary 上调至 52（GamutClip 收缩取最大
            // 可达饱和度——中亮度紫罗兰 c≈42 时 sRGB R≈G 灰化的修复）
            assertTrue("primary chroma real", id.palette.primary.c in 30f..56f)
        }
    }

    @Test
    fun primaryFamilyRendersAsSrgbBlueViolet() {
        // Organism Quality §9：最终 sRGB hue 主范围 225–275°（跨 seed 全族校验）
        for (seed in 1L..200L) {
            val id = EchoIdentitySpec.derive(seed)
            val argb = ColorSpace.lch(id.palette.primary.l, id.palette.primary.c, id.palette.primary.h)
            val r = (argb shr 16 and 0xFF) / 255f
            val g = (argb shr 8 and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            assertTrue("primary not achromatic (sat > .15)", mx > 0f && (mx - mn) / mx > 0.15f)
            assertTrue("primary blue dominant (b >= r, b >= g)", b >= r && b >= g)
            val hueDeg = rgbHue(r, g, b)
            assertTrue("primary sRGB hue 208..280 (got $hueDeg)", hueDeg in 208f..280f)
        }
    }

    private fun rgbHue(r: Float, g: Float, b: Float): Float {
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        if (mx == mn) return 0f
        val d = mx - mn
        val h = when (mx) {
            r -> (g - b) / d % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return (h * 60f % 360f + 360f) % 360f
    }

    @Test
    fun differentSeedsDifferInGeometryNotOnlyColor() {
        val ids = (1L..60L).map { EchoIdentitySpec.derive(it) }
        // 至少 3 个几何维度在种群中明显变化（§82 自动门）
        val lobeVariety = ids.map { it.lobeCount }.toSet().size
        val chiralityBoth = ids.map { it.chirality }.toSet().size
        val freqVariety = ids.map { it.baseFrequency }.toSet().size
        val coreRatioSpread = ids.map { it.coreRatio }.let { it.max() - it.min() }
        val tiltSpread = ids.map { it.primaryTilt }.let { it.max() - it.min() }
        assertTrue("lobe variety >= 3", lobeVariety >= 3)
        assertEquals("chirality both signs", 2, chiralityBoth)
        assertTrue("frequency family variety >= 3", freqVariety >= 3)
        assertTrue("coreRatio spread > .08", coreRatioSpread > .08f)
        assertTrue("primaryTilt spread > .5", tiltSpread > .5f)
    }

    @Test
    fun paletteIsPerceptualBluePurpleFamily() {
        val id = EchoIdentitySpec.derive(7L)
        // LCh → ARGB：主色族为蓝—紫（B 通道显著高于 R），暖色独立小面积使用
        val primary = ColorSpace.lch(id.palette.primary.l, id.palette.primary.c, id.palette.primary.h)
        val r = primary shr 16 and 0xFF
        val b = primary and 0xFF
        assertTrue("primary is blue-purple family (B > R)", b > r)
        val warm = ColorSpace.lch(id.palette.warm.l, id.palette.warm.c, id.palette.warm.h)
        val wr = warm shr 16 and 0xFF
        val wb = warm and 0xFF
        assertTrue("warm accent is warm family (R > B)", wr > wb)
    }

    @Test
    fun lchConversionIsDeterministic() {
        // chroma 用 Lab 量纲（真实彩度）；0.105 量级在 Lab 上近无彩，两 hue 会坍缩同色
        assertEquals(
            ColorSpace.lch(0.66f, 30f, 240f),
            ColorSpace.lch(0.66f, 30f, 240f),
        )
        assertNotEquals(
            ColorSpace.lch(0.66f, 30f, 240f),
            ColorSpace.lch(0.66f, 30f, 34f),
        )
    }
}
