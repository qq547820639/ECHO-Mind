package com.yunjue.echo.mind.wearable.research

/**
 * AnsObservationMapper —— ANSWatch frame → ECHO 中立观察的映射（ANSWATCH_INTEGRATION_CONTRACT）。
 *
 * 映射原则（以 ANS 真实字段为准）：
 * - signal quality → ECHO evidence quality（SQI/on-body 如实保留）；
 * - physical activity → neutral wrist activity（activity_level 4 级 / motion_energy）；
 * - physiological activation → Research / validation-gated neutral observation（不直接进产品）；
 * - recovery → Research / validation-gated neutral observation；
 * - baseline deviation → Research / validation-gated neutral observation；
 * - UNKNOWN → ECHO ABSTENTION（端到端保留，绝不变成 0/normal/low）。
 *
 * 硬锁：stressLikelihood / 任何 affective 标签 → RESEARCH_ONLY，禁止影响
 * headline / Presence 颜色语义 / Memory / SelfModel / Journey Story / Notification /
 * Haptic / Action Recommendation / Intervention（AFFECTIVE_CONTRACT 冻结，本次不解锁）。
 */

/** ECHO 证据质量（UNKNOWN 保留）。 */
enum class AnsEvidenceQuality { GOOD, REVIEW, POOR, ABSTAINED }

/** 中立腕上活动（neutral wrist activity；不带任何心理标签）。 */
data class AnsNeutralActivity(
    val activityLevel: Int?,      // null = UNKNOWN（绝不用 0 冒充）
    val motionEnergy: Double?,    // null = UNKNOWN
    val minutesSinceVigorous: Double?,
)

/** 映射后的观察：只含中立/研究字段，且每类字段带 gating。 */
data class AnsMappedObservation(
    val quality: AnsEvidenceQuality,
    val neutralActivity: AnsNeutralActivity,
    /** Research-only：生理激活（validation-gated，不进产品语义）。 */
    val researchActivation: Double?,
    /** Research-only：stress likelihood（AFFECTIVE 硬锁）。 */
    val researchStressLikelihood: Double?,
    /** Research-only：recovery。 */
    val researchRecoveryScore: Double?,
    /** Research-only：baseline deviation z。 */
    val researchBaselineZ: Double?,
    val abstained: Boolean,
    val abstentionReason: String?,
    /** ANS 帧完整保留引用（审计/研究用；不进入 Memory/Journey 产品存储）。 */
    val sourceFrame: AnsObservationFrame,
)

object AnsObservationMapper {

    const val SQI_GATE_PASS = 0.70
    const val SQI_GATE_REJECT = 0.40

    fun map(frame: AnsObservationFrame): AnsMappedObservation {
        val sqi = frame.signalQuality
        val onBody = sqi.onBody

        val quality = when {
            frame.unknown.isUnknown -> AnsEvidenceQuality.ABSTAINED
            !onBody -> AnsEvidenceQuality.ABSTAINED // OFF_BODY：不把摘下当静止
            minOf(sqi.sqiEda, sqi.sqiPpg, sqi.sqiTemp, sqi.sqiImu) >= SQI_GATE_PASS -> AnsEvidenceQuality.GOOD
            minOf(sqi.sqiEda, sqi.sqiPpg) < SQI_GATE_REJECT -> AnsEvidenceQuality.POOR
            else -> AnsEvidenceQuality.REVIEW
        }

        val abstained = frame.unknown.isUnknown || !onBody
        // UNKNOWN 时生理量在 ANS 输出中为 null —— 端到端保留，绝不复算。
        val activityLevel = if (abstained) null else frame.physicalActivity.activityLevel
        val motionEnergy = if (abstained) null else frame.physicalActivity.motionEnergy

        return AnsMappedObservation(
            quality = quality,
            neutralActivity = AnsNeutralActivity(
                activityLevel = activityLevel,
                motionEnergy = motionEnergy,
                minutesSinceVigorous = frame.physicalActivity.minutesSinceVigorous,
            ),
            researchActivation = frame.physiologicalArousal.activation,
            researchStressLikelihood = frame.stressLikelihood.value,
            researchRecoveryScore = frame.recovery.recoveryScore,
            researchBaselineZ = frame.baselineDeviation.zComposite,
            abstained = abstained,
            abstentionReason = frame.unknown.reason,
            sourceFrame = frame,
        )
    }
}
