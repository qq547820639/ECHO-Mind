package com.yunjue.echo.mind.visual.model

import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.visual.math.DeterministicRandom

/**
 * VisualGenomeCompiler — EchoVisualParameters → EchoVisualGenome（V3 §H/§J）。
 *
 * **机械编译器**：[EchoVisualParameters] 已经是 canonical 语义值，本编译器只做
 * 参数 → genome 字段映射与 identity 元数据透传；
 * **禁止**从 rhythm/behavior/confidence/maturity 重新推导
 * brightness/turbulence/density/core openness（那是 presence 映射层的职责）。
 *
 * 诚实边界：**contrast / accentIntensity / structureComplexity 当前不被视觉链消费**
 * （genome 无对应字段，渲染链不读取）——三参数保留为 canonical 语义面（上游 presence/
 * journey 继续填充，未来材质层可接线）；除三者外其余参数一一映射进 genome 字段。
 */
object VisualGenomeCompiler {

    private const val SALT_PHASE = 7

    /**
     * @param params   canonical 语义参数（presence 映射层产出）
     * @param identity 恒定身份层（seed/拓扑/光谱/轨道；来自 EchoPresenceState.identityGenome
     *                 或 [neutralIdentity]）
     */
    fun compile(params: EchoVisualParameters, identity: EchoIdentityGenome): EchoVisualGenome {
        val seed = identity.seed
        return EchoVisualGenome(
            identitySeed = seed,
            identityTopology = identity.coreTopology.coerceIn(0f, 1f),
            identityPhase = frac(DeterministicRandom.mix(seed, SALT_PHASE)),
            seasonPhase = params.seasonPhase.coerceIn(0f, 1f),
            dayComposition = params.dayComposition.coerceIn(0f, 1f),
            coherence = params.coherence.coerceIn(0f, 1f),
            radialSpread = params.dispersion.coerceIn(0f, 1f),
            orbitalEccentricity = identity.orbitGeometry.coerceIn(0f, 1f),
            particleDensity = params.particleDensity.coerceIn(0f, 1f),
            filamentDensity = params.filamentDensity.coerceIn(0f, 1f),
            driftRate = params.flowSpeed.coerceIn(0f, 1f),
            pulseRate = params.pulsePeriodSeconds,
            turbulence = params.turbulence.coerceIn(0f, 1f),
            luminance = params.brightness.coerceIn(0f, 1f),
            spectralBias = identity.accentHue.coerceIn(0f, 1f),
            coreIntensity = params.coreOpenness.coerceIn(0f, 1f),
            haloIntensity = params.haloIntensity.coerceIn(0f, 1f),
            dataClarity = params.dataClarity.coerceIn(0f, 1f),
            depth = params.depth.coerceIn(0f, 1f),
            momentIntensity = params.momentIntensity.coerceIn(0f, 1f),
            revision = EchoVisualGenome.CURRENT_REVISION,
        )
    }

    /**
     * 中性恒定身份（canonical 单一定义）：历史数据缺 identity traits 时（如 Journey
     * 早期快照）使用的稳定身份——同一 seed 恒定同一结果，跨天可辨同一 ECHO。
     * 禁止在其他位置另设 hardcoded identityTopology。
     */
    fun neutralIdentity(seed: Long): EchoIdentityGenome = EchoIdentityGenome(
        seed = seed,
        coreTopology = 0.6f,
        orbitGeometry = 0.45f,
        motionPersonality = 0.5f,
        symmetryTendency = 0.5f,
    )

    private fun frac(v: Long): Float = (v ushr 40 and 0xFFFFFF).toFloat() / 16777215f
}
