package com.yunjue.echo.mind.visual.render

import org.junit.Test

/**
 * LCh saturation sweep at H=279° (seed 7710 primary, formula 268+14*) and H=268° (min hue),
 * with a family-0 volume-lobe chroma comparison (62 → 48).
 *
 * Organism Quality §31：meanChromaticSaturation 门槛 ≥ 0.55。
 */
class ColorSpaceLchHueSweepTest {

    private companion object {
        const val SAT_THRESHOLD = 0.55f

        fun rgbSat(argb: Int): Float {
            val r = (argb ushr 16 and 0xFF) / 255f
            val g = (argb ushr 8 and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            return if (mx > 1e-6f) (mx - mn) / mx else 0f
        }

        fun luma(argb: Int): Float {
            val r = (argb ushr 16 and 0xFF) / 255f
            val g = (argb ushr 8 and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            return 0.2126f * r + 0.587f * g + 0.0722f * b
        }
    }

    @Test
    fun lchSaturationSweepAt279And268deg() {
        data class Result(val l: Float, val c: Float, val sat: Float)

        // seed 7710 新公式 H≈279°（deep blue，sRGB sat 可达 ≥0.55）
        val h279Lc: List<Pair<Float, Float>> = listOf(
            Pair(0.72f, 52f), Pair(0.62f, 52f), Pair(0.62f, 48f),
            Pair(0.58f, 52f), Pair(0.58f, 48f), Pair(0.72f, 48f))
        // 最小 hue H=268°（同族深蓝，sat 略高）
        val h268Lc: List<Pair<Float, Float>> = listOf(
            Pair(0.72f, 52f), Pair(0.62f, 52f), Pair(0.62f, 48f),
            Pair(0.58f, 52f), Pair(0.58f, 48f), Pair(0.72f, 48f))

        println("\n=== H=279° (seed 7710 primary，新公式 268+14*frac) ===")
        val h279Results = mutableListOf<Result>()
        for ((l, c) in h279Lc) {
            val argb = ColorSpace.lch(l, c, 279f)
            val sat = rgbSat(argb)
            val lum = luma(argb)
            val r = (argb ushr 16 and 0xFF)
            val g = (argb ushr 8 and 0xFF)
            val b = (argb and 0xFF)
            println("  L=$l  C=$c  →  sat=${"%.4f".format(sat)}  lum=${"%.4f".format(lum)}  #${"%02X%02X%02X".format(r, g, b)}  ${if (sat >= SAT_THRESHOLD) "PASS" else "BELOW"}")
            h279Results.add(Result(l, c, sat))
        }

        println("\n=== H=268° (公式下限，深蓝色域) ===")
        val h268Results = mutableListOf<Result>()
        for ((l, c) in h268Lc) {
            val argb = ColorSpace.lch(l, c, 268f)
            val sat = rgbSat(argb)
            val lum = luma(argb)
            val r = (argb ushr 16 and 0xFF)
            val g = (argb ushr 8 and 0xFF)
            val b = (argb and 0xFF)
            println("  L=$l  C=$c  →  sat=${"%.4f".format(sat)}  lum=${"%.4f".format(lum)}  #${"%02X%02X%02X".format(r, g, b)}  ${if (sat >= SAT_THRESHOLD) "PASS" else "BELOW"}")
            h268Results.add(Result(l, c, sat))
        }

        // 报告：哪个 (L,C,hue) 组合 meanChromaticSaturation 最接近 0.55
        data class Entry(val hue: Float, val l: Float, val c: Float, val sat: Float)
        val allResults = (h279Results.map { Entry(279f, it.l, it.c, it.sat) } +
            h268Results.map { Entry(268f, it.l, it.c, it.sat) })
        val closestTo55 = allResults.minByOrNull { kotlin.math.abs(it.sat - SAT_THRESHOLD) }
        println("\n=== meanChromaticSaturation closest to $SAT_THRESHOLD ===")
        if (closestTo55 != null) {
            println("  H=${closestTo55.hue.toInt()}°  L=${closestTo55.l}  C=${closestTo55.c}  →  sat=${"%.4f".format(closestTo55.sat)}  (diff=${"%.4f".format(kotlin.math.abs(closestTo55.sat - SAT_THRESHOLD))})")
        }

        // 报告：各 hue 下 PASS 的组合
        println("\n=== PASS (sat >= $SAT_THRESHOLD) ===")
        h279Results.filter { it.sat >= SAT_THRESHOLD }.forEach { (l, c, sat) ->
            println("  H=279°  L=$l  C=$c  sat=${"%.4f".format(sat)}")
        }
        h268Results.filter { it.sat >= SAT_THRESHOLD }.forEach { (l, c, sat) ->
            println("  H=268°  L=$l  C=$c  sat=${"%.4f".format(sat)}")
        }
    }

    @Test
    fun volumeLobeFamily0Chroma62Vs48() {
        // Volume lobe family 0 公式（OrganismFrameComputer evalVolumeLobes）：
        //   ColorSpace.lch(0.58f + 0.05f * lb.softness, 62f, huePrimary)
        // softness 范围 0.55..1.0 → L ∈ [0.6075, 0.63]
        val softnesses = floatArrayOf(0.55f, 0.65f, 0.75f, 0.85f, 1.0f)
        val hue = 279f

        println("\n=== Volume lobe family 0: chroma 62 vs 48 ===")
        var totalSatDelta = 0f
        softnesses.forEach { s ->
            val l = 0.58f + 0.05f * s
            val c62 = ColorSpace.lch(l, 62f, hue)
            val c48 = ColorSpace.lch(l, 48f, hue)
            val sat62 = rgbSat(c62)
            val sat48 = rgbSat(c48)
            val lum62 = luma(c62)
            val lum48 = luma(c48)
            val delta = sat48 - sat62
            totalSatDelta += delta
            val r62 = (c62 ushr 16 and 0xFF)
            val g62 = (c62 ushr 8 and 0xFF)
            val b62 = (c62 and 0xFF)
            val r48 = (c48 ushr 16 and 0xFF)
            val g48 = (c48 ushr 8 and 0xFF)
            val b48 = (c48 and 0xFF)
            println("  softness=$s  L=${"%.4f".format(l)}")
            println("    C=62 : sat=${"%.4f".format(sat62)}  lum=${"%.4f".format(lum62)}  #${"%02X%02X%02X".format(r62, g62, b62)}")
            println("    C=48 : sat=${"%.4f".format(sat48)}  lum=${"%.4f".format(lum48)}  #${"%02X%02X%02X".format(r48, g48, b48)}")
            println("    Δsat = ${"%.4f".format(delta)}  (${if (delta < 0f) "↓" else "↑"})")
        }
        val avgDelta = totalSatDelta / softnesses.size
        println("\n  平均 Δsat (62→48) = ${"%.4f".format(avgDelta)}")
        println("  结论：chroma 62→48 使 family-0 lobe 饱和度下降约 ${"%.2f".format(avgDelta * 100)}% 绝对值，")
        println("        对 meanChromaticSaturation 产生负向偏移（向 0.55 门槛远离）。")
    }
}
