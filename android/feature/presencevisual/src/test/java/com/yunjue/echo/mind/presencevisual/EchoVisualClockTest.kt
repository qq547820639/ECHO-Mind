package com.yunjue.echo.mind.presencevisual

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V3 §N — EchoVisualClock（boot-global monotonic）锚点：
 * 非负且跨调用单调不减（同一 ECHO 的视觉相位时间基准不回退）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EchoVisualClockTest {

    @Test
    fun nowNanosIsPositive() {
        assertTrue(EchoVisualClock.nowNanos() > 0L)
    }

    @Test
    fun nowNanosIsMonotonicNonDecreasing() {
        var prev = EchoVisualClock.nowNanos()
        repeat(64) {
            val now = EchoVisualClock.nowNanos()
            assertTrue("boot-global 时钟不得回退（prev=$prev now=$now）", now >= prev)
            prev = now
        }
    }

    @Test
    fun nowSecondsAgreesWithNanos() {
        val nanos = EchoVisualClock.nowNanos()
        val seconds = EchoVisualClock.nowSeconds()
        assertTrue(seconds > 0f)
        // 相邻两次读取应在同一数量级（秒 = 纳秒 / 1e9）
        assertTrue(kotlin.math.abs(seconds - nanos / 1_000_000_000f) < 5f)
    }
}
