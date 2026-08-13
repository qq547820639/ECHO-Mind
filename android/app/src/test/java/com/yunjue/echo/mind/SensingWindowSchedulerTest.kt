package com.yunjue.echo.mind

import android.hardware.Sensor
import com.yunjue.echo.mind.model.DerivedFeatureInput
import com.yunjue.echo.mind.sensing.FeatureExtractor
import com.yunjue.echo.mind.sensing.MicDerivedFeatureSource
import com.yunjue.echo.mind.sensing.MicFeatureExtractor
import com.yunjue.echo.mind.sensing.NotificationCollector
import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensingWindowScheduler
import com.yunjue.echo.mind.sensing.SensorSample
import com.yunjue.echo.mind.sensing.WindowFlushResult
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * T02 SensingWindowScheduler 单测（纯 JVM + kotlinx-coroutines-test）。
 *
 * - 窗口边界：epoch 对齐（:00/:05/:10）
 * - 重启恢复：任意时刻对齐到下一边界
 * - 重复窗口防护：同一 windowStart 只 flush 一次
 * - **窗口 ACK 语义**：持久化成功才清 consumed + 进 flushed 集；失败保留缓冲 + bounded retry
 * - 注入时钟：MutableTestClock 控制时间推进
 */
class SensingWindowSchedulerTest {

