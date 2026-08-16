package com.yunjue.echo.mind.presence

/**
 * WallpaperScheduler — V3 §69/§71 帧率调度（纯函数，JVM 可测）。
 *
 * §69 优先级（30fps 是 cap；不可见必须真正 0 连续绘制）：
 *   !visible → 0fps（null = 不调度）
 *   reducedMotion → 8
 *   thermal >= SEVERE → 8
 *   powerSave → 10
 *   recentTouch < 3s → 30
 *   presenceUpdate < 4s → 30
 *   night → 12
 *   else → 18
 *
 * §71 Dream：entry first 3s = 24fps；steady = 15；reduced = 8。
 */
object WallpaperScheduler {

    const val MAX_FPS = 30

    /** @return 帧间隔 ms；null = 0fps（不可见/销毁——取消一切 callback/runnable/帧循环）。 */
    fun wallpaperFrameDelayMs(
        visible: Boolean,
        reducedMotion: Boolean,
        thermalSevereOrWorse: Boolean,
        powerSave: Boolean,
        msSinceTouch: Long,
        msSincePresenceUpdate: Long,
        night: Boolean,
    ): Long? {
        if (!visible) return null
        val fps = when {
            reducedMotion -> 8
            thermalSevereOrWorse -> 8
            powerSave -> 10
            msSinceTouch < 3_000L -> 30
            msSincePresenceUpdate < 4_000L -> 30
            night -> 12
            else -> 18
        }.coerceAtMost(MAX_FPS)
        return 1000L / fps
    }

    /** §71 Dream 帧间隔（entry 3s 内 24fps / steady 15 / reduced 8）。 */
    fun dreamFrameDelayMs(elapsedSinceStartMs: Long, reducedMotion: Boolean): Long {
        val fps = when {
            reducedMotion -> 8
            elapsedSinceStartMs < 3_000L -> 24
            else -> 15
        }
        return 1000L / fps
    }

    /** §72 burn-in：每分钟 deterministic offset（x [-3,+3]dp / y [-2,+2]dp；不跳变）。 */
    fun burnInOffsetDp(minuteOfDay: Int): Pair<Float, Float> {
        // 确定性：同一分钟同一 offset；相邻分钟缓慢变化（正弦族，无随机跳变）
        val x = kotlin.math.sin(minuteOfDay * 0.714f) * 3f
        val y = kotlin.math.sin(minuteOfDay * 1.117f + 1.3f) * 2f
        return x to y
    }
}
