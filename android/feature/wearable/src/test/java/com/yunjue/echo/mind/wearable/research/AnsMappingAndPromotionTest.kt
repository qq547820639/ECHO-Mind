package com.yunjue.echo.mind.wearable.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST — ANS Mapping / Promotion / Affective Hard Lock。
 */
class AnsMappingAndPromotionTest {

    private fun okFrame(onBody: Boolean = true): AnsObservationFrame = AnsObservationFrame(
        schemaVersion = "1.0",
        subjectId = "P0001",
        windowId = 0L,
        windowEndTimestamp = 1_000_000L,
        signalQuality = AnsSignalQuality(
            sqiEda = 0.92, sqiPpg = 0.88, sqiTemp = 0.95, sqiImu = 0.91,
            onBody = onBody,
            repaired = mapOf("eda" to false, "ppg" to false),
        ),
        physiologicalArousal = AnsPhysiologicalArousal(activation = 62.5),
        stressLikelihood = AnsStressLikelihood(value = 0.34),
        confidence = AnsConfidence(value = 0.81, calibrated = true, calibrationModelId = "platt_v003"),
        recovery = AnsRecovery(activeEvent = false, tauMin = null, recoveryScore = null),
        physicalActivity = AnsPhysicalActivity(activityLevel = 1, motionEnergy = 0.42, minutesSinceVigorous = 240.0),
        baselineDeviation = AnsBaselineDeviation(zComposite = 1.1, layer = "work", baselineReady = true),
        unknown = AnsUnknown(isUnknown = false, reason = null),
    )

    private fun unknownFrame(reason: String): AnsObservationFrame = okFrame().copy(
        signalQuality = AnsSignalQuality(0.3, 0.2, 0.9, 0.1, onBody = false),
        physiologicalArousal = AnsPhysiologicalArousal(activation = null),
        stressLikelihood = AnsStressLikelihood(value = null, disambiguationFlags = listOf("post_exercise_arousal")),
        recovery = AnsRecovery(activeEvent = false, tauMin = null, recoveryScore = null),
        baselineDeviation = AnsBaselineDeviation(zComposite = null, layer = "rest", baselineReady = false),
        unknown = AnsUnknown(isUnknown = true, reason = reason),
    )

    @Test
    fun goodFrame_mapsNeutralActivityAndQuality() {
        val mapped = AnsObservationMapper.map(okFrame())
        assertEquals(AnsEvidenceQuality.GOOD, mapped.quality)
        assertEquals(1, mapped.neutralActivity.activityLevel)
        assertEquals(0.42, mapped.neutralActivity.motionEnergy!!, 1e-9)
        assertEquals(240.0, mapped.neutralActivity.minutesSinceVigorous!!, 1e-9)
        assertFalse(mapped.abstained)
        assertNull(mapped.abstentionReason)
    }

    @Test
    fun offBodyFrame_abstainsNotZero() {
        val mapped = AnsObservationMapper.map(unknownFrame("OFF_BODY"))
        assertTrue(mapped.abstained)
        assertEquals("OFF_BODY", mapped.abstentionReason)
        assertEquals(AnsEvidenceQuality.ABSTAINED, mapped.quality)
        // 不把 not worn 当静止：activity 为 null，不是 0。
        assertNull(mapped.neutralActivity.activityLevel)
        assertNull(mapped.neutralActivity.motionEnergy)
    }

    @Test
    fun unknownEveryReasonCode_abstains() {
        for (reason in AnsObservationFrame.UNKNOWN_REASON_CODES) {
            val mapped = AnsObservationMapper.map(unknownFrame(reason))
            assertTrue("reason $reason must abstain", mapped.abstained)
            assertNull(mapped.neutralActivity.activityLevel)
        }
    }

    @Test
    fun stressLikelihood_staysResearchOnly() {
        // stress 值照常解码（研究用），但绝不进产品语义：mapper 只放 research 字段。
        val mapped = AnsObservationMapper.map(okFrame())
        assertEquals(0.34, mapped.researchStressLikelihood!!, 1e-9)
        assertEquals(62.5, mapped.researchActivation!!, 1e-9)
    }

    @Test
    fun affectiveHardLock_cannotBePromoted() {
        assertFalse(AnsPromotionPolicy.affectivePromotionAllowed())
        val gates = AnsPromotionPolicy.ValidationGates(hardwareVerified = true, sqiCalibratedForDevice = true)
        val decision = AnsPromotionPolicy.promotionDecision(AnsObservationMapper.map(okFrame()), gates)
        assertFalse(decision.stressPromoted)
        assertFalse(decision.activationPromoted)
        assertFalse(decision.recoveryPromoted)
        assertFalse(decision.baselinePromoted)
    }

    @Test
    fun neutralActivityPromotion_requiresAllGates() {
        val mapped = AnsObservationMapper.map(okFrame())
        // 无硬件验证 → 不晋升
        assertFalse(
            AnsPromotionPolicy.canPromoteNeutralActivity(
                mapped,
                AnsPromotionPolicy.ValidationGates(hardwareVerified = false, sqiCalibratedForDevice = false),
            ),
        )
        // 无 SQI 标定 → 不晋升
        assertFalse(
            AnsPromotionPolicy.canPromoteNeutralActivity(
                mapped,
                AnsPromotionPolicy.ValidationGates(hardwareVerified = true, sqiCalibratedForDevice = false),
            ),
        )
        // 全门通过 + GOOD + on-body + baseline ready → 晋升（中性活动证据）
        assertTrue(
            AnsPromotionPolicy.canPromoteNeutralActivity(
                mapped,
                AnsPromotionPolicy.ValidationGates(hardwareVerified = true, sqiCalibratedForDevice = true),
            ),
        )
    }

    @Test
    fun abstainedFrame_neverPromoted() {
        val mapped = AnsObservationMapper.map(unknownFrame("MODALITY_DOWN"))
        assertFalse(
            AnsPromotionPolicy.canPromoteNeutralActivity(
                mapped,
                AnsPromotionPolicy.ValidationGates(hardwareVerified = true, sqiCalibratedForDevice = true),
            ),
        )
    }

    @Test
    fun labBenchmarkNumbers_areNotProductClaims() {
        // WESAD AUROC 0.9029 等 lab benchmark 不出现在 promotion 决策里：
        // 决策只看 validation gates（硬件/SQI/baseline），没有任何模型分数门槛。
        val mapped = AnsObservationMapper.map(okFrame())
        val gates = AnsPromotionPolicy.ValidationGates(hardwareVerified = true, sqiCalibratedForDevice = true)
        assertTrue(AnsPromotionPolicy.canPromoteNeutralActivity(mapped, gates))
    }
}
