package com.yunjue.echo.mind

import android.hardware.Sensor
import com.yunjue.echo.mind.model.DerivedFeatureInput
import com.yunjue.echo.mind.sensing.AppActivityCollector
import com.yunjue.echo.mind.sensing.FeatureExtractor
import com.yunjue.echo.mind.sensing.NotificationCollector
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

/**
 * T04.7 特征提取器单测：
 * - 窗口聚合产出 DerivedFeatureInput
 * - summary 长度限制（≤4000 字）
 * - vector 维度限制（≤256 维）
 * - source 选择逻辑
 * - 空窗口不产出
 * - 窗口外事件被过滤
 *
 * Phase 4.1（Sensor Timestamp）：accel/gyro 样本为 [SensorSample]（带 timestampMs），
 * 本测试构造的样本时间戳必须落在 [windowStart, windowEnd) 内，否则会被精确窗口过滤丢弃。
 */
class FeatureExtractorTest {

    private val extractor = FeatureExtractor()

    @Test
    fun emptyWindowProducesNoFeature() {
        val now = Instant.now()
        val features = extractor.extract(
            windowStart = now.minusSeconds(300),
            windowEnd = now,
            accelSamples = emptyList(),
            gyroSamples = emptyList(),
            screenEvents = emptyList(),
            notifications = emptyList(),
            appActivities = emptyList()
        )
        assertTrue("空窗口不应产出特征", features.isEmpty())
    }

