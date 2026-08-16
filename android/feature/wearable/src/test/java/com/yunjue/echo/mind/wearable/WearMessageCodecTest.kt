package com.yunjue.echo.mind.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST — PROTOCOL（ECHO_WRIST_CONTRACT §Protocol Test）：
 * Presence v1 encode/decode / Observation / Action / Ack / Capability /
 * unknown field / future field / malformed JSON / missing optional fields /
 * unsupported schema。
 */
class WearMessageCodecTest {

    private fun presence(): WearPresenceEnvelope = WearPresenceEnvelope(
        messageId = "p-1",
        generatedAt = 1_000L,
        revision = 3L,
        expiresAt = 1_000L + 900_000L,
        maturity = "KNOWN",
        identity = WearIdentityProjection(0.5f, 0.5f, 0.5f, 0.5f, 2, 3, 0.5f),
        moment = WearMomentProjection(0.4f, 0.7f, 0.3f, 0.2f, 0.6f),
        surface = WearSurfaceParams("DEFAULT", false, false),
        publicHeadline = "今天开始得比通常晚一些。",
        availableActions = listOf("START_BREATHING", "START_PAUSE"),
    )

    @Test
    fun presenceV1_roundTrip() {
        val text = WearMessageCodec.encode(WearMessage.Presence(presence()))
        val decoded = WearMessageCodec.decode(text)
        assertTrue(decoded is WearMessageCodec.DecodeResult.Ok)
        val message = (decoded as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Presence
        val e = message.envelope
        assertEquals(WEAR_SCHEMA_V1, e.schemaVersion)
        assertEquals("p-1", e.messageId)
        assertEquals(1_000L, e.generatedAt)
        assertEquals(WearMessageSource.PHONE, e.source)
        assertEquals(3L, e.revision)
        assertEquals(901_000L, e.expiresAt)
        assertEquals("KNOWN", e.maturity)
        assertEquals(0.5f, e.identity.topology, 1e-6f)
        assertEquals(2, e.identity.texture)
        assertEquals(3, e.identity.colorFamily)
        assertEquals(0.4f, e.moment.flow, 1e-6f)
        assertEquals("DEFAULT", e.surface.motionLevel)
        assertFalse(e.surface.lowPower)
        assertFalse(e.surface.motionSummaryEnabled)
        assertEquals("今天开始得比通常晚一些。", e.publicHeadline)
        assertEquals(listOf("START_BREATHING", "START_PAUSE"), e.availableActions)
    }

    @Test
    fun presence_optionalFieldsMissing_decodeWithDefaults() {
        val e = presence().copy(publicHeadline = null, availableActions = emptyList())
        val text = WearMessageCodec.encode(WearMessage.Presence(e))
        // 手工去掉可选字段（模拟旧版发送方）
        val json = org.json.JSONObject(text)
        json.remove("publicHeadline")
        json.remove("availableActions")
        val decoded = WearMessageCodec.decode(json.toString())
        assertTrue(decoded is WearMessageCodec.DecodeResult.Ok)
        val envelope = (decoded as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Presence
        assertNull(envelope.envelope.publicHeadline)
        assertTrue(envelope.envelope.availableActions.isEmpty())
    }

    @Test
    fun observation_roundTrip_includingUnknownSentinel() {
        val envelope = WearObservationEnvelope(
            messageId = "o-1",
            generatedAt = 2_000L,
            motion = WearMotionSummary(
                motionEnergy = null, // UNKNOWN：null 不是 0
                movementClass = "UNKNOWN",
                sampleCoverage = 0.4f,
                quality = "POOR",
                windowStartMs = 1_000L,
                windowEndMs = 10_000L,
            ),
            deviceState = WearDeviceStateSnapshot(
                wearing = "NOT_WORN",
                sleep = "UNKNOWN",
                batteryPercent = null,
                charging = null,
            ),
        )
        val text = WearMessageCodec.encode(WearMessage.Observation(envelope))
        val decoded = WearMessageCodec.decode(text)
        assertTrue(decoded is WearMessageCodec.DecodeResult.Ok)
        val got = (decoded as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Observation
        assertNull(got.envelope.motion?.motionEnergy)
        assertEquals("UNKNOWN", got.envelope.motion?.movementClass)
        assertEquals("NOT_WORN", got.envelope.deviceState?.wearing)
        assertNull(got.envelope.deviceState?.batteryPercent)
        assertNull(got.envelope.deviceState?.charging)
    }

    @Test
    fun action_roundTrip_andUnknownCommandIsIgnorableNotCrash() {
        val envelope = WearActionEnvelope(messageId = "a-1", generatedAt = 3_000L, command = "START_BREATHING")
        val text = WearMessageCodec.encode(WearMessage.Action(envelope))
        val decoded = WearMessageCodec.decode(text)
        assertTrue(decoded is WearMessageCodec.DecodeResult.Ok)
        val got = (decoded as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Action
        assertEquals(WearActionCommand.START_BREATHING, got.envelope.parsedCommand)

        // 未知命令：解码成功、parsedCommand=null（forward compatible：忽略，不崩溃）
        val unknown = WearActionEnvelope(messageId = "a-2", generatedAt = 4_000L, command = "FUTURE_COMMAND")
        val got2 = WearMessageCodec.decode(WearMessageCodec.encode(WearMessage.Action(unknown)))
        val action2 = (got2 as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Action
        assertNull(action2.envelope.parsedCommand)
    }

    @Test
    fun ack_roundTrip() {
        val envelope = WearAckEnvelope(
            messageId = "ack-1",
            generatedAt = 5_000L,
            ackFor = "p-1",
            revision = 3L,
            status = "OK",
        )
        val text = WearMessageCodec.encode(WearMessage.Ack(envelope))
        val decoded = WearMessageCodec.decode(text)
        assertTrue(decoded is WearMessageCodec.DecodeResult.Ok)
        val got = (decoded as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Ack
        assertEquals("p-1", got.envelope.ackFor)
        assertEquals(3L, got.envelope.revision)
        assertEquals("OK", got.envelope.status)
    }

    @Test
    fun capability_roundTrip_bandToPhone() {
        val envelope = WearCapabilityEnvelope(
            messageId = "c-1",
            generatedAt = 6_000L,
            source = WearMessageSource.XIAOMI_BAND,
            protocolVersion = 1,
            screenWidth = 212,
            screenHeight = 520,
            capabilities = mapOf(
                "ACCELEROMETER" to "SUPPORTED",
                "VIBRATION_PATTERN" to "UNSUPPORTED",
            ),
        )
        val text = WearMessageCodec.encode(WearMessage.Capability(envelope))
        val decoded = WearMessageCodec.decode(text)
        assertTrue(decoded is WearMessageCodec.DecodeResult.Ok)
        val got = (decoded as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Capability
        assertEquals(212, got.envelope.screenWidth)
        assertEquals(520, got.envelope.screenHeight)
        assertEquals("SUPPORTED", got.envelope.capabilities["ACCELEROMETER"])
        assertEquals(WearMessageSource.XIAOMI_BAND, got.envelope.source)
    }

    @Test
    fun unknownField_ignored() {
        val text = WearMessageCodec.encode(WearMessage.Presence(presence()))
        val json = org.json.JSONObject(text)
        json.put("futureField", "anything")
        json.getJSONObject("identity").put("futureIdentityField", 42)
        val decoded = WearMessageCodec.decode(json.toString())
        assertTrue(decoded is WearMessageCodec.DecodeResult.Ok)
        // 未破坏必填字段解析
        val envelope = (decoded as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Presence
        assertEquals(3L, envelope.envelope.revision)
    }

    @Test
    fun malformedJson_isMalformed() {
        assertTrue(WearMessageCodec.decode("not json at all") is WearMessageCodec.DecodeResult.Malformed)
        assertTrue(WearMessageCodec.decode("{") is WearMessageCodec.DecodeResult.Malformed)
        assertTrue(WearMessageCodec.decode("") is WearMessageCodec.DecodeResult.Malformed)
        assertTrue(WearMessageCodec.decode("[]") is WearMessageCodec.DecodeResult.Malformed)
    }

    @Test
    fun unsupportedSchema_rejected() {
        val text = WearMessageCodec.encode(WearMessage.Presence(presence()))
        val json = org.json.JSONObject(text)
        json.put("schemaVersion", WEAR_SCHEMA_CURRENT + 1)
        val decoded = WearMessageCodec.decode(json.toString())
        assertTrue(decoded is WearMessageCodec.DecodeResult.UnsupportedSchema)
        assertEquals(WEAR_SCHEMA_CURRENT + 1, (decoded as WearMessageCodec.DecodeResult.UnsupportedSchema).schemaVersion)
    }

    @Test
    fun missingRequiredField_isMalformed() {
        val text = WearMessageCodec.encode(WearMessage.Presence(presence()))
        for (field in listOf("messageId", "generatedAt", "revision", "expiresAt", "maturity", "identity", "moment", "surface")) {
            val json = org.json.JSONObject(text)
            json.remove(field)
            val decoded = WearMessageCodec.decode(json.toString())
            assertTrue("field $field should be required", decoded is WearMessageCodec.DecodeResult.Malformed)
        }
        // identity 子字段必填
        val json2 = org.json.JSONObject(text)
        json2.getJSONObject("identity").remove("topology")
        assertTrue(WearMessageCodec.decode(json2.toString()) is WearMessageCodec.DecodeResult.Malformed)
    }

    @Test
    fun wrongTypedField_isMalformed() {
        val text = WearMessageCodec.encode(WearMessage.Presence(presence()))
        val json = org.json.JSONObject(text)
        json.put("messageId", 123) // 数字冒充字符串
        assertTrue(WearMessageCodec.decode(json.toString()) is WearMessageCodec.DecodeResult.Malformed)
        val json2 = org.json.JSONObject(text)
        json2.put("revision", "abc")
        assertTrue(WearMessageCodec.decode(json2.toString()) is WearMessageCodec.DecodeResult.Malformed)
    }

    @Test
    fun duplicateMessage_reencodesDeterministically() {
        val a = WearMessageCodec.encode(WearMessage.Presence(presence()))
        val b = WearMessageCodec.encode(WearMessage.Presence(presence()))
        assertEquals(a, b)
    }

    @Test
    fun unknownSourceName_fallsBackToBand() {
        val text = WearMessageCodec.encode(WearMessage.Presence(presence()))
        val json = org.json.JSONObject(text)
        json.put("source", "FUTURE_SOURCE")
        val decoded = WearMessageCodec.decode(json.toString())
        val envelope = (decoded as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Presence
        // 未知来源不崩；运行时对 band 来源 presence 直接忽略（防伪造）
        assertEquals(WearMessageSource.XIAOMI_BAND, envelope.envelope.source)
    }

    @Test
    fun nullOptionalStrings_decodeAsNull() {
        val e = presence().copy(publicHeadline = null)
        val text = WearMessageCodec.encode(WearMessage.Presence(e))
        val decoded = WearMessageCodec.decode(text)
        assertNotNull(decoded)
        val envelope = (decoded as WearMessageCodec.DecodeResult.Ok).message as WearMessage.Presence
        assertNull(envelope.envelope.publicHeadline)
    }
}
