package com.yunjue.echo.mind.visual.surface

import com.yunjue.echo.mind.visual.model.EchoVisualGenome

/**
 * WristVisualSpec — 腕上低维视觉参数（§18 SAME ECHO / SECOND BODY）。
 *
 * 手机高维 [EchoVisualGenome] → deterministic downsample → 腕上低维参数。
 * Vela 用 few shapes / slow organic movement；不复制完整手机 renderer。
 *
 * 边界（ECHO_WRIST_CONTRACT）：PUBLIC_SAFE Presence；无 Memory / SelfModel / Journey / Provider；
 * 断连保留 Identity、Moment → QUIET，不显示 ERROR。
 */
data class WristVisualSpec(
    /** identity 种子子集（腕上 renderer 的确定性随机源）。 */
    val identitySeedSubset: Long,
    /** 色相参数 0..1（主光谱蓝→紫内；与手机同一 identity）。 */
    val hueParam: Float,
    /** 形状模式 0..3（由 texture/拓扑派生的少数形状族）。 */
    val shapeMode: Int,
    /** 强度 0..1（核心亮度/开放度）。 */
    val intensity: Float,
    /** 脉冲相位 0..1（呼吸同步相位，与手机同源）。 */
    val pulsePhase: Float,
    /** 漂移相位 0..1（慢漂移同步相位）。 */
    val driftPhase: Float,
    /** 隐私位（true = PUBLIC_SAFE，腕上恒 true）。 */
    val privacy: Boolean,
    /** spec 修订号（双端兼容锚点）。 */
    val revision: Int = CURRENT_REVISION,
) {
    companion object {
        const val CURRENT_REVISION: Int = 1
    }
}

/** 腕上投影器（deterministic downsample；纯函数，双端一致）。 */
object WristVisualProjector {

    /** 手机 genome → 腕上低维 spec（identity 保留，moment 降维）。 */
    fun downsample(genome: EchoVisualGenome): WristVisualSpec = WristVisualSpec(
        identitySeedSubset = genome.identitySeed and 0xFFFFFF, // 低 24 位作腕上随机源
        hueParam = genome.spectralBias.coerceIn(0f, 1f),
        shapeMode = (genome.identityTopology * 3.99f).toInt().coerceIn(0, 3),
        intensity = genome.coreIntensity.coerceIn(0f, 1f),
        pulsePhase = frac(genome.identityPhase + genome.dayComposition),
        driftPhase = frac(genome.seasonPhase),
        privacy = true,
    )

    /** 断连/stale → QUIET 态 spec（保留 identity，moment 归零，不显示 ERROR）。 */
    fun quietFallback(genome: EchoVisualGenome): WristVisualSpec =
        downsample(genome).copy(intensity = genome.coreIntensity * 0.5f)

    private fun frac(v: Float): Float = v - kotlin.math.floor(v)
}
