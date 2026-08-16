package com.yunjue.echo.mind.wearable.research

/**
 * AnsPromotionPolicy —— ANSWatch 证据进入产品的晋升门（ANSWATCH_INTEGRATION_CONTRACT）。
 *
 * 默认：**任何 ANS 输出都不直接进入产品语义**。
 * - stress/affective：AFFECTIVE_CONTRACT 冻结，硬锁 RESEARCH_ONLY，本次绝不解锁；
 * - neutral activity：validation-gated（真机 + SQI 标定 + baseline 就绪后才可作中性腕上活动证据）；
 * - recovery / baseline deviation：validation-gated；
 * - UNKNOWN：永不晋升，abstention 端到端保留；
 * - lab benchmark（WESAD AUROC 等）永不变成产品宣称。
 */
object AnsPromotionPolicy {

    /** AFFECTIVE 硬锁：恒 false（AFFECTIVE_CONTRACT §8/§9/§10 未满足前不可能翻转）。 */
    fun affectivePromotionAllowed(): Boolean = false

    /**
     * 中性腕上活动证据的晋升门（全部满足才允许，缺一不可）：
     * - 硬件验证：ANS 真机 clock/on-body/SQI/missingness/motion artifact 已验证
     *   （[ValidationGates.hardwareVerified]；本环境无硬件 → false）；
     * - SQI 标定：设备 SQI 阈值已完成设备级标定（[ValidationGates.sqiCalibratedForDevice]）；
     * - baseline 就绪：[AnsObservationFrame.baselineDeviation.baselineReady]；
     * - 未 abstain、on-body、SQI ≥ PASS 门。
     */
    fun canPromoteNeutralActivity(mapped: AnsMappedObservation, gates: ValidationGates): Boolean {
        if (mapped.abstained) return false
        if (!gates.hardwareVerified) return false
        if (!gates.sqiCalibratedForDevice) return false
        if (!mapped.sourceFrame.baselineDeviation.baselineReady) return false
        if (mapped.quality != AnsEvidenceQuality.GOOD) return false
        return mapped.sourceFrame.signalQuality.onBody
    }

    /** 晋升许可（结构上只允许中性活动证据；其他全部 research-only）。 */
    fun promotionDecision(mapped: AnsMappedObservation, gates: ValidationGates): AnsPromotionDecision {
        val neutralActivityPromoted = canPromoteNeutralActivity(mapped, gates)
        return AnsPromotionDecision(
            neutralActivityPromoted = neutralActivityPromoted,
            activationPromoted = false,
            stressPromoted = false,
            recoveryPromoted = false,
            baselinePromoted = false,
        )
    }

    /** 外部验证门（本环境无 ANS 硬件 → 默认全部 false；真机验证后由运营配置打开）。 */
    data class ValidationGates(
        val hardwareVerified: Boolean = false,
        val sqiCalibratedForDevice: Boolean = false,
    )
}

data class AnsPromotionDecision(
    val neutralActivityPromoted: Boolean,
    val activationPromoted: Boolean,
    val stressPromoted: Boolean,
    val recoveryPromoted: Boolean,
    val baselinePromoted: Boolean,
)
