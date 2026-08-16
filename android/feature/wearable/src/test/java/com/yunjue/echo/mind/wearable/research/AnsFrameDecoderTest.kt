package com.yunjue.echo.mind.wearable.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST — ANS GOLDEN（ECHO_WRIST_CONTRACT §ANS Golden）：
 * Python output ↔ ANS_FRAME_V1 ↔ Kotlin decoder。
 * 必须保持 quality / confidence / UNKNOWN；任何 affective/stress 保持 research-only。
 *
 * 注意：本文件内 golden JSON 与 `integrations/answatch/golden` 目录下的帧文件逐字节一致，
 * 由 `integrations/answatch/tools/verify_golden.py` 跨语言锁定（黄金门）。
 */
class AnsFrameDecoderTest {

    private val okGolden = """
        {
          "schema_version": "1.0",
          "subject_id": "P0001",
          "window_id": 0,
          "window_end_timestamp": 1735689600000000,
          "signal_quality": {
            "sqi_eda": 0.92, "sqi_ppg": 0.88, "sqi_temp": 0.95, "sqi_imu": 0.91,
            "on_body": true,
            "repaired": { "eda": false, "ppg": true }
          },
          "physiological_arousal": { "activation": 62.5, "trend_30min": "stable" },
          "stress_likelihood": { "value": 0.34, "disambiguation_flags": [] },
          "confidence": { "value": 0.81, "calibrated": true, "calibration_model_id": "platt_v003" },
          "recovery": { "active_event": false, "tau_min": null, "recovery_score": null },
          "physical_activity": { "activity_level": 1, "motion_energy": 0.42, "minutes_since_vigorous": 240 },
          "baseline_deviation": { "z_composite": 1.1, "layer": "work", "baseline_ready": true },
          "unknown": { "is_unknown": false, "reason": null }
        }
    """.trimIndent()

    private val unknownGolden = """
        {
          "schema_version": "1.0",
          "subject_id": "P0001",
          "window_id": 9,
          "window_end_timestamp": 1735689605000000,
          "signal_quality": {
            "sqi_eda": 0.30, "sqi_ppg": 0.20, "sqi_temp": 0.90, "sqi_imu": 0.10,
            "on_body": false,
            "repaired": { "eda": true, "ppg": false }
          },
          "physiological_arousal": { "activation": null, "trend_30min": "stable" },
          "stress_likelihood": { "value": null, "disambiguation_flags": ["post_exercise_arousal"] },
          "confidence": { "value": 0.40, "calibrated": false, "calibration_model_id": null },
          "recovery": { "active_event": false, "tau_min": null, "recovery_score": null },
          "physical_activity": { "activity_level": 3, "motion_energy": 1.80, "minutes_since_vigorous": 2 },
          "baseline_deviation": { "z_composite": null, "layer": "rest", "baseline_ready": false },
          "unknown": { "is_unknown": true, "reason": "OFF_BODY" }
        }
    """.trimIndent()

    @Test
    fun goldenOk_decodesExactly() {
        val result = AnsFrameDecoder.decode(okGolden)
        assertTrue(result.isSuccess)
        val frame = result.getOrThrow()
        assertEquals("1.0", frame.schemaVersion)
        assertEquals("P0001", frame.subjectId)
        assertEquals(0L, frame.windowId)
        assertEquals(1735689600000000L, frame.windowEndTimestamp)
        assertEquals(0.92, frame.signalQuality.sqiEda, 1e-9)
        assertEquals(0.88, frame.signalQuality.sqiPpg, 1e-9)
        assertEquals(0.95, frame.signalQuality.sqiTemp, 1e-9)
        assertEquals(0.91, frame.signalQuality.sqiImu, 1e-9)
        assertTrue(frame.signalQuality.onBody)
        assertEquals(mapOf("eda" to false, "ppg" to true), frame.signalQuality.repaired)
        assertEquals(62.5, frame.physiologicalArousal.activation!!, 1e-9)
        assertEquals("stable", frame.physiologicalArousal.trend30Min)
        assertEquals(0.34, frame.stressLikelihood.value!!, 1e-9)
        assertTrue(frame.stressLikelihood.disambiguationFlags.isEmpty())
        assertEquals(0.81, frame.confidence.value, 1e-9)
        assertTrue(frame.confidence.calibrated)
        assertEquals("platt_v003", frame.confidence.calibrationModelId)
        assertFalse(frame.recovery.activeEvent)
        assertNull(frame.recovery.tauMin)
        assertNull(frame.recovery.recoveryScore)
        assertEquals(1, frame.physicalActivity.activityLevel)
        assertEquals(0.42, frame.physicalActivity.motionEnergy, 1e-9)
        assertEquals(240.0, frame.physicalActivity.minutesSinceVigorous!!, 1e-9)
        assertEquals(1.1, frame.baselineDeviation.zComposite!!, 1e-9)
        assertEquals("work", frame.baselineDeviation.layer)
        assertTrue(frame.baselineDeviation.baselineReady)
        assertFalse(frame.unknown.isUnknown)
        assertNull(frame.unknown.reason)
    }