    private class MutableTestClock(@Volatile var millisValue: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millisValue)
        override fun millis(): Long = millisValue
        fun advanceMs(delta: Long) {
            millisValue += delta
        }
    }

    private val windowMs = 5 * 60 * 1000L

    /** Phase 4.1：构造窗口内加速度样本（0 值三轴 + 指定时间戳）。 */
    private fun accelSample(timestampMs: Long): SensorSample =
        SensorSample(timestampMs, Sensor.TYPE_ACCELEROMETER, 0f, 0f, 9.8f)

    // ===== 窗口边界（epoch 对齐） =====

    @Test
    fun alignWindowStartToEpochBoundary() {
        val scheduler = SensingWindowScheduler(SensingEventHub(), clock = Clock.systemUTC())
        // 12:03:00 → 12:00:00
        assertEquals(Instant.parse("2026-08-01T12:00:00Z").toEpochMilli(), scheduler.alignWindowStartMs(Instant.parse("2026-08-01T12:03:00Z").toEpochMilli()))
        // 12:07:30 → 12:05:00
        assertEquals(Instant.parse("2026-08-01T12:05:00Z").toEpochMilli(), scheduler.alignWindowStartMs(Instant.parse("2026-08-01T12:07:30Z").toEpochMilli()))
        // 恰好边界 12:05:00 → 12:05:00
        assertEquals(Instant.parse("2026-08-01T12:05:00Z").toEpochMilli(), scheduler.alignWindowStartMs(Instant.parse("2026-08-01T12:05:00Z").toEpochMilli()))
    }

    @Test
    fun windowDurationReferencesFeatureExtractorConstant() {
        val scheduler = SensingWindowScheduler(SensingEventHub(), clock = Clock.systemUTC())
        // 默认 windowDurationMs 必须引用 FeatureExtractor.WINDOW_DURATION_MS（5*60*1000）
        assertEquals(com.yunjue.echo.mind.sensing.FeatureExtractor.WINDOW_DURATION_MS, windowMs)
        assertEquals(windowMs, scheduler.alignWindowStartMs(1_000_000L + windowMs) - scheduler.alignWindowStartMs(1_000_000L))
    }

    // ===== 重启恢复 =====

    @Test
    fun restartResumesAtNextAlignedBoundary() {
        val scheduler = SensingWindowScheduler(SensingEventHub(), clock = Clock.systemUTC())
        // 进程在 12:03 被杀，12:07 重启 → 下一个 flush 窗口开始 = 12:10（对齐边界）
        val restartMs = Instant.parse("2026-08-01T12:07:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-08-01T12:10:00Z").toEpochMilli(), scheduler.nextWindowStartMs(restartMs))
        // 重启时当前窗口起点对齐到 12:05（而非 12:07 任意时刻）
        assertEquals(Instant.parse("2026-08-01T12:05:00Z").toEpochMilli(), scheduler.alignWindowStartMs(restartMs))
    }

    // ===== flush 行为：提取 + ACK 成功清 consumed =====

    @Test
    fun flushWindowExtractsAndClearsHubBuffersOnSuccess() = runTest {
        val hub = SensingEventHub()
        val now = Instant.parse("2026-08-01T12:00:00Z")
        hub.onAccelSample(accelSample(now.plusSeconds(60).toEpochMilli()))
        hub.onNotificationPosted(NotificationCollector.NotificationMeta(now.plusSeconds(60).toEpochMilli(), "pkg", "social"))

        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        var flushed: List<DerivedFeatureInput> = emptyList()
        val result = scheduler.flushWindow(now, now.plusMillis(windowMs)) {
            flushed = it
            true
        }

        assertEquals(WindowFlushResult.SUCCESS, result)
        assertEquals(1, flushed.size)
        assertEquals("accel", flushed.first().source)
        assertTrue("sources_present 应包含 accel/notification", flushed.first().sourcesPresent.containsAll(listOf("accel", "notification")))
        // 成功后 hub 缓冲清空（只清本窗口 consumed 项）
        assertTrue(hub.snapshotAccel().isEmpty())
        assertTrue(hub.snapshotNotifications().isEmpty())
    }

    @Test
    fun emptyWindowFlushesNothingAndNoCallback() = runTest {
        val hub = SensingEventHub()
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        var callbackCalls = 0
        val result = scheduler.flushWindow(
            Instant.parse("2026-08-01T12:00:00Z"),
            Instant.parse("2026-08-01T12:05:00Z")
        ) {
            callbackCalls++
            true
        }

        assertEquals("空窗口应 SUCCESS", WindowFlushResult.SUCCESS, result)
        assertEquals("空窗口不应调用 onWindowReady", 0, callbackCalls)
    }

    @Test
    fun micFeaturesAreConsumedAsMicOptInputs() = runTest {
        val hub = SensingEventHub()
        val mic = MicSourceForTest()
        mic.seed(2)
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC(), micCollector = mic)
        var flushed: List<DerivedFeatureInput> = emptyList()
        val result = scheduler.flushWindow(
            Instant.parse("2026-08-01T12:00:00Z"),
            Instant.parse("2026-08-01T12:05:00Z")
        ) {
            flushed = it
            true
        }

        assertEquals(WindowFlushResult.SUCCESS, result)
        assertEquals("麦克风派生特征应转为 mic_opt 输入", 2, flushed.size)
        assertTrue(flushed.all { it.source == "mic_opt" })
        assertTrue(flushed.all { it.sourcesPresent == listOf("mic_opt") })
        // 成功消费后麦克风缓冲清空
        assertTrue(mic.snapshot().isEmpty())
    }

    // ===== 窗口 ACK：持久化失败保留缓冲 + bounded retry =====

    @Test
    fun flushFailureKeepsBuffersAndMarksRetryable() = runTest {
        val hub = SensingEventHub()
        val now = Instant.parse("2026-08-01T12:00:00Z")
        hub.onAccelSample(accelSample(now.plusSeconds(60).toEpochMilli()))

        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        var callbackCalls = 0
        val result = scheduler.flushWindow(now, now.plusMillis(windowMs)) {
            callbackCalls++
            false // 持久化失败
        }

        assertEquals(WindowFlushResult.FAILURE_RETRYABLE, result)
        assertEquals(1, callbackCalls)
        // 失败：缓冲保留（不清 consumed）、窗口不进 flushed 集、失败计数可见
        assertFalse("失败后缓冲应保留", hub.snapshotAccel().isEmpty())
        assertFalse("失败后窗口不应进 flushed 集", scheduler.hasFlushed(now.toEpochMilli()))
        assertEquals("失败计数应为 1", 1, scheduler.retryCount(now.toEpochMilli()))
    }

    @Test
    fun flushFailureThenSuccessClearsAndMarksFlushed() = runTest {
        val hub = SensingEventHub()
        val now = Instant.parse("2026-08-01T12:00:00Z")
        hub.onAccelSample(accelSample(now.plusSeconds(60).toEpochMilli()))

        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        var attempts = 0
        // 第一次失败
        val first = scheduler.flushWindow(now, now.plusMillis(windowMs)) {
            attempts++
            false
        }
        assertEquals(WindowFlushResult.FAILURE_RETRYABLE, first)
        assertFalse(hub.snapshotAccel().isEmpty())

        // 第二次成功
        val second = scheduler.flushWindow(now, now.plusMillis(windowMs)) {
            attempts++
            true
        }
        assertEquals(WindowFlushResult.SUCCESS, second)
        assertTrue("成功后缓冲应清空", hub.snapshotAccel().isEmpty())
        assertTrue("成功后窗口应进 flushed 集", scheduler.hasFlushed(now.toEpochMilli()))
        assertEquals("失败计数应被清除", 0, scheduler.retryCount(now.toEpochMilli()))
        assertEquals(2, attempts)
    }

    @Test
    fun flushExceedingMaxRetryDropsWindowButBuffersKept() = runTest {
        val hub = SensingEventHub()
        val now = Instant.parse("2026-08-01T12:00:00Z")
        hub.onAccelSample(accelSample(now.plusSeconds(60).toEpochMilli()))

        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        // 连续失败 MAX_WINDOW_RETRY+1 次 → 窗口被丢弃（不再重试）
        repeat(SensingWindowScheduler.MAX_WINDOW_RETRY + 1) {
            val result = scheduler.flushWindow(now, now.plusMillis(windowMs)) { false }
            assertEquals(WindowFlushResult.FAILURE_RETRYABLE, result)
        }
        assertEquals("超限后窗口应从 pending 移除", 0, scheduler.retryCount(now.toEpochMilli()))
        // 缓冲保留（数据未清，但窗口不再重试；失败已被记录/可观测）
        assertFalse(hub.snapshotAccel().isEmpty())
    }

    // ===== 重复窗口防护 =====

    @Test
    fun sameWindowStartFlushesOnlyOnce() = runTest {
        val hub = SensingEventHub()
        hub.onAccelSample(accelSample(Instant.parse("2026-08-01T12:00:00Z").toEpochMilli() + 60_000L))
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        val ws = Instant.parse("2026-08-01T12:00:00Z")
        val we = Instant.parse("2026-08-01T12:05:00Z")

        var calls = 0
        val first = scheduler.flushWindow(ws, we) { calls++; true }
        val second = scheduler.flushWindow(ws, we) { calls++; true }

        assertEquals(WindowFlushResult.SUCCESS, first)
        assertEquals("同一 windowStart 第二次 flush 应 SUCCESS（去重，不重复回调）", WindowFlushResult.SUCCESS, second)
        assertEquals("第二次不应触发回调", 1, calls)
        assertTrue(scheduler.hasFlushed(ws.toEpochMilli()))
    }

    // ===== start 循环在边界触发 flush（注入时钟 + 虚拟时间） =====

    @Test
    fun startLoopFlushesAtAlignedBoundary() = runTest {
        val clock = MutableTestClock(Instant.parse("2026-08-01T12:03:00Z").toEpochMilli())
        val hub = SensingEventHub()
        // 样本时间戳 = 当前注入时钟（12:03:00，落在 [12:00, 12:05) 窗口内）
        hub.onAccelSample(accelSample(clock.millis()))
        val flushedStarts = mutableListOf<Instant>()
        val scheduler = SensingWindowScheduler(hub, clock = clock, windowDurationMs = windowMs)
        scheduler.start(this) { inputs ->
            inputs.firstOrNull()?.let { flushedStarts.add(it.windowStart) }
            true
        }
        assertTrue("start 后应处于运行状态", scheduler.running)
        // 让循环首轮迭代先执行：读取当前时钟（12:03）并调度 delay(waitMs)，
        // 之后再推进时钟与虚拟时间，否则首轮读到的是已推进后的时钟（错过 12:00 窗口）
        runCurrent()

        // 推进到 12:05 边界 → flush 12:00 窗口
        clock.advanceMs(2 * 60 * 1000L)
        advanceTimeBy(2 * 60 * 1000L)
        runCurrent()
        assertEquals(listOf(Instant.parse("2026-08-01T12:00:00Z")), flushedStarts)

        // 推进到 12:10 边界 → flush 12:05 窗口（上一窗口已 clearConsumed，需重新注入样本）
        // 样本时间戳 = 当前注入时钟（12:05:00，落在 [12:05, 12:10) 窗口内）
        hub.onAccelSample(accelSample(clock.millis()))
        clock.advanceMs(5 * 60 * 1000L)
        advanceTimeBy(5 * 60 * 1000L)
        runCurrent()
        assertEquals(
            listOf(
                Instant.parse("2026-08-01T12:00:00Z"),
                Instant.parse("2026-08-01T12:05:00Z")
            ),
            flushedStarts
        )
        // 无重复窗口
        assertEquals(2, flushedStarts.size)

        scheduler.stop()
        assertFalse("stop 后应停止", scheduler.running)
    }

    @Test
    fun stopIsIdempotent() {
        val scheduler = SensingWindowScheduler(SensingEventHub(), clock = Clock.systemUTC())
        scheduler.stop()
        scheduler.stop()
        assertFalse(scheduler.running)
    }

    @Test
    fun stopClearsPendingRetries() = runTest {
        val hub = SensingEventHub()
        val now = Instant.parse("2026-08-01T12:00:00Z")
        hub.onAccelSample(accelSample(now.plusSeconds(60).toEpochMilli()))
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        scheduler.flushWindow(now, now.plusMillis(windowMs)) { false }
        assertEquals(1, scheduler.retryCount(now.toEpochMilli()))
        scheduler.stop()
        assertEquals("stop 后应清空失败重试状态", 0, scheduler.pendingRetryCount)
    }

    /** 测试用麦克风派生特征源替身（实现 [MicDerivedFeatureSource]，不依赖 Android 框架）。 */
    private class MicSourceForTest : MicDerivedFeatureSource {
        private val buffer = java.util.concurrent.ConcurrentLinkedDeque<MicFeatureExtractor.MicDerivedFeature>()
        fun seed(count: Int) {
            repeat(count) {
                buffer.offerLast(MicFeatureExtractor().emptyFeature())
            }
        }
        override fun snapshot(): List<MicFeatureExtractor.MicDerivedFeature> = buffer.toList()
        override fun clearConsumed(consumed: List<MicFeatureExtractor.MicDerivedFeature>) {
            consumed.forEach { buffer.remove(it) }
        }
    }
}
