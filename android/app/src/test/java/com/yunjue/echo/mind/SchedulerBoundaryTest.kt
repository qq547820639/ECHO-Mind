package com.yunjue.echo.mind

import com.yunjue.echo.mind.sensing.SensingEventHub
import com.yunjue.echo.mind.sensing.SensingWindowScheduler
import com.yunjue.echo.mind.sensing.WindowFlushResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant

/**
 * T02 Scheduler 健壮性：flushedWindowStarts 有界去重范围 + 重复窗口边界（纯 JVM）。
 *
 * - flushed 集只保留最近 FLUSHED_WINDOW_KEEP 个窗口（bounded dedupe range）
 * - 最老窗口被淘汰后可再次 flush（不误伤新窗口）
 * - 窗口边界恰好在边界时刻/跨天不重复
 */
class SchedulerBoundaryTest {

    @Test
    fun flushedWindowSetIsBounded() = runTest {
        val hub = SensingEventHub()
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        val base = Instant.parse("2026-08-01T00:00:00Z")
        val keep = SensingWindowScheduler.FLUSHED_WINDOW_KEEP

        for (i in 0 until (keep + 10)) {
            val ws = base.plusMillis(i * 300_000L)
            hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
            val result = scheduler.flushWindow(ws, ws.plusMillis(300_000L)) { true }
            assertEquals(WindowFlushResult.SUCCESS, result)
        }

        // 最老的窗口已被淘汰（有界去重）
        assertFalse("最老窗口应被淘汰", scheduler.hasFlushed(base.toEpochMilli()))
        // 最新窗口仍在
        val latest = base.plusMillis((keep + 9) * 300_000L)
        assertTrue("最新窗口应保留", scheduler.hasFlushed(latest.toEpochMilli()))
    }

    @Test
    fun evictedWindowCanBeFlushedAgain() = runTest {
        val hub = SensingEventHub()
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        val base = Instant.parse("2026-08-01T00:00:00Z")
        val keep = SensingWindowScheduler.FLUSHED_WINDOW_KEEP

        for (i in 0 until keep) {
            val ws = base.plusMillis(i * 300_000L)
            hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
            scheduler.flushWindow(ws, ws.plusMillis(300_000L)) { true }
        }
        // 最老的已淘汰
        assertFalse(scheduler.hasFlushed(base.toEpochMilli()))
        // 重新 flush 最老窗口：可再次成功（不再被去重误伤）
        hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
        val result = scheduler.flushWindow(base, base.plusMillis(300_000L)) { true }
        assertEquals(WindowFlushResult.SUCCESS, result)
        assertTrue(scheduler.hasFlushed(base.toEpochMilli()))
    }

    @Test
    fun duplicateWindowFlushesOnlyOnce() = runTest {
        val hub = SensingEventHub()
        hub.onAccelSample(floatArrayOf(0f, 0f, 9.8f))
        val scheduler = SensingWindowScheduler(hub, clock = Clock.systemUTC())
        val ws = Instant.parse("2026-08-01T12:00:00Z")
        var calls = 0
        scheduler.flushWindow(ws, ws.plusMillis(300_000L)) { calls++; true }
        scheduler.flushWindow(ws, ws.plusMillis(300_000L)) { calls++; true }
        assertEquals("重复窗口只回调一次", 1, calls)
    }

    @Test
    fun crossMidnightWindowAlignmentStaysStable() {
        val scheduler = SensingWindowScheduler(SensingEventHub(), clock = Clock.systemUTC())
        // 23:58 → 对齐 23:55；00:02 → 对齐 00:00（跨天）
        assertEquals(
            Instant.parse("2026-08-01T23:55:00Z").toEpochMilli(),
            scheduler.alignWindowStartMs(Instant.parse("2026-08-01T23:58:00Z").toEpochMilli())
        )
        assertEquals(
            Instant.parse("2026-08-02T00:00:00Z").toEpochMilli(),
            scheduler.alignWindowStartMs(Instant.parse("2026-08-02T00:02:00Z").toEpochMilli())
        )
    }
}
