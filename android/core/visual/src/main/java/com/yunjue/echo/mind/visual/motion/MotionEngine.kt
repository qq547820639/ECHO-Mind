package com.yunjue.echo.mind.visual.motion

/**
 * MotionEngine — 统一动画系统（ECHO_INTERACTION_CONTRACT / SURFACE_POLICY §四）。
 *
 * 核心约束：
 * - **Presence 更新频率与渲染帧钟分离**：状态分钟级重算，渲染帧级驱动；
 *   本引擎只把「帧钟时间」转译为各动画通道的相位，不重新推理用户状态。
 * - **Reduced Motion 是独立 policy，非静态截图**：大幅降低粒子/轨道，保留低频呼吸与轻微亮度漂移。
 * - **Battery Saver / thermal** 通过降低 visual complexity（粒子/filament 上限）实现，
 *   **永不改变 Identity**。
 *
 * 动画通道（全部确定性相位函数，输入 clockSeconds + genome 节奏参数）：
 * breathing / drift / orbital / filamentFlow / particleMigration / microResponse / sceneTransition / morph。
 */

/** 动画通道的相位集合（0..1 或弧度；renderer 消费）。 */
data class MotionPhase(
    /** 呼吸相位 -1..1（核心缩放/膜起伏）。 */
    val breathing: Float,
    /** 漂移相位 -1..1（超低频整体漂移）。 */
    val drift: Float,
    /** 轨道相位（弧度；轨道旋转）。 */
    val orbital: Float,
    /** filament 流动相位 -1..1。 */
    val filamentFlow: Float,
    /** 粒子迁移相位 -1..1。 */
    val particleMigration: Float,
    /** 环境亮度漂移 -1..1（极慢；Reduced Motion 下仍保留）。 */
    val luminanceDrift: Float,
)

/** 动效等级（决定各通道幅度缩放；不影响 identity）。 */
enum class MotionPolicy {
    /** 默认。 */
    FULL,

    /** 减少动画（无障碍）：粒子/轨道大幅降，保留低频呼吸 + 轻微亮度漂移。 */
    REDUCED_MOTION,

    /** 低功耗/热压：复杂度降低（更少的通道幅度），identity 不变。 */
    LOW_POWER,
}

object MotionEngine {

    private const val TWO_PI = 2f * Math.PI.toFloat()

    /**
     * 由帧钟 + 节奏参数 + policy 计算所有通道相位（确定性）。
     * @param clockSeconds 渲染帧钟（秒）
     * @param pulseRate 呼吸周期（秒）
     * @param driftRate 漂移速率 0..1
     * @param policy 动效等级
     */
    fun phaseAt(
        clockSeconds: Float,
        pulseRate: Float,
        driftRate: Float,
        policy: MotionPolicy,
    ): MotionPhase {
        val t = clockSeconds
        val period = pulseRate.coerceAtLeast(1f)

        // 呼吸：所有 policy 都保留（Reduced Motion 也保留低频呼吸）
        val breatheAmp = when (policy) {
            MotionPolicy.FULL -> 1f
            MotionPolicy.REDUCED_MOTION -> 0.5f
            MotionPolicy.LOW_POWER -> 0.7f
        }
        val breathing = kotlin.math.sin(t / period * TWO_PI) * breatheAmp

        // 漂移/轨道/粒子：REDUCED_MOTION 大幅降（近 0），LOW_POWER 适度降
        val moveAmp = when (policy) {
            MotionPolicy.FULL -> 1f
            MotionPolicy.REDUCED_MOTION -> 0.08f
            MotionPolicy.LOW_POWER -> 0.4f
        }
        val driftPeriod = 30f - driftRate.coerceIn(0f, 1f) * 20f
        val drift = kotlin.math.sin(t / driftPeriod * TWO_PI) * moveAmp
        val orbital = t * driftRate * 0.1f * moveAmp
        val filamentFlow = kotlin.math.sin(t * 0.5f) * moveAmp
        val particleMigration = kotlin.math.sin(t * 0.7f) * moveAmp

        // 亮度漂移：极慢，Reduced Motion 仍保留轻微（非静态截图）
        val lumDrift = kotlin.math.sin(t / 45f * TWO_PI) * 0.5f

        return MotionPhase(
            breathing = breathing,
            drift = drift,
            orbital = orbital,
            filamentFlow = filamentFlow,
            particleMigration = particleMigration,
            luminanceDrift = lumDrift,
        )
    }
}
