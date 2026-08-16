package com.yunjue.echo.mind.visual.motion

import com.yunjue.echo.mind.visual.render.EchoInteractionSpec
import com.yunjue.echo.mind.visual.render.EchoMotionSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * MotionEvaluator — V3 §25 全 Surface 共享运动求值器。
 *
 * 铁律：
 * - ECHO motion 是**时间的纯函数**：同一 absolute monotonic time → 同一运动状态；
 *   App / Wallpaper / Dream / Wrist 不各自发明时间数学（禁止 Compose InfiniteTransition
 *   与 Wallpaper 各自一套）。
 * - 帧层只做插值（§26）：本求值器不重算任何 Presence/Identity/Daily 语义。
 * - Reduced Motion / Dream / LOW_POWER 的精确系数已在 [EchoMotionSpec] 编译期展开，
 *   这里只按参数求值。
 */

/** 一帧的运动状态（renderer 消费）。 */
data class EchoMotionState(
    /** 呼吸缩放（1 ± amplitude；b(t) = .5 - .5·cos(2πt/period)）。 */
    val breathScale: Float,
    /** 全局轨道旋转（弧度；主轨道 21–60 分钟一整圈，不会像 magic ball 快转）。 */
    val globalRotation: Float,
    /** filament 内部相位（弧度；26–58s 周期）。 */
    val filamentPhase: Float,
    /** 粒子场旋转（弧度；比轨道更慢的独立迁移）。 */
    val particleRotation: Float,
    /** halo 亮度乘数（≤±3% 脉冲；不能像 loading spinner）。 */
    val haloMultiplier: Float,
    /** 交互包络 0..1（transient；由交互侧按 §29 时间线产生）。 */
    val interactionEnvelope: Float,
)

object MotionEvaluator {

    private const val TWO_PI = 2f * PI.toFloat()

    /** 由运动语义参数 + 绝对时间（秒）求值（确定性）。 */
    fun evaluate(motion: EchoMotionSpec, timeSeconds: Float): EchoMotionState {
        val t = timeSeconds
        val period = motion.breathPeriodSeconds.coerceAtLeast(1f)
        // b(t) = .5 - .5·cos(2πt/period)；breathScale = 1 - amp·cos(...)（振幅即峰值偏差）
        val breathScale = 1f - motion.breathAmplitude * cos(t / period * TWO_PI)
        val globalRotation = TWO_PI * t / motion.orbitPeriodSeconds.coerceAtLeast(60f) *
            motion.orbitVelocity
        val filamentPhase = TWO_PI * t / motion.filamentPhaseSeconds.coerceAtLeast(4f) *
            motion.filamentPhaseScale
        val particleRotation = TWO_PI * t / (motion.orbitPeriodSeconds.coerceAtLeast(60f) * 1.7f) *
            motion.particleVelocity
        val haloMultiplier = 1f + motion.brightnessPulse * sin(t / period * TWO_PI + PI.toFloat() / 2f)
        return EchoMotionState(
            breathScale = breathScale,
            globalRotation = globalRotation,
            filamentPhase = filamentPhase,
            particleRotation = particleRotation,
            haloMultiplier = haloMultiplier,
            interactionEnvelope = 0f,
        )
    }

    /** 带交互包络的求值（touch 只改变 transient renderer interaction，§29）。 */
    fun evaluate(
        motion: EchoMotionSpec,
        timeSeconds: Float,
        interaction: EchoInteractionSpec,
    ): EchoMotionState = evaluate(motion, timeSeconds).let {
        if (interaction.active) it.copy(interactionEnvelope = interaction.envelope) else it
    }

    /**
     * §26 参数趋近：expApproach(start, target, t, tau)。
     * Moment tau 2.8s / Daily 7.5s / Season 18s / resume convergence 6s / 无 provenance 5.5s。
     */
    fun expApproach(start: Float, target: Float, elapsedSeconds: Float, tau: Float): Float {
        val k = 1f - kotlin.math.exp(-elapsedSeconds / tau.coerceAtLeast(0.05f))
        return start + (target - start) * k
    }

    /**
     * §45 Correction 视觉反馈（约 900ms）：
     * - halo -8%（包络先降后回）；- filament phase 暂停 150ms 后平滑 converge。
     * Identity 不改变。返回 (haloDelta 乘数修正, filamentPhase 暂停秒数)。
     */
    fun correctionPulse(sinceMs: Long): Pair<Float, Float> {
        if (sinceMs < 0L || sinceMs > 900L) return 0f to 0f
        val t = sinceMs / 900f
        // halo：先 -8% 再回（单峰正弦包络）
        val haloDelta = -0.08f * sin(t * PI.toFloat())
        // phase 暂停：前 150ms 全停，150–900ms 平滑 converge（暂停量衰减到 0）
        val pause = if (sinceMs < 150L) {
            sinceMs / 1000f
        } else {
            0.15f * (1f - (sinceMs - 150L) / 750f)
        }
        return haloDelta to pause
    }

    /** §29 交互包络时间线（ms）：0–80 capture → 80–180 rise → 180–600 peak/decay → 600–1150 return。 */
    fun interactionEnvelope(sinceTouchMs: Long): Float = when {
        sinceTouchMs < 0L -> 0f
        sinceTouchMs < 80L -> 0f
        sinceTouchMs < 180L -> (sinceTouchMs - 80L) / 100f
        sinceTouchMs < 600L -> 1f - (sinceTouchMs - 180L) / 420f * 0.35f
        sinceTouchMs < 1150L -> 0.65f * (1f - (sinceTouchMs - 600L) / 550f)
        else -> 0f
    }
}
