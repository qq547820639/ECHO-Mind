package com.yunjue.echo.mind.wearable.research

import org.json.JSONObject

/**
 * ANS_FRAME_V1 —— ANSWatch 真实输出契约的 Kotlin 镜像。
 *
 * 字段与语义**逐一对应** `integrations/answatch/ANS_FRAME_V1.schema.json`，
 * 该 schema 提取自 ANSWatch `wrist_pipeline/output_schema.py`（README/SPEC 单一事实源）。
 * 禁止自行"重新设计一个看起来相似的 schema"。
 *
 * 关键纪律：
 * - UNKNOWN 是正式答案：`unknown.is_unknown=true` 时 activation/stress/recovery 为 null；
 *   signal_quality 永远如实上报（不因 UNKNOWN 而省略）。
 * - null（JSON null）≠ 0 ≠ normal：解码必须端到端保留。
 */
data class AnsSignalQuality(
    val sqiEda: Double,
    val sqiPpg: Double,
    val sqiTemp: Double,
    val sqiImu: Double,
    val onBody: Boolean,
    val repaired: Map<String, Boolean> = emptyMap(),
)

data class AnsPhysiologicalArousal(
    val activation: Double?,
    val trend30Min: String = "stable",
)

data class AnsStressLikelihood(
    val value: Double?,
    val disambiguationFlags: List<String> = emptyList(),
)

data class AnsConfidence(
    val value: Double,
    val calibrated: Boolean,
    val calibrationModelId: String?,
)

data class AnsRecovery(
    val activeEvent: Boolean,
    val tauMin: Double?,
    val recoveryScore: Double?,
)

data class AnsPhysicalActivity(
    val activityLevel: Int,
    val motionEnergy: Double,
    val minutesSinceVigorous: Double?,
)

data class AnsBaselineDeviation(
    val zComposite: Double?,
    val layer: String,
    val baselineReady: Boolean,
)

data class AnsUnknown(
    val isUnknown: Boolean,
    val reason: String?,
)

data class AnsObservationFrame(
    val schemaVersion: String,
    val subjectId: String,
    val windowId: Long?,
    val windowEndTimestamp: Long,
    val signalQuality: AnsSignalQuality,
    val physiologicalArousal: AnsPhysiologicalArousal,
    val stressLikelihood: AnsStressLikelihood,
    val confidence: AnsConfidence,
    val recovery: AnsRecovery,
    val physicalActivity: AnsPhysicalActivity,
    val baselineDeviation: AnsBaselineDeviation,
    val unknown: AnsUnknown,
) {
    companion object {
        /** ANSWatch UNKNOWN 原因码（SPEC-2 §7.6 冻结值；必须端到端保留）。 */
        val UNKNOWN_REASON_CODES: Set<String> = setOf(
            "SIGNAL_MOTION_COMPOUND",
            "DUAL_MODALITY_DOWN",
            "MODALITY_DOWN",
            "OFF_BODY",
            "CLOCK_FAULT",
            "LOW_CONFIDENCE",
            "CONTEXT_CONFLICT",
            "BASELINE_NOT_READY",
        )
    }
}

/**
 * ANS_FRAME_V1 解码器（org.json）。
 * [DecodeFailure] 保留失败原因；null 与缺失区分：JSON null → Kotlin null，缺失必填 → 失败。
 */
object AnsFrameDecoder {

    data class DecodeFailure(val reason: String) : Exception(reason)

    fun decode(text: String): Result<AnsObservationFrame> {
        val root: JSONObject = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Result.failure(DecodeFailure("malformed JSON: ${e.message}"))
        }
        return try {
            Result.success(
                AnsObservationFrame(
                    schemaVersion = root.getString("schema_version"),
                    subjectId = root.getString("subject_id"),
                    windowId = root.optLongOrNull("window_id"),
                    windowEndTimestamp = root.getLong("window_end_timestamp"),
                    signalQuality = decodeSignalQuality(root.getJSONObject("signal_quality")),
                    physiologicalArousal = decodeArousal(root.getJSONObject("physiological_arousal")),
                    stressLikelihood = decodeStress(root.getJSONObject("stress_likelihood")),
                    confidence = decodeConfidence(root.getJSONObject("confidence")),
                    recovery = decodeRecovery(root.getJSONObject("recovery")),
                    physicalActivity = decodeActivity(root.getJSONObject("physical_activity")),
                    baselineDeviation = decodeBaseline(root.getJSONObject("baseline_deviation")),
                    unknown = decodeUnknown(root.getJSONObject("unknown")),
                )
            )
        } catch (e: Exception) {
            Result.failure(DecodeFailure("field error: ${e.message}"))
        }
    }

    private fun decodeSignalQuality(o: JSONObject): AnsSignalQuality {
        val repaired = mutableMapOf<String, Boolean>()
        o.optJSONObject("repaired")?.let { r ->
            val keys = r.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                repaired[k] = r.optBoolean(k)
            }
        }
        return AnsSignalQuality(
            sqiEda = o.getDouble("sqi_eda"),
            sqiPpg = o.getDouble("sqi_ppg"),
            sqiTemp = o.getDouble("sqi_temp"),
            sqiImu = o.getDouble("sqi_imu"),
            onBody = o.getBoolean("on_body"),
            repaired = repaired,
        )
    }

    private fun decodeArousal(o: JSONObject): AnsPhysiologicalArousal =
        AnsPhysiologicalArousal(
            activation = o.optDoubleOrNull("activation"),
            trend30Min = o.optString("trend_30min", "stable"),
        )

    private fun decodeStress(o: JSONObject): AnsStressLikelihood {
        val flags = mutableListOf<String>()
        o.optJSONArray("disambiguation_flags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                if (v is String) flags.add(v)
            }
        }
        return AnsStressLikelihood(
            value = o.optDoubleOrNull("value"),
            disambiguationFlags = flags,
        )
    }

    private fun decodeConfidence(o: JSONObject): AnsConfidence =
        AnsConfidence(
            value = o.getDouble("value"),
            calibrated = o.getBoolean("calibrated"),
            calibrationModelId = o.optStringOrNull("calibration_model_id"),
        )

    private fun decodeRecovery(o: JSONObject): AnsRecovery =
        AnsRecovery(
            activeEvent = o.getBoolean("active_event"),
            tauMin = o.optDoubleOrNull("tau_min"),
            recoveryScore = o.optDoubleOrNull("recovery_score"),
        )

    private fun decodeActivity(o: JSONObject): AnsPhysicalActivity =
        AnsPhysicalActivity(
            activityLevel = o.getInt("activity_level"),
            motionEnergy = o.getDouble("motion_energy"),
            minutesSinceVigorous = o.optDoubleOrNull("minutes_since_vigorous"),
        )

    private fun decodeBaseline(o: JSONObject): AnsBaselineDeviation =
        AnsBaselineDeviation(
            zComposite = o.optDoubleOrNull("z_composite"),
            layer = o.optString("layer", "rest"),
            baselineReady = o.getBoolean("baseline_ready"),
        )

    private fun decodeUnknown(o: JSONObject): AnsUnknown =
        AnsUnknown(
            isUnknown = o.getBoolean("is_unknown"),
            reason = o.optStringOrNull("reason"),
        )
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name) else null

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null
