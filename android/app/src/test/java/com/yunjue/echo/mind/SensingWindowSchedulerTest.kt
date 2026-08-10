package com.yunjue.echo.mind

import com.yunjue.echo.mind.model.DerivedFeatureInput
import com.yunjue.echo.mind.sensing.FeatureExtractor
import com.yunjue.echo.mind.sensing.MicDerivedFeatureSource
import com.yunjue.echo.mind.sensing.MicFeatureExtractor
import com.yunjue.echo.mind.sensing.NotificationCollector
import com.yunjue.echo.mind.sensing.ScreenCollector
import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensingWindowScheduler
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
 * - buffer 清空：flush 后 hub 缓冲清空、麦克风缓冲清空
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

    // ===== flush 行为：提取 + 清空 =====

    @Test
    fun flushWindowExtractsAndClearsHubBuffers() = runTest {
        val hub = SensingEventHub()
        val now = Instant.parse("2026-08-01T12:00:00Z")
        hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
        hub.onNotificationPosted(NotificationCollector.NotificationMeta(now.plusSeconds(60).toEpochMilli(), "pkg", "social"))

        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        var flushed: List<DerivedFeatureInput> = emptyList()
        val result = scheduler.flushWindow(now, now.plusMillis(windowMs)) { flushed = it }

        assertEquals(1, result.size)
        assertEquals(1, flushed.size)
        assertEquals("accel", flushed.first().source)
        assertTrue("sources_present 应包含 accel/notification", flushed.first().sourcesPresent.containsAll(listOf("accel", "notification")))
        // flush 后 hub 缓冲清空（不无限增长）
        assertTrue(hub.snapshotAccel().isEmpty())
        assertTrue(hub.snapshotNotifications().isEmpty())
    }

    @Test
    fun emptyWindowFlushesNothingAndClears() = runTest {
        val hub = SensingEventHub()
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        var callbackCalls = 0
        val result = scheduler.flushWindow(
            Instant.parse("2026-08-01T12:00:00Z"),
            Instant.parse("2026-08-01T12:05:00Z")
        ) { callbackCalls++ }

        assertTrue("空窗口不应产出特征", result.isEmpty())
        assertEquals("空窗口不应调用 onWindowReady", 0, callbackCalls)
    }

    @Test
    fun micFeaturesAreConsumedAsMicOptInputs() = runTest {
        val hub = SensingEventHub()
        val mic = MicSourceForTest()
        mic.seed(2)
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC(), micCollector = mic)
        var flushed: List<DerivedFeatureInput> = emptyList()
        scheduler.flushWindow(
            Instant.parse("2026-08-01T12:00:00Z"),
            Instant.parse("2026-08-01T12:05:00Z")
        ) { flushed = it }

        assertEquals("麦克风派生特征应转为 mic_opt 输入", 2, flushed.size)
        assertTrue(flushed.all { it.source == "mic_opt" })
        assertTrue(flushed.all { it.sourcesPresent == listOf("mic_opt") })
        // 消费后麦克风缓冲清空
        assertTrue(mic.snapshotAndClear().isEmpty())
    }

    // ===== 重复窗口防护 =====

    @Test
    fun sameWindowStartFlushesOnlyOnce() = runTest {
        val hub = SensingEventHub()
        hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        val ws = Instant.parse("2026-08-01T12:00:00Z")
        val we = Instant.parse("2026-08-01T12:05:00Z")

        val first = scheduler.flushWindow(ws, we) {}
        val second = scheduler.flushWindow(ws, we) {}

        assertEquals(1, first.size)
        assertTrue("同一 windowStart 第二次 flush 应返回空（去重）", second.isEmpty())
        assertTrue(scheduler.hasFlushed(ws.toEpochMilli()))
    }

    // ===== start 循环在边界触发 flush（注入时钟 + 虚拟时间） =====

    @Test
    fun startLoopFlushesAtAlignedBoundary() = runTest {
        val clock = MutableTestClock(Instant.parse("2026-08-01T12:03:00Z").toEpochMilli())
        val hub = SensingEventHub()
        hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
        val flushedStarts = mutableListOf<Instant>()
        val scheduler = SensingWindowScheduler(hub, clock = clock, windowDurationMs = windowMs)
        scheduler.start(this) { inputs ->
            inputs.firstOrNull()?.let { flushedStarts.add(it.windowStart) }
        }
        assertTrue("start 后应处于运行状态", scheduler.running)

        // 推进到 12:05 边界 → flush 12:00 窗口
        clock.advanceMs(2 * 60 * 1000L)
        advanceTimeBy(2 * 60 * 1000L)
        runCurrent()
        assertEquals(listOf(Instant.parse("2026-08-01T12:00:00Z")), flushedStarts)

        // 推进到 12:10 边界 → flush 12:05 窗口
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

    /** 测试用麦克风派生特征源替身（实现 [MicDerivedFeatureSource]，不依赖 Android 框架）。 */
    private class MicSourceForTest : MicDerivedFeatureSource {
        private val buffer = java.util.concurrent.ConcurrentLinkedDeque<MicFeatureExtractor.MicDerivedFeature>()
        fun seed(count: Int) {
            repeat(count) {
                buffer.offerLast(MicFeatureExtractor().emptyFeature())
            }
        }
        override fun snapshotAndClear(): List<MicFeatureExtractor.MicDerivedFeature> {
            val snap = buffer.toList()
            buffer.clear()
            return snap
        }
    }
}