    @Test
    fun windowAggregationProducesFeature() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        // 模拟加速度数据（3 轴，带时间戳，落在窗口内）
        val accelSamples = listOf(
            SensorSample(windowStart.toEpochMilli() + 1_000L, Sensor.TYPE_ACCELEROMETER, 0.1f, 0.2f, 9.8f),
            SensorSample(windowStart.toEpochMilli() + 2_000L, Sensor.TYPE_ACCELEROMETER, 0.15f, 0.25f, 9.7f),
            SensorSample(windowStart.toEpochMilli() + 3_000L, Sensor.TYPE_ACCELEROMETER, 0.12f, 0.22f, 9.9f)
        )
        // 模拟屏幕事件（窗口内）
        val screenEvents = listOf(
            ScreenCollector.ScreenEvent(now.minusSeconds(120).toEpochMilli(), ScreenCollector.ScreenState.ON),
            ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.OFF)
        )
        // 模拟通知（窗口内）
        val notifications = listOf(
            NotificationCollector.NotificationMeta(now.minusSeconds(100).toEpochMilli(), "com.test.app", "social"),
            NotificationCollector.NotificationMeta(now.minusSeconds(50).toEpochMilli(), "com.test.app", "social"),
            NotificationCollector.NotificationMeta(now.minusSeconds(30).toEpochMilli(), "com.other.app", "promo")
        )

        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            accelSamples = accelSamples,
            screenEvents = screenEvents,
            notifications = notifications
        )

        assertEquals("应产出一个特征", 1, features.size)
        val feature = features.first()
        // Phase 4/5：schemaVersion 由 FeatureExtractor 产出为 "passive-core-v1"（schema registry）
        assertEquals("passive-core-v1", feature.schemaVersion)
        assertEquals("accel", feature.source)
        assertEquals(windowStart, feature.windowStart)
        assertEquals(now, feature.windowEnd)
        assertTrue("summary 不应为空", feature.summary.isNotEmpty())
        assertTrue("vector 不应为空", feature.vector.isNotEmpty())
    }

    @Test
    fun summaryDoesNotExceed4000Chars() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        // 构造大量信号触发长摘要（时间戳递增，均落在窗口内）
        val accelSamples = (1..500).map {
            SensorSample(windowStart.toEpochMilli() + it * 100L, Sensor.TYPE_ACCELEROMETER, it.toFloat() * 0.01f, 0f, 9.8f)
        }
        val screenEvents = (1..100).map {
            ScreenCollector.ScreenEvent(now.minusSeconds((300 - it * 2).toLong()).toEpochMilli(), ScreenCollector.ScreenState.ON)
        }
        val notifications = (1..200).map {
            NotificationCollector.NotificationMeta(now.minusSeconds((300 - it).toLong()).toEpochMilli(), "pkg$it", "social")
        }

        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            accelSamples = accelSamples,
            screenEvents = screenEvents,
            notifications = notifications
        )

        assertEquals(1, features.size)
        assertTrue(
            "summary 长度 ${features.first().summary.length} 应 ≤4000",
            features.first().summary.length <= FeatureExtractor.MAX_SUMMARY_LENGTH
        )
    }

    @Test
    fun vectorDoesNotExceed256Dimensions() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        val accelSamples = listOf(
            SensorSample(windowStart.toEpochMilli() + 1_000L, Sensor.TYPE_ACCELEROMETER, 0.1f, 0.2f, 9.8f)
        )
        val screenEvents = listOf(
            ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.ON)
        )

        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            accelSamples = accelSamples,
            screenEvents = screenEvents
        )

        assertEquals(1, features.size)
        assertTrue(
            "vector 维度 ${features.first().vector.size} 应 ≤256",
            features.first().vector.size <= FeatureExtractor.MAX_VECTOR_DIM
        )
    }

    @Test
    fun sourceSelectionPrioritizesAccel() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            accelSamples = listOf(SensorSample(windowStart.toEpochMilli() + 1_000L, Sensor.TYPE_ACCELEROMETER, 0f, 0f, 9.8f)),
            gyroSamples = listOf(SensorSample(windowStart.toEpochMilli() + 1_000L, Sensor.TYPE_GYROSCOPE, 0f, 0f, 0f)),
            screenEvents = listOf(ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.ON)),
            notifications = listOf(NotificationCollector.NotificationMeta(now.minusSeconds(30).toEpochMilli(), "pkg", "social")),
            appActivities = listOf(AppActivityCollector.AppActivity(now.minusSeconds(10).toEpochMilli(), "com.test"))
        )
        assertEquals("有加速度数据时 source 应为 accel", "accel", features.first().source)
    }

    @Test
    fun sourceSelectionFallsBackToScreen() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            screenEvents = listOf(ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.ON))
        )
        assertEquals("无加速度/陀螺仪数据时 source 应为 screen", "screen", features.first().source)
    }

    @Test
    fun sourceSelectionFallsBackToNotification() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            notifications = listOf(NotificationCollector.NotificationMeta(now.minusSeconds(30).toEpochMilli(), "pkg", "social"))
        )
        assertEquals("仅有通知时 source 应为 notification", "notification", features.first().source)
    }

    @Test
    fun sourceSelectionFallsBackToAppActivity() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            appActivities = listOf(AppActivityCollector.AppActivity(now.minusSeconds(10).toEpochMilli(), "com.test"))
        )
        assertEquals("仅有 App 活跃时 source 应为 app_activity", "app_activity", features.first().source)
    }

    @Test
    fun eventsOutsideWindowAreFiltered() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        // 窗口外的屏幕事件应被过滤
        val screenEventsOutside = listOf(
            ScreenCollector.ScreenEvent(now.minusSeconds(600).toEpochMilli(), ScreenCollector.ScreenState.ON),
            ScreenCollector.ScreenEvent(now.minusSeconds(500).toEpochMilli(), ScreenCollector.ScreenState.OFF)
        )
        // 窗口内的屏幕事件
        val screenEventsInside = listOf(
            ScreenCollector.ScreenEvent(now.minusSeconds(100).toEpochMilli(), ScreenCollector.ScreenState.ON)
        )

        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            screenEvents = screenEventsOutside + screenEventsInside
        )

        assertEquals(1, features.size)
        // summary 应只包含 1 次屏幕开启（窗口内），不含窗口外事件
        assertTrue("summary 应包含屏幕开启1次", features.first().summary.contains("屏幕开启1次"))
        assertFalse("summary 不应包含窗口外事件", features.first().summary.contains("屏幕开启2次"))
    }

    @Test
    fun vectorContainsExpectedDimensions() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            accelSamples = listOf(
                SensorSample(windowStart.toEpochMilli() + 1_000L, Sensor.TYPE_ACCELEROMETER, 0.1f, 0.2f, 9.8f),
                SensorSample(windowStart.toEpochMilli() + 2_000L, Sensor.TYPE_ACCELEROMETER, 0.2f, 0.3f, 9.7f)
            ),
            gyroSamples = listOf(SensorSample(windowStart.toEpochMilli() + 1_000L, Sensor.TYPE_GYROSCOPE, 0.01f, 0.02f, 0.03f)),
            screenEvents = listOf(ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.ON)),
            notifications = listOf(NotificationCollector.NotificationMeta(now.minusSeconds(30).toEpochMilli(), "pkg", "social")),
            appActivities = listOf(AppActivityCollector.AppActivity(now.minusSeconds(10).toEpochMilli(), "com.test"))
        )
        // 8 (accel) + 6 (gyro) + 3 (screen) + 3 (notif) + 2 (app) = 22 维
        assertEquals("vector 应为 22 维", 22, features.first().vector.size)
    }

    @Test
    fun sensorSamplesOutsideWindowAreFilteredFromVector() {
        // Phase 4.1：加速度样本时间戳在窗口外 → 不计入统计（精确窗口归属）
        val now = Instant.now()
        val windowStart = now.minusSeconds(300)
        val features = extractor.extract(
            windowStart = windowStart,
            windowEnd = now,
            accelSamples = listOf(
                SensorSample(windowStart.toEpochMilli() - 10_000L, Sensor.TYPE_ACCELEROMETER, 99f, 99f, 99f),
                SensorSample(now.toEpochMilli() + 10_000L, Sensor.TYPE_ACCELEROMETER, 88f, 88f, 88f)
            ),
            screenEvents = listOf(
                ScreenCollector.ScreenEvent(now.minusSeconds(60).toEpochMilli(), ScreenCollector.ScreenState.ON)
            )
        )
        assertEquals(1, features.size)
        // source 应 fallback 到 screen（accel 全被过滤，不参与 selectSource）
        assertEquals("窗口外 accel 不应计入 → source 为 screen", "screen", features.first().source)
        // accel 统计段全为 0（8 维）
        val vector = features.first().vector
        assertEquals("accel 8 维应为 0", 8, vector.take(8).count { it == 0f })
    }
}
