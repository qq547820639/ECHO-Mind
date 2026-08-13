package com.yunjue.echo.mind

import com.yunjue.echo.mind.sensing.AppActivityCollector
import com.yunjue.echo.mind.sensing.FeatureExtractor
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Phase 4.3 跨窗口 carry-over 状态测试（纯 JVM，不依赖 Android 框架）。
 *
 * 直接构造 [ScreenCollector.ScreenStateCarry] / [AppActivityCollector.AppForegroundState]
 * 传给 [FeatureExtractor.extract]（新参数），验证：
 * - Screen：窗口开始前已 ON（screenOnSinceMs < windowStart）→ screen_on_duration_ms 从 windowStart 起算；
 * - Screen：窗口内 OFF 事件 → duration 截断到 OFF 时刻；
 * - App：foregroundSinceMs 早于 windowStart → top_app_duration_ms 从 windowStart 起算；
 * - 无 carry 时旧逻辑被新逻辑替代（foregroundSince 在窗口外/未来 → 0）。
 *
 * vector 布局（FeatureExtractor.buildVector）：8 accel + 6 gyro + 3 screen
 * （on_count=14, off_count=15, on_duration_ms=16）+ 3 notif（17..19）+ 2 app
 * （switch_count=20, top_app_duration_ms=21）。
 *
 * 说明：传感器类型使用局部常量（1 = android.hardware.Sensor.TYPE_ACCELEROMETER），
 * 纯 JVM 测试避免引用 android.jar 类。
 */
class WindowCarryStateTest {

    private val extractor = FeatureExtractor()

    // android.hardware.Sensor.TYPE_ACCELEROMETER = 1（编译期常量，纯 JVM 替代）
    private val typeAccelerometer = 1

    private val windowStart = Instant.parse("2026-08-01T12:00:00Z")
    private val windowEnd = windowStart.plusMillis(300_000L)
    private val windowStartMs = windowStart.toEpochMilli()
    private val windowEndMs = windowEnd.toEpochMilli()

    /** 单条窗口内 accel 样本（保证窗口有信号可产出特征；不干扰 carry 断言）。 */
    private fun inWindowAccel(): SensorSample =
        SensorSample(windowStartMs + 1_000L, typeAccelerometer, 0f, 0f, 9.8f)

    /** 屏幕事件（窗口内）。 */
    private fun screenEvent(offsetMs: Long, state: ScreenCollector.ScreenState): ScreenCollector.ScreenEvent =
        ScreenCollector.ScreenEvent(windowStartMs + offsetMs, state)

    // ===== Screen carry-over =====

    @Test
    fun screenOnBeforeWindowStartCountsFromWindowStart() {
        // 屏幕在窗口开始前 60s 已 ON（carry），窗口内无任何事件 → duration = 整窗 300s
        val carry = ScreenCollector.ScreenStateCarry(windowStartMs - 60_000L)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = windowEnd,
            accelSamples = listOf(inWindowAccel()),
            screenCarryState = carry
        )
        assertEquals(1, features.size)
        // vector[16] = screen on_duration_ms
        assertEquals(
            "carry ON 应从 windowStart 起算整窗时长",
            300_000f,
            features.first().vector[16],
            1e-4f
        )
    }

    @Test
    fun screenOffInsideWindowTruncatesCarryDuration() {
        // 屏幕在窗口开始前 60s 已 ON（carry），窗口内 120s 处 OFF → duration 截断到 OFF 时刻
        val carry = ScreenCollector.ScreenStateCarry(windowStartMs - 60_000L)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = windowEnd,
            accelSamples = listOf(inWindowAccel()),
            screenEvents = listOf(screenEvent(120_000L, ScreenCollector.ScreenState.OFF)),
            screenCarryState = carry
        )
        assertEquals(1, features.size)
        assertEquals(
            "carry 段应到 OFF 事件时刻（120s）",
            120_000f,
            features.first().vector[16],
            1e-4f
        )
    }

    @Test
    fun screenOnThenOffInsideWindowComputesPairDuration() {
        // 无 carry：窗口内 ON(60s) → OFF(120s) → duration = 60s（旧逻辑不受 carry 影响）
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = windowEnd,
            accelSamples = listOf(inWindowAccel()),
            screenEvents = listOf(
                screenEvent(60_000L, ScreenCollector.ScreenState.ON),
                screenEvent(120_000L, ScreenCollector.ScreenState.OFF)
            )
        )
        assertEquals(1, features.size)
        assertEquals(60_000f, features.first().vector[16], 1e-4f)
    }

    @Test
    fun noCarryAndNoScreenEventsYieldsZeroDuration() {
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = windowEnd,
            accelSamples = listOf(inWindowAccel())
        )
        assertEquals(1, features.size)
        assertEquals(0f, features.first().vector[16], 1e-4f)
    }

    // ===== App foreground carry-over =====

    @Test
    fun appForegroundBeforeWindowStartCountsFromWindowStart() {
        // 当前前台 App 从窗口开始前 120s 起保持前台 → duration = 整窗 300s
        val foreground = AppActivityCollector.AppForegroundState("com.test", windowStartMs - 120_000L)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = windowEnd,
            accelSamples = listOf(inWindowAccel()),
            appForeground = foreground
        )
        assertEquals(1, features.size)
        // vector[21] = top_app_duration_ms
        assertEquals(
            "foregroundSince 早于 windowStart → duration 从 windowStart 起算整窗",
            300_000f,
            features.first().vector[21],
            1e-4f
        )
    }

    @Test
    fun appForegroundStartedInsideWindowCountsFromForegroundSince() {
        val foreground = AppActivityCollector.AppForegroundState("com.test", windowStartMs + 60_000L)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = windowEnd,
            accelSamples = listOf(inWindowAccel()),
            appForeground = foreground
        )
        assertEquals(1, features.size)
        assertEquals(
            "foregroundSince 在窗口内 → duration = windowEnd - foregroundSince",
            240_000f,
            features.first().vector[21],
            1e-4f
        )
    }

    @Test
    fun noForegroundAndNoAppEventsYieldsZeroDuration() {
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = windowEnd,
            accelSamples = listOf(inWindowAccel())
        )
        assertEquals(1, features.size)
        assertEquals(0f, features.first().vector[21], 1e-4f)
    }

    @Test
    fun foregroundSinceAtOrAfterWindowEndYieldsZeroDuration() {
        // 新逻辑：foregroundSince >= windowEnd → 该 App 不在本窗口前台 → duration 0
        val foreground = AppActivityCollector.AppForegroundState("com.test", windowEndMs + 1_000L)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = windowEnd,
            accelSamples = listOf(inWindowAccel()),
            appForeground = foreground
        )
        assertEquals(1, features.size)
        assertEquals(0f, features.first().vector[21], 1e-4f)
    }

    @Test
    fun windowCarryStateIsDataClassWithExposedFields() {
        // 编译期契约：carry 状态可独立构造并传给 extract（新参数路径）
        val screenCarry = ScreenCollector.ScreenStateCarry(windowStartMs - 1L)
        val appForeground = AppActivityCollector.AppForegroundState("com.test", windowStartMs - 1L)
        assertTrue("carry screenOnSinceMs 应可读", screenCarry.screenOnSinceMs < windowStartMs)
        assertTrue("foreground packageName 应可读", appForeground.packageName == "com.test")
        assertTrue("foreground foregroundSinceMs 应可读", appForeground.foregroundSinceMs < windowStartMs)
    }
}
