package com.yunjue.echo.mind.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST — WRIST SOURCE FUSION（ECHO_WRIST_CONTRACT §Wrist Source Fusion）：
 * Phone stationary + Wrist moving / Phone moving + Wrist stationary / Band not worn /
 * Band disconnected / low quality / sleep / conflicting sources。
 * 验证：不重复计算、不误把 not-worn 当静止、不把 device state 当 personal truth。
 */
class WearSourceArbitrationTest {

    private fun observation(
        movementClass: String? = "WALKING",
        quality: String = "GOOD",
        coverage: Float = 0.95f,
        wearing: String = "WORN",
        sleep: String = "AWAKE",
        battery: Int? = 80,
        charging: Boolean? = false,
    ): WearObservationEnvelope = WearObservationEnvelope(
        messageId = "o-1",
        generatedAt = 1L,
        motion = WearMotionSummary(
            motionEnergy = 1.0f,
            movementClass = movementClass ?: "UNKNOWN",
            sampleCoverage = coverage,
            quality = quality,
            windowStartMs = 0L,
            windowEndMs = 10_000L,
        ),
        deviceState = WearDeviceStateSnapshot(
            wearing = wearing,
            sleep = sleep,
            batteryPercent = battery,
            charging = charging,
        ),
    )

    @Test
    fun wristMoving_wornAndGood_isUsable() {
        val result = WearSourceArbitration.arbitrate(observation())
        assertEquals("WALKING", result.movementClass)
        assertTrue(result.usable)
        assertNull(result.reason)
    }

    @Test
    fun bandNotWorn_qualityUnavailable_neverStationary() {
        val result = WearSourceArbitration.arbitrate(observation(wearing = "NOT_WORN"))
        assertEquals("UNAVAILABLE", result.quality)
        assertNull(result.movementClass) // 绝不把 not-worn 当静止（null 不是 STATIONARY）
        assertFalse(result.usable)
        assertEquals("NOT_WORN", result.reason)
    }

    @Test
    fun bandDisconnected_wearingUnknown_notFused() {
        // 断连时收不到观察；即使收到佩戴状态 UNKNOWN 的观察也不融合（未验证 vendor 信号）。
        val result = WearSourceArbitration.arbitrate(observation(wearing = "UNKNOWN"))
        assertEquals("UNKNOWN", result.quality)
        assertFalse(result.usable)
        assertEquals("WEARING_UNVERIFIED", result.reason)
    }

    @Test
    fun lowQualityWristObservation_notFused() {
        val poor = WearSourceArbitration.arbitrate(observation(quality = "POOR"))
        assertFalse(poor.usable)
        assertEquals("LOW_QUALITY", poor.reason)
        assertNull(poor.movementClass)

        val lowCoverage = WearSourceArbitration.arbitrate(observation(coverage = 0.5f))
        assertFalse(lowCoverage.usable)
        assertEquals("LOW_COVERAGE", lowCoverage.reason)
    }

    @Test
    fun sleepState_neverChangesActivitySemantics() {
        // 睡眠状态是中立上下文：SLEEPING 时腕上静止 = 静止（正在佩戴），
        // 但绝不把 sleep 翻译成"用户无活动"或任何心理标签——这里只验证仲裁输出不含睡眠语义。
        val result = WearSourceArbitration.arbitrate(
            observation(movementClass = "STATIONARY", sleep = "SLEEPING"),
        )
        assertEquals("STATIONARY", result.movementClass)
        // 仲裁结果结构上没有任何 sleep/心理字段（data class 只有 movementClass/quality/usable/reason）。
    }

    @Test
    fun conflictingSources_sourcesStaySeparate() {
        // Phone moving + Wrist stationary（或反之）：来源保持分离，不相加、不抵消。
        assertTrue(WearSourceArbitration.sourcesStaySeparate(phoneEvidencePresent = true, wristEvidencePresent = true))
    }

    @Test
    fun deviceStateNeverEntersPersonalTruth() {
        // battery/charging/connection 绝不进入个人画像（结构禁令恒 false）。
        assertFalse(WearSourceArbitration.deviceStateEntersPersonalTruth())
        // battery 值照常上报（DEVICE_HEALTH），但不进入仲裁的活动结论。
        val result = WearSourceArbitration.arbitrate(observation(battery = 5, charging = true))
        assertEquals("WALKING", result.movementClass) // 活动结论与电量无关
    }
}
