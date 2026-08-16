package com.yunjue.echo.mind.visual.render

import kotlin.math.abs

/** 颜色工具（纯函数）。视觉主色由 Identity Genome 决定，非状态决定 → 不构成情绪色彩联想。 */
object ColorSpace {

    /** hue(0..1)/sat/value → ARGB Int。 */
    fun hsv(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): Argb {
        val h = (hue % 1f + 1f) % 1f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs(h * 6f % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 1f / 6f -> Triple(c, x, 0f)
            h < 2f / 6f -> Triple(x, c, 0f)
            h < 3f / 6f -> Triple(0f, c, x)
            h < 4f / 6f -> Triple(0f, x, c)
            h < 5f / 6f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(alpha, r + m, g + m, b + m)
    }

    fun argb(alpha: Float, r: Float, g: Float, b: Float): Argb {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        val rr = (r.coerceIn(0f, 1f) * 255f).toInt()
        val gg = (g.coerceIn(0f, 1f) * 255f).toInt()
        val bb = (b.coerceIn(0f, 1f) * 255f).toInt()
        return a shl 24 or (rr shl 16) or (gg shl 8) or bb
    }

    /** 改 alpha。 */
    fun Argb.withAlpha(alpha: Float): Argb {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return this and 0x00FFFFFF or (a shl 24)
    }

    /**
     * CIELCh(D65) → ARGB（V3 §12：identity palette 的感知语义）。
     * l 归一化 0..1（×100 得 CIE L*）；c 彩度（0..~0.4 实用区间）；h 色相角度（度）。
     * 超色域分量裁剪到 0..1（软裁剪留给 tone pipeline，不在身份层做）。
     */
    fun lch(l: Float, c: Float, h: Float, alpha: Float = 1f): Argb {
        val hr = Math.toRadians((h % 360f + 360f) % 360f.toDouble())
        val labL = (l.coerceIn(0f, 1f) * 100f).toDouble()
        val a = c * kotlin.math.cos(hr)
        val b = c * kotlin.math.sin(hr)
        // Lab → XYZ（D65 白点）
        val fy = (labL + 16.0) / 116.0
        val fx = fy + a / 5.0
        val fz = fy - b / 2.0
        fun invF(t: Double): Double {
            val t3 = t * t * t
            return if (t3 > 0.008856) t3 else (t - 16.0 / 116.0) / 7.787
        }
        val x = invF(fx) * 0.95047
        val y = if (labL > 7.9996) fy * fy * fy else labL / 903.3
        val z = invF(fz) * 1.08883
        // XYZ → linear sRGB
        val rl = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val gl = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val bl = 0.0557 * x - 0.2040 * y + 1.0570 * z
        fun gamma(u: Double): Float {
            val v = if (u <= 0.0031308) 12.92 * u else 1.055 * Math.pow(u, 1.0 / 2.4) - 0.055
            return v.toFloat().coerceIn(0f, 1f)
        }
        return argb(alpha, gamma(rl), gamma(gl), gamma(bl))
    }

    /** deep navy / OLED black 底色（宪法 §二）。 */
    val BG_CENTER = hsv(0.62f, 0.55f, 0.07f)   // ~ #0A1230
    val BG_EDGE = hsv(0.66f, 0.7f, 0.02f)       // ~ #02040C

    /** 暖金高光（仅 allowWarmAccent 且 <5% 面积）。 */
    val WARM_GOLD = hsv(0.10f, 0.75f, 0.9f)
}
