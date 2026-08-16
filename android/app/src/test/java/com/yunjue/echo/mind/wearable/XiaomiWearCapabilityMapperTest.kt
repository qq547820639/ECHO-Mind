package com.yunjue.echo.mind.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TEST — WRIST SOURCE FUSION 前置（vendor 状态映射）：
 * 未验证 vendor 信号 → UNKNOWN；battery/charging 属 DEVICE_HEALTH；
 * NOT_WORN 绝不等于"用户静止"。
 */
class XiaomiWearCapabilityMapperTest {

    @Test
    fun wearing_unverifiedOrInvalid_isUnknown() {
        assertEquals("WORN", XiaomiWearCapabilityMapper.wearingFromVendor(1))
        assertEquals("NOT_WORN", XiaomiWearCapabilityMapper.wearingFromVendor(2))
        assertEquals("UNKNOWN", XiaomiWearCapabilityMapper.wearingFromVendor(null))
        assertEquals("UNKNOWN", XiaomiWearCapabilityMapper.wearingFromVendor(9))
    }

    @Test
    fun sleep_unverified_isUnknown_neverAssumed() {
        assertEquals("SLEEPING", XiaomiWearCapabilityMapper.sleepFromVendor(1))
        assertEquals("AWAKE", XiaomiWearCapabilityMapper.sleepFromVendor(2))
        assertEquals("UNKNOWN", XiaomiWearCapabilityMapper.sleepFromVendor(null))
        assertEquals("UNKNOWN", XiaomiWearCapabilityMapper.sleepFromVendor(0))
    }

    @Test
    fun connectionMapping() {
        assertEquals(WearableConnectionState.CONNECTED, XiaomiWearCapabilityMapper.connectionFromVendor(1))
        assertEquals(WearableConnectionState.DISCONNECTED, XiaomiWearCapabilityMapper.connectionFromVendor(2))
        assertEquals(WearableConnectionState.DISCONNECTED, XiaomiWearCapabilityMapper.connectionFromVendor(null))
    }

    @Test
    fun battery_outOfRange_isUnknown_notClamped() {
        assertEquals(98, XiaomiWearCapabilityMapper.batteryFromVendor(98))
        assertNull(XiaomiWearCapabilityMapper.batteryFromVendor(101))
        assertNull(XiaomiWearCapabilityMapper.batteryFromVendor(-1))
        assertNull(XiaomiWearCapabilityMapper.batteryFromVendor(null))
    }

    @Test
    fun deviceStateSnapshot_preservesUnknown() {
        val allUnknown = XiaomiWearCapabilityMapper.deviceStateFromVendor()
        assertEquals("UNKNOWN", allUnknown.wearing)
        assertEquals("UNKNOWN", allUnknown.sleep)
        assertNull(allUnknown.batteryPercent)
        assertNull(allUnknown.charging)

        val full = XiaomiWearCapabilityMapper.deviceStateFromVendor(
            wearing = 2, sleep = 1, battery = 42, charging = 1,
        )
        assertEquals("NOT_WORN", full.wearing)
        assertEquals("SLEEPING", full.sleep)
        assertEquals(42, full.batteryPercent)
        assertEquals(true, full.charging)
    }

    @Test
    fun notWorn_neverMapsToStationary() {
        // not worn → UNKNOWN（在观察映射层断言，此处验证 vendor 语义不产生静止暗示）。
        val state = XiaomiWearCapabilityMapper.deviceStateFromVendor(wearing = 2)
        assertEquals("NOT_WORN", state.wearing)
        // 映射层没有把 NOT_WORN 翻译成任何活动语义（无 movementClass 输出）。
    }
}
