package com.yunjue.echo.mind

import com.yunjue.echo.mind.sensing.FeatureExtractor
import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensorCollector
import com.yunjue.echo.mind.sensing.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Phase 4.2 高采样量缓冲压力测试（纯 JVM，不依赖 Android 框架）。
 *
 * 验证 hub 的 [SensorCollector.MAX_BUFFER_SIZE]（4096）容量语义：
 * - 超量写入只保留最近 4096 条（trim 最旧，不崩溃）；
 * - **5 分钟窗口（1500 样本 @ 200ms）内样本不因容量丢失**（4096 > 1500）；
 * - 窗口过滤（Phase 4.1 精确归属）：timestampMs 在窗口外的样本不计入特征；
 * - 跨窗口：新到事件（下一窗口）不混入当前窗口特征。
 *
 * 说明：传感器类型使用局部常量（1 = android.hardware.Sensor.TYPE_ACCELEROMETER），
 * 纯 JVM 测试避免引用 android.jar 类（编译期常量虽可内联，但保持最小依赖）。
 */
class SensorBufferPressureTest {

    private val extractor = FeatureExtractor()
    private lateinit var hub: SensingEventHub

    // android.hardware.Sensor.TYPE_ACCELEROMETER = 1（编译期常量，纯 JVM 替代）
    private val typeAccelerometer = 1

    private val windowStart = Instant.parse("2026-08-01T12:00:00Z")
    private val windowEnd = windowStart.plusMillis(300_000L)

    @Before
    fun setUp() {
        hub = SensingEventHub()
    }

    private fun accelSample(timestampMs: Long, x: Float = 0f, y: Float = 0f, z: Float = 9.8f): SensorSample =
        SensorSample(timestampMs, typeAccelerometer, x, y, z)

    private fun snapshotOf(h: SensingEventHub): SensingEventHub.HubSnapshot = h.snapshotAll()

    // ===== 缓冲容量 =====

    @Test
    fun bufferTrimsAtMaxSizeUnderHighVolume() {
        val base = windowStart.toEpochMilli()
        // 5000 条 @ 200ms 间隔（≈16.7 分钟），远超 MAX_BUFFER_SIZE（4096）
        repeat(5000) { i ->
            hub.onAccelSample(accelSample(base + i * 200L))
        }
        assertEquals("缓冲不应超过 MAX_BUFFER_SIZE", SensorCollector.MAX_BUFFER_SIZE, hub.snapshotAccel().size)
        // 滑动窗口语义：保留最近 4096 条（最旧被丢弃）
        val firstRetained = base + (5000 - SensorCollector.MAX_BUFFER_SIZE) * 200L
        assertEquals("应保留最近 4096 条（最旧被丢弃）", firstRetained, hub.snapshotAccel().first().timestampMs)
    }

    @Test
    fun bufferSurvivesRepeatedBurstsAcrossWindows() {
        // 连续多窗口写入（模拟长时间运行），缓冲始终有界、不崩溃、不无限增长
        repeat(3) { w ->
            repeat(3000) { i ->
                hub.onAccelSample(accelSample(windowStart.toEpochMilli() + w * 300_000L + i * 100L))
            }
        }
        assertEquals(
            "多次突发后缓冲仍不超上限",
            SensorCollector.MAX_BUFFER_SIZE,
            hub.snapshotAccel().size
        )
    }

    // ===== 5 分钟窗口样本不因容量丢失 =====

    @Test
    fun fiveMinuteWindowSamplesAreNotLostAtCapacity() {
        val base = windowStart.toEpochMilli()
        // 1500 样本 @ 200ms = 300s = 5 分钟窗口（一窗满采样），容量 4096 充足
        repeat(1500) { i ->
            hub.onAccelSample(accelSample(base + i * 200L, x = i % 100 * 0.01f))
        }
        val snapshot = hub.snapshotAccel()
        assertTrue("1500 条窗口样本不应被容量丢弃", snapshot.size >= 1500)

        val features = extractor.extractFromSnapshot(
            windowStart, windowEnd, snapshotOf(hub)
        )
        assertEquals("窗口样本应产出特征", 1, features.size)
        val vector = features.first().vector
        // accel mean_x（vector[0]）基于窗口内全部样本统计 → 非零（x 轴有变化）
        assertTrue("accel mean_x 应非零（样本未被丢弃）", vector[0] != 0f)
        // magnitude_mean（vector[6]）≈ 9.8（z≈9.8 + x 小变化）
        assertTrue("accel magnitude_mean 应非零", vector[6] > 9.0f)
    }

    // ===== 窗口过滤（Phase 4.1 精确归属） =====

    @Test
    fun samplesOutsideWindowAreFilteredFromFeatures() {
        val base = windowStart.toEpochMilli()
        // 窗口内 500 条（z=9.8）；窗口前 250 条 + 窗口后 250 条（z=0，若误计入会拉低 magnitude）
        repeat(500) { i -> hub.onAccelSample(accelSample(base + i * 100L, z = 9.8f)) }
        repeat(250) { i -> hub.onAccelSample(accelSample(base - 100_000L - i * 100L, z = 0f)) }
        repeat(250) { i -> hub.onAccelSample(accelSample(windowEnd.toEpochMilli() + i * 100L, z = 0f)) }

        val features = extractor.extractFromSnapshot(windowStart, windowEnd, snapshotOf(hub))
        assertEquals(1, features.size)
        val vector = features.first().vector
        // 仅窗口内样本计入 → magnitude_mean = 9.8；若窗口外混入（z=0）会被拉低
        assertEquals("仅窗口内样本计入 → magnitude_mean 应为 9.8", 9.8f, vector[6], 1e-4f)
    }

    // ===== 跨窗口不混入 =====

    @Test
    fun crossWindowEventsDoNotMixIntoCurrentWindow() {
        val base = windowStart.toEpochMilli()
        // 当前窗口 [12:00, 12:05)：1500 条（z=9.8）
        repeat(1500) { i ->
            hub.onAccelSample(accelSample(base + i * 200L, z = 9.8f))
        }
        // 下一窗口 [12:05, 12:10)：500 条（x=5, z=0）——不得混入当前窗口
        val nextStart = windowEnd.toEpochMilli()
        repeat(500) { i ->
            hub.onAccelSample(accelSample(nextStart + i * 200L, x = 5f, z = 0f))
        }

        val features = extractor.extractFromSnapshot(windowStart, windowEnd, snapshotOf(hub))
        assertEquals(1, features.size)
        val vector = features.first().vector
        assertEquals("当前窗口 mean_x 应为 0（下一窗口 x=5 不混入）", 0f, vector[0], 1e-4f)
        assertEquals("当前窗口 magnitude_mean 应为 9.8", 9.8f, vector[6], 1e-4f)
    }
}
