package com.yunjue.echo.mind.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST — CAPABILITY（ECHO_WRIST_CONTRACT §Capability Test）：
 * 能力测试以 `docs/wearable/XIAOMI_BAND10_CAPABILITY_MATRIX.md` 为源。
 * 状态：SUPPORTED / UNSUPPORTED / UNKNOWN，绝不猜；未验证 Band10 → UNKNOWN / BLOCKED_EXTERNAL。
 */
class WearableCapabilityTest {

    @Test
    fun band10Matrix_matchesOfficialDocFacts() {
        val caps = WearableCapability.BAND10_CAPABILITIES
        // 官方 sensor 文档支持列表：accelerometer/pressure 支持，compass 不支持（Band 10）
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.ACCELEROMETER))
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.PRESSURE))
        assertEquals(WearCapabilityStatus.UNSUPPORTED, statusOf(caps, WearCapabilityId.COMPASS))
        // 官方 vibrator 文档：vibrate short/long 支持；start/stop pattern 不支持（仅 Watch S5）
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.VIBRATION_SHORT_LONG))
        assertEquals(WearCapabilityStatus.UNSUPPORTED, statusOf(caps, WearCapabilityId.VIBRATION_PATTERN))
        // 官方后台运行文档：传感器不在后台接口允许列表
        assertEquals(WearCapabilityStatus.UNSUPPORTED, statusOf(caps, WearCapabilityId.BACKGROUND_SENSOR_LOOP))
        // 官方 interconnect / fetch：支持
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.INTERCONNECT_MESSAGING))
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.NETWORK_FETCH))
        // 通用 BLE 无公开文档事实 → UNKNOWN（不猜、不 reverse engineer）
        assertEquals(WearCapabilityStatus.UNKNOWN, statusOf(caps, WearCapabilityId.GENERIC_BLE))
        // Android 穿戴 SDK（vendor）：官方文档存在，SDK 本体 BLOCKED —— 仍按 vendor 能力标记
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.DEVICE_STATE_CONNECTION))
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.DEVICE_STATE_BATTERY))
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.DEVICE_STATE_CHARGING))
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.DEVICE_STATE_WEARING))
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.DEVICE_STATE_SLEEP))
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.WEAR_APP_INSTALL_CHECK))
        assertEquals(WearCapabilityStatus.SUPPORTED, statusOf(caps, WearCapabilityId.WEAR_APP_LAUNCH))
    }

    @Test
    fun unknownCapabilityId_defaultsToUnknown_neverGuesses() {
        // 任何矩阵外的能力 id → UNKNOWN（纪律：绝不猜）。
        val caps = WearableCapability.BAND10_CAPABILITIES
        val status = WearableCapability.statusOf(caps, WearCapabilityId.GENERIC_BLE)
        assertEquals(WearCapabilityStatus.UNKNOWN, status)
    }

    @Test
    fun band10Screen_isOfficialSpec() {
        assertEquals(212, WearableDeviceProfile.BAND10_SCREEN_WIDTH)
        assertEquals(520, WearableDeviceProfile.BAND10_SCREEN_HEIGHT)
        val profile = WearableDeviceProfile.band10("node-1")
        assertEquals("Xiaomi Band 10", profile.model)
        assertEquals(212, profile.screenWidth)
        assertEquals(520, profile.screenHeight)
    }

    @Test
    fun everyCapability_hasEvidenceReference() {
        // 每条能力必须附 Matrix 证据引用，防止"我觉得应该支持"式漂移。
        for (capability in WearableCapability.BAND10_CAPABILITIES) {
            assertTrue("${capability.id} must have evidenceRef", capability.evidenceRef.isNotBlank())
        }
    }

    @Test
    fun deviceStateClassification_isStrict() {
        // 严格分类：battery/charging/connection → DEVICE_HEALTH；
        // wearing → OBSERVATION_QUALITY；sleep → OPTIONAL_NEUTRAL_CONTEXT。
        // 禁止 battery → Portrait / charging → Memory / connection → SelfModel。
        val classes = WearDeviceStateClass.entries
        assertEquals(3, classes.size)
        assertEquals("DEVICE_HEALTH", WearDeviceStateClass.DEVICE_HEALTH.name)
        assertEquals("OBSERVATION_QUALITY", WearDeviceStateClass.OBSERVATION_QUALITY.name)
        assertEquals("OPTIONAL_NEUTRAL_CONTEXT", WearDeviceStateClass.OPTIONAL_NEUTRAL_CONTEXT.name)
    }

    private fun statusOf(caps: List<WearableCapability>, id: WearCapabilityId): WearCapabilityStatus =
        WearableCapability.statusOf(caps, id)
}