    @Test
    fun goldenUnknown_preservesNullNotZero() {
        val result = AnsFrameDecoder.decode(unknownGolden)
        assertTrue(result.isSuccess)
        val frame = result.getOrThrow()
        assertTrue(frame.unknown.isUnknown)
        assertEquals("OFF_BODY", frame.unknown.reason)
        // UNKNOWN 帧：生理量是 null（不是 0 / normal / low）
        assertNull(frame.physiologicalArousal.activation)
        assertNull(frame.stressLikelihood.value)
        assertNull(frame.recovery.recoveryScore)
        assertNull(frame.baselineDeviation.zComposite)
        // signal_quality 永远如实上报
        assertEquals(0.30, frame.signalQuality.sqiEda, 1e-9)
        assertFalse(frame.signalQuality.onBody)
        // 理由码在冻结集合内
        assertTrue(AnsObservationFrame.UNKNOWN_REASON_CODES.contains(frame.unknown.reason!!))
    }

    @Test
    fun allUnknownReasonCodes_areFrozen() {
        assertEquals(
            setOf(
                "SIGNAL_MOTION_COMPOUND",
                "DUAL_MODALITY_DOWN",
                "MODALITY_DOWN",
                "OFF_BODY",
                "CLOCK_FAULT",
                "LOW_CONFIDENCE",
                "CONTEXT_CONFLICT",
                "BASELINE_NOT_READY",
            ),
            AnsObservationFrame.UNKNOWN_REASON_CODES,
        )
    }

    @Test
    fun malformedInput_failsGracefully() {
        assertTrue(AnsFrameDecoder.decode("not json").isFailure)
        assertTrue(AnsFrameDecoder.decode("{}").isFailure)
    }

    @Test
    fun missingRequiredField_fails() {
        val json = org.json.JSONObject(okGolden)
        json.remove("signal_quality")
        assertTrue(AnsFrameDecoder.decode(json.toString()).isFailure)
    }

    @Test
    fun unknownExtraFields_ignored_forwardCompatible() {
        val json = org.json.JSONObject(okGolden)
        json.put("future_ans_field", "x")
        json.getJSONObject("confidence").put("future_confidence", 1)
        val result = AnsFrameDecoder.decode(json.toString())
        assertTrue(result.isSuccess)
        assertEquals(0.81, result.getOrThrow().confidence.value, 1e-9)
    }

    @Test
    fun missingOptionalFields_defaultsApply() {
        val json = org.json.JSONObject(okGolden)
        json.remove("window_id")
        json.getJSONObject("physical_activity").remove("minutes_since_vigorous")
        val frame = AnsFrameDecoder.decode(json.toString()).getOrThrow()
        assertNull(frame.windowId)
        assertNull(frame.physicalActivity.minutesSinceVigorous)
    }

    @Test
    fun nullValue_versusMissing_distinguished() {
        // null → Kotlin null；缺失可选 → null；缺失必填 → 失败。
        val withNullReason = org.json.JSONObject(okGolden)
        withNullReason.getJSONObject("unknown").put("reason", org.json.JSONObject.NULL)
        val frame = AnsFrameDecoder.decode(withNullReason.toString()).getOrThrow()
        assertNull(frame.unknown.reason)
        assertFalse(frame.unknown.isUnknown)

        val missingReason = org.json.JSONObject(okGolden)
        missingReason.getJSONObject("unknown").remove("reason")
        assertNotNull(AnsFrameDecoder.decode(missingReason.toString()).getOrThrow())
    }
}
