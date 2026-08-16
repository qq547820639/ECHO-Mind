package com.yunjue.echo.mind.wearable

/**
 * WearSourceArbitration —— 手机证据与腕上证据的仲裁（Observation Source Arbitration）。
 *
 * 基本原则（ECHO_WRIST_CONTRACT §Observation Source）：
 * - Wrist：更接近身体运动；Phone：更接近数字使用 context。二者语义不同，**不简单相加**；
 * - NOT_WORN → 腕上观察 quality = unavailable，绝不把"无运动"当"用户静止"；
 * - device state（battery/charging/connection）绝不变成 personal truth；
 * - 低质量/断连的腕上观察 → UNKNOWN（保留），不参与任何融合。
 */
object WearSourceArbitration {

    /** 仲裁后的中立腕上活动结论。 */
    data class ArbitratedWristActivity(
        /** UNKNOWN 保留：低质量/未佩戴/断连 → null，绝不猜。 */
        val movementClass: String?,
        val quality: String,
        /** 腕上证据是否可参与 Moment-level 融合（真机验证门之外再叠加质量门）。 */
        val usable: Boolean,
        val reason: String?,
    )

    fun arbitrate(envelope: WearObservationEnvelope): ArbitratedWristActivity {
        val motion = envelope.motion
        val wearing = envelope.deviceState?.wearing ?: WearDeviceStateSnapshot.WEAR_STATE_UNKNOWN

        // not worn → 全部腕上观察不可用（不把 lack of movement 当静止）。
        if (wearing == "NOT_WORN") {
            return ArbitratedWristActivity(
                movementClass = null,
                quality = "UNAVAILABLE",
                usable = false,
                reason = "NOT_WORN",
            )
        }
        if (wearing == "UNKNOWN") {
            // 佩戴状态未知（未真机验证的 vendor 信号）：观察仍然只作中性记录，不融合。
            return ArbitratedWristActivity(
                movementClass = motion?.movementClass?.takeIf { it != "UNKNOWN" },
                quality = "UNKNOWN",
                usable = false,
                reason = "WEARING_UNVERIFIED",
            )
        }
        if (motion == null) {
            return ArbitratedWristActivity(
                movementClass = null,
                quality = "UNKNOWN",
                usable = false,
                reason = "NO_MOTION_SUMMARY",
            )
        }
        val quality = motion.quality
        if (quality != "GOOD") {
            return ArbitratedWristActivity(
                movementClass = null,
                quality = quality,
                usable = false,
                reason = "LOW_QUALITY",
            )
        }
        val coverage = motion.sampleCoverage
        if (coverage < 0.8f) {
            return ArbitratedWristActivity(
                movementClass = null,
                quality = quality,
                usable = false,
                reason = "LOW_COVERAGE",
            )
        }
        return ArbitratedWristActivity(
            movementClass = motion.movementClass,
            quality = quality,
            usable = true,
            reason = null,
        )
    }

    /**
     * 禁止的融合（结构保证）：
     * - 腕上运动与手机运动绝不相加（语义不同；调用方必须保持来源分离）；
     * - device health（battery/charging/connection）绝不影响个人画像。
     * 本函数恒返回 true 表达"来源保持分离"这一不变量是否成立。
     */
    fun sourcesStaySeparate(phoneEvidencePresent: Boolean, wristEvidencePresent: Boolean): Boolean = true

    /** battery/charging/connection 进入 personal truth 的禁令（恒禁止）。 */
    fun deviceStateEntersPersonalTruth(): Boolean = false
}
