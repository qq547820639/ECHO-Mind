package com.yunjue.echo.mind.actions

/**
 * ERA 9 — Intervention Policy（主动能力分级，Master Prompt PART 45）。
 *
 * 默认 L0-L1（视觉 / 打开时解释）。任何主动建议与通知都必须：
 * 用户 opt-in + 极低频 + 有明确价值 + 不制造焦虑 + 不使用低置信度推测。
 * 纯函数（JVM 可测）。
 */
enum class InterventionLevel {
    /** 仅视觉（ECHO 的默认存在方式）。 */
    L0_VISUAL_ONLY,

    /** 用户打开时解释（一句话 + 为什么）。 */
    L1_EXPLAIN_WHEN_OPENED,

    /** 用户打开时给建议（想要慢一点？）。 */
    L2_SUGGEST_WHEN_OPENED,

    /** 主动通知（必须 opt-in + 高频置信 + 低频次）。 */
    L3_PROACTIVE_NOTIFICATION,
}

data class InterventionInputs(
    /** 当前状态置信度（0..1）。 */
    val confidence: Float,
    /** Ambient 是否已知（UNKNOWN = 不干预）。 */
    val ambientKnown: Boolean,
    /** 用户是否开启「打开时建议」。 */
    val suggestionsEnabled: Boolean = false,
    /** 用户是否开启「主动通知」（L3 opt-in）。 */
    val proactiveOptIn: Boolean = false,
    /** 上次主动通知时间（0 = 从未）。 */
    val lastProactiveAtMs: Long = 0L,
    /** 当前时间。 */
    val nowMs: Long,
)

object InterventionPolicy {

    /** L2 建议的最低置信度。 */
    const val SUGGEST_MIN_CONFIDENCE = 0.7f

    /** L3 主动通知的最低置信度。 */
    const val PROACTIVE_MIN_CONFIDENCE = 0.85f

    /** L3 最小间隔（默认 7 天；绝不因用户没互动而频繁打扰）。 */
    const val PROACTIVE_MIN_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000L

    /** 解析当前允许的干预级别（判定顺序即优先级，铁律见类注释）。 */
    fun resolve(inputs: InterventionInputs): InterventionLevel {
        // 低置信度 / 状态未知：只允许视觉存在（禁止语言、建议、通知、强记忆）
        if (!inputs.ambientKnown || inputs.confidence < SUGGEST_MIN_CONFIDENCE) {
            return InterventionLevel.L0_VISUAL_ONLY
        }
        // L3：opt-in + 高置信 + 低频（间隔不足降级到 L2）
        if (inputs.proactiveOptIn && inputs.confidence >= PROACTIVE_MIN_CONFIDENCE &&
            (inputs.lastProactiveAtMs <= 0L ||
                inputs.nowMs - inputs.lastProactiveAtMs >= PROACTIVE_MIN_INTERVAL_MS)
        ) {
            return InterventionLevel.L3_PROACTIVE_NOTIFICATION
        }
        // L2：用户开启建议 + 中高置信
        if (inputs.suggestionsEnabled) {
            return InterventionLevel.L2_SUGGEST_WHEN_OPENED
        }
        // 默认：打开时解释（L1）
        return InterventionLevel.L1_EXPLAIN_WHEN_OPENED
    }
}
