package com.yunjue.echo.mind.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 32 R22（时钟基准修复回归）：SensorEvent.timestamp 是开机纳秒，
 * 换算结果必须落在 epoch 毫秒的窗口范围内——防回归「开机毫秒被 epoch 窗口全量过滤、
 * accel/gyro 活动量特征从未产出」这一确定性缺陷。
 */
class SensorTimestampConversionTest {

    private val nowEpochMs = 1_750_000_000_000L
    private val nowElapsedNs = 500_000_000_000L // 开机约 8.3 分钟

    @Test
    fun sampleTakenNowMapsToNowEpoch() {
        assertEquals(
            "开机纳秒=当前开机时间时，应精确等于当前 epoch",
            nowEpochMs,
            sensorEventTimestampToEpochMs(nowElapsedNs, nowEpochMs, nowElapsedNs)
        )
    }

    @Test
    fun sampleFiveMinutesAgoMapsBackFiveMinutes() {
        val fiveMinutesNs = 5 * 60 * 1_000_000_000L
        assertEquals(
            "5 分钟前的样本应换算为 5 分钟前的 epoch 毫秒",
            nowEpochMs - 5 * 60 * 1000L,
            sensorEventTimestampToEpochMs(nowElapsedNs - fiveMinutesNs, nowEpochMs, nowElapsedNs)
        )
    }

    @Test
    fun convertedTimestampIsEpochScale() {
        // 防回归核心断言：换算结果必须 ≥ 2020-01-01 epoch（开机毫秒最大 ~1e10，永不满足）
        val converted = sensorEventTimestampToEpochMs(nowElapsedNs, nowEpochMs, nowElapsedNs)
        assertTrue("换算结果应为 epoch 毫秒量级，实际 $converted", converted >= 1_577_836_800_000L)
        // 且与当前 epoch 相差不超过 1 分钟（同一时刻采样）
        assertTrue("换算结果应贴近当前 epoch", kotlin.math.abs(converted - nowEpochMs) <= 60_000L)
    }

    @Test
    fun nonPositiveTimestampFallsBackToNowEpoch() {
        assertEquals(nowEpochMs, sensorEventTimestampToEpochMs(0L, nowEpochMs, nowElapsedNs))
        assertEquals(nowEpochMs, sensorEventTimestampToEpochMs(-1L, nowEpochMs, nowElapsedNs))
    }
}
