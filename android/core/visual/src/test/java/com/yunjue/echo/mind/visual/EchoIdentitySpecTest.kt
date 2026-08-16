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
 * palette 感知色域（primary 218°..262° / secondary +22°..44° / warm 28°..40°）。
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
            assertTrue("primaryHue 218..262", id.palette.primary.h in 218f..262f)
            val secDelta = id.palette.secondary.h - id.palette.primary.h
            assertTrue("secondary +22..44", secDelta in 22f..44f)
            assertTrue("warmHue 28..40", id.palette.warm.h in 28f..40f)
        }
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
        assertEquals(
            ColorSpace.lch(0.66f, 0.105f, 240f),
            ColorSpace.lch(0.66f, 0.105f, 240f),
        )
        assertNotEquals(
            ColorSpace.lch(0.66f, 0.105f, 240f),
            ColorSpace.lch(0.66f, 0.105f, 34f),
        )
    }
}
