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

    /** deep navy / OLED black 底色（宪法 §二）。 */
    val BG_CENTER = hsv(0.62f, 0.55f, 0.07f)   // ~ #0A1230
    val BG_EDGE = hsv(0.66f, 0.7f, 0.02f)       // ~ #02040C

    /** 暖金高光（仅 allowWarmAccent 且 <5% 面积）。 */
    val WARM_GOLD = hsv(0.10f, 0.75f, 0.9f)
}
