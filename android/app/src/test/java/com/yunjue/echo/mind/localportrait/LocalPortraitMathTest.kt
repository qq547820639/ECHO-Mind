package com.yunjue.echo.mind.localportrait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 端侧稳健统计纯函数测试（镜像后端 metrics.py / circular.py 语义）。
 */
class LocalPortraitMathTest {

    @Test
    fun medianOddAndEven() {
        assertEquals(2.0, LocalPortraitMath.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, LocalPortraitMath.median(listOf(1.0, 2.0, 3.0, 4.0)))
        assertNull(LocalPortraitMath.median(emptyList()))
    }

    @Test
    fun madAroundMedian() {
        // median([1,2,3,100])=2.5；偏差 [1.5,0.5,0.5,97.5] 的中位数 = 1.0
        assertEquals(1.0, LocalPortraitMath.mad(listOf(1.0, 2.0, 3.0, 100.0)))
        assertNull(LocalPortraitMath.mad(emptyList()))
    }

    @Test
    fun percentileLinearInterpolation() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(1.0, LocalPortraitMath.percentile(values, 0.0)!!, 1e-9)
        assertEquals(3.0, LocalPortraitMath.percentile(values, 50.0)!!, 1e-9)
        assertEquals(5.0, LocalPortraitMath.percentile(values, 100.0)!!, 1e-9)
        // 线性插值：k = 4*0.25 = 1.0 → 2.0
        assertEquals(2.0, LocalPortraitMath.percentile(values, 25.0)!!, 1e-9)
        assertNull(LocalPortraitMath.percentile(emptyList(), 50.0))
    }

    @Test
    fun circularMedianAcrossMidnight() {
        // 23:55 / 00:05 / 00:10：圆周视角三者紧密相邻，中位数应为 5（00:05）
        assertEquals(5.0, LocalPortraitMath.circularMedian(listOf(1435.0, 5.0, 10.0))!!, 1e-9)
        assertEquals(5.0, LocalPortraitMath.circularMad(listOf(1435.0, 5.0, 10.0))!!, 1e-9)
    }

    @Test
    fun circularDistanceWraps() {
        assertEquals(10.0, LocalPortraitMath.circularDistance(1435.0, 5.0), 1e-9)
        assertEquals(0.0, LocalPortraitMath.circularDistance(5.0, 1445.0), 1e-9)
        assertEquals(720.0, LocalPortraitMath.circularDistance(0.0, 720.0), 1e-9)
    }

    @Test
    fun circularSingleAndEmpty() {
        assertNull(LocalPortraitMath.circularMedian(emptyList()))
        assertNull(LocalPortraitMath.circularMad(emptyList()))
        assertEquals(7.0, LocalPortraitMath.circularMedian(listOf(7.0))!!, 1e-9)
        assertEquals(0.0, LocalPortraitMath.circularMad(listOf(7.0))!!, 1e-9)
    }

    @Test
    fun computeStatsCircularDropsPercentiles() {
        val stats = computeLocalStats(listOf(1435.0, 5.0, 10.0), circular = true)
        assertEquals(5.0, stats.median!!, 1e-9)
        assertNull(stats.p10)
        assertNull(stats.p25)
        assertNull(stats.p75)
        assertNull(stats.p90)
        assertEquals(3, stats.validDays)
    }

    @Test
    fun computeStatsEmptyAllNull() {
        val stats = computeLocalStats(emptyList())
        assertNull(stats.median)
        assertNull(stats.mad)
        assertNull(stats.p10)
        assertEquals(0, stats.validDays)
    }

    @Test
    fun computeStatsAllZeroStillComputes() {
        val stats = computeLocalStats(listOf(0.0, 0.0, 0.0))
        assertEquals(0.0, stats.median!!, 1e-9)
        assertEquals(0.0, stats.mad!!, 1e-9)
        assertEquals(3, stats.validDays)
    }
}
