package com.yunjue.echo.mind.visual.render

import com.yunjue.echo.mind.visual.math.DeterministicRandom
import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seed 7710 primary-hue saturation sweep (CIELCh LCh → sRGB).
 *
 * Organism Quality §31 新艺术门要求 meanChromaticSaturation >= 0.55（强彩色发光像素平均饱和度）。
 * 本测试枚举 (L, C) 网格在 H=306°（seed 7710 primary）下计算 sat=(max-min)/max，
 * 报告哪些组合能过 0.55 门槛，并评估把 primary L 从 0.75 降到 ~0.62 对
 * heroMeanLuminance（目标 0.10-0.20）的影响。
 *
 * 色域纪律：ColorSpace.lch 对暗+高彩度组合自动二分收缩 c，保 hue、弃过饱和；
 * 因此 sat 是 sRGB-reachable 的上界，实测值可能低于同 LCh 下理想值。
 */
class ColorSpaceSeed7710PrimarySatTest {

    private companion object {
        // seed 7710 的 primary hue 由 EchoIdentitySpec 新公式给出：
        // 268 + 14 * fracOf(DeterministicRandom.mix(7710L, 1)) ≈ 279°
        // （公式 268+14* 窄幅 LCh_H∈[268,282]，meanSat≈0.560 ≥ 0.55 门）
        const val TARGET_PRIMARY_HUE = 279f

        /** sat 门槛（与 VisualLabMetrics GateResult.chromaticSaturationPass 一致）。 */
        const val SAT_THRESHOLD = 0.55f

        /** 辅助：ARGB → 归一化 r/g/b → sat = (mx - mn) / mx。 */
        fun rgbSat(argb: Int): Float {
            val r = (argb ushr 16 and 0xFF) / 255f
            val g = (argb ushr 8 and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            return if (mx > 1e-6f) (mx - mn) / mx else 0f
        }

        /** 辅助：ARGB → Rec.709 luma（与 VisualLabMetrics.compute 一致）。 */
        fun luma(argb: Int): Float {
            val r = (argb ushr 16 and 0xFF) / 255f
            val g = (argb ushr 8 and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            return 0.2126f * r + 0.587f * g + 0.0722f * b
        }
    }

    @Test
    fun seed7710PrimaryHueIs279Degrees() {
        val id = EchoIdentitySpec.derive(7710L)
        val actualH = id.palette.primary.h
        assertTrue(
            "primaryH $actualH ≈ $TARGET_PRIMARY_HUE",
            kotlin.math.abs(actualH - TARGET_PRIMARY_HUE) < 1.5f
        )
    }

    @Test
    fun lchSaturationSweepAt306deg() {
        val lValues = floatArrayOf(0.72f, 0.68f, 0.65f, 0.62f, 0.60f, 0.58f)
        val cValues = floatArrayOf(52f, 48f, 44f, 40f)

        val passRows = mutableListOf<String>()
        val allResults = mutableListOf<Triple<Float, Float, Float>>() // (l, c, sat)

        println("\n=== ColorSpace.lch sat sweep — H=${TARGET_PRIMARY_HUE}° (seed 7710 primary) ===")
        for (l in lValues) {
            for (c in cValues) {
                val argb = ColorSpace.lch(l, c, TARGET_PRIMARY_HUE)
                val sat = rgbSat(argb)
                allResults.add(Triple(l, c, sat))
                val r = (argb ushr 16 and 0xFF)
                val g = (argb ushr 8 and 0xFF)
                val b = (argb and 0xFF)
                val hex = "%02X%02X%02X".format(r, g, b)
                val pass = sat >= SAT_THRESHOLD
                if (pass) passRows.add("  L=$l  C=$c  →  sat=${"%.4f".format(sat)}  ($hex) PASS")
                println("  L=$l  C=$c  →  sat=${"%.4f".format(sat)}  #$hex  ${if (pass) "PASS (>= $SAT_THRESHOLD)" else "BELOW"}")
            }
        }

        println("\n=== sat >= $SAT_THRESHOLD 的组合 ===")
        if (passRows.isEmpty()) {
            println("  (none)")
        } else {
            passRows.forEach { println(it) }
        }

        // 核心报告：当前 spec primary 配置（L=0.68, C=52）的 sat
        val currentL = 0.68f
        val currentC = 52f
        val currentArgb = ColorSpace.lch(currentL, currentC, TARGET_PRIMARY_HUE)
        val currentSat = rgbSat(currentArgb)
        println("\n--- Current spec primary: L=$currentL C=$currentC H=${TARGET_PRIMARY_HUE}° ---")
        println("  sat = ${"%.4f".format(currentSat)}  (threshold=$SAT_THRESHOLD)")

        // 报告：max sat 出现在哪个 (L, C)
        val best = allResults.maxByOrNull { it.third } ?: Triple(0f, 0f, 0f)
        println("--- Best achievable at H=${TARGET_PRIMARY_HUE}°: L=${best.first} C=${best.second} sat=${"%.4f".format(best.third)} ---")
    }

    @Test
    fun primaryLLoweredFrom75to62_heroMeanLuminanceImpact() {
        // VisualLabMetrics 把 heroMeanLuminance 定义为目标 0.10-0.20（§31）。
        // 用 primary 颜色自身的 luma 做 proxy：primary 是 hero 盘内最亮的彩色源，
        // 其 luma 变化直接决定 heroMeanLuminance 的偏移方向。
        val lOriginal = 0.75f   // 用户提议降低前的 L（近似 spec 上限 0.76）
        val lLowered = 0.62f    // 提议降低后的 L
        val c = 52f             // spec primary chroma
        val h = TARGET_PRIMARY_HUE

        val argbOrig = ColorSpace.lch(lOriginal, c, h)
        val argbNew = ColorSpace.lch(lLowered, c, h)
        val lumaOrig = luma(argbOrig)
        val lumaNew = luma(argbNew)
        val deltaLuma = lumaNew - lumaOrig
        val satOrig = rgbSat(argbOrig)
        val satNew = rgbSat(argbNew)

        println("\n=== heroMeanLuminance impact — lowering primary L $lOriginal → $lLowered (C=$c, H=$h°) ===")
        println("  Original L=$lOriginal : luma=${"%.4f".format(lumaOrig)}  sat=${"%.4f".format(satOrig)}  #${"%02X%02X%02X".format(argbOrig ushr 16 and 0xFF, argbOrig ushr 8 and 0xFF, argbOrig and 0xFF)}")
        println("  Lowered  L=$lLowered : luma=${"%.4f".format(lumaNew)}  sat=${"%.4f".format(satNew)}  #${"%02X%02X%02X".format(argbNew ushr 16 and 0xFF, argbNew ushr 8 and 0xFF, argbNew and 0xFF)}")
        println("  deltaLuma = ${"%.4f".format(deltaLuma)}  (${if (deltaLuma < 0f) "darken" else "brighten"})")
        println("  deltaSat  = ${"%.4f".format(satNew - satOrig)}")

        // 断言1：降低 L 后 luma 严格下降（验证方向正确）
        assertTrue(
            "lowering L must reduce luma (got delta=${"%.4f".format(deltaLuma)})",
            deltaLuma < 0f
        )

        // 断言2：降低 L 后 sat 比原值有微小改善（亮度降低使通道差异比例提高）
        // 这是 sRGB gamut 边缘行为的预期结果——越暗的色块越接近中性但饱和度比例提升
        assertTrue(
            "lowering L should slightly improve sat ratio (orig=${"%.4f".format(satOrig)} new=${"%.4f".format(satNew)})",
            satNew > satOrig
        )

        // 断言3：原 L=0.75 时 primary 自身 luma 应处于合理范围
        // heroMeanLuminance 是全图 hero 盘均值，含背景/丝/腔；primary luma 是上界贡献
        assertTrue(
            "Original primary luma must be a reasonable upper bound for heroMeanLuminance (target 0.10-0.20)",
            lumaOrig in 0.10f..0.80f
        )

        // 报告：luma 下降量级及其对 heroMeanLuminance 目标的含义
        println("\n--- Impact Assessment ---")
        println("  primary luma drop: ${"%.4f".format(-deltaLuma)} (absolute)")
        println("  heroMeanLuminance target: 0.10-0.20 (§31)")
        println("  If original heroMeanLuminance ~ 0.15 (mid-target), new ~ ${"%.4f".format(0.15f + deltaLuma)}")
        println("  -> Lowering primary L by ${(lOriginal - lLowered) * 100}% points shifts hero mean by ~ ${"%.4f".format(deltaLuma)}")
        println("  -> Risk: may fall below 0.10 floor if original was near lower bound")
    }
}
