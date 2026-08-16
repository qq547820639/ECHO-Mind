package com.yunjue.echo.mind.ui

/**
 * V3 §54 — Awakening 固定时间线（总时长 2200ms 不变）。
 *
 * 0–180ms     previous UI fade
 * 120–520ms   halo 0 → .55
 * 300–900ms   filament richness minimum → target
 * 520–1180ms  outer ring alpha 0 → 1
 * 850–1550ms  first breath .985 → 1.018 → 1.000
 * 1350–1650ms headline fade
 * 1650–2200ms hold
 * 2200ms      进入 Home
 *
 * 纯函数（单测锁定关键帧）；渲染端把返回值映射进 EchoRenderOptions。
 */
object AwakeningTimeline {

    const val TOTAL_MS = 2200L

    data class Frame(
        /** previous UI 淡出 alpha（1 → 0）。 */
        val previousUiAlpha: Float,
        /** halo 比例 0 → .55（映射为 haloScale 0..1）。 */
        val haloScale: Float,
        /** filament 丰富度（detailScale 语义：minimum → target）。 */
        val detailScale: Float,
        /** outer ring alpha 0 → 1。 */
        val ringAlphaScale: Float,
        /** first breath 缩放（.985 → 1.018 → 1.000）。 */
        val breathScale: Float,
        /** headline alpha 0 → 1。 */
        val headlineAlpha: Float,
        /** 是否到点进入 Home。 */
        val finished: Boolean,
    )

    fun at(tMs: Long): Frame {
        val t = tMs.coerceIn(0L, TOTAL_MS)
        return Frame(
            previousUiAlpha = 1f - segment(t, 0, 180),
            haloScale = segment(t, 120, 520) * 1.0f, // halo 强度上限由 renderer 控制（.55 语义在 halo 参数）
            detailScale = lerp(0.30f, 1.0f, segment(t, 300, 900)),
            ringAlphaScale = segment(t, 520, 1180),
            breathScale = breath(t),
            headlineAlpha = segment(t, 1350, 1650),
            finished = tMs >= TOTAL_MS,
        )
    }

    /** first breath：850–1550ms，.985 → 1.018 → 1.000（之前/之后恒 1.000）。 */
    private fun breath(t: Long): Float = when {
        t < 850 -> 1.000f
        t < 1150 -> lerp(0.985f, 1.018f, (t - 850) / 300f)
        t < 1550 -> lerp(1.018f, 1.000f, (t - 1150) / 400f)
        else -> 1.000f
    }

    private fun segment(t: Long, start: Long, end: Long): Float =
        ((t - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun lerp(a: Float, b: Float, p: Float): Float = a + (b - a) * p.coerceIn(0f, 1f)
}
