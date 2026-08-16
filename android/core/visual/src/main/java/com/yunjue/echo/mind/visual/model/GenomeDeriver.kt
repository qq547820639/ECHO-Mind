package com.yunjue.echo.mind.visual.model

import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.visual.math.DeterministicRandom

/**
 * GenomeDeriver — EchoPresenceState → EchoVisualGenome（确定性纯函数）。
 *
 * 这是 ECHO_VISUAL_SEMANTICS.md 映射表的代码实现，**唯一事实源**。
 * 仅消费 presence 的四个时间尺度层（identity/season/daily/moment）与 confidence，
 * 不接触任何 Observation 原始特征；不含心理语义。
 */
object GenomeDeriver {

    private const val TWO_PI = 2f * Math.PI.toFloat()

    /** 由 presence + 昼夜时刻派生 genome（同输入同输出）。 */
    fun derive(state: EchoPresenceState, hourOfDay: Float): EchoVisualGenome {
        val id = state.identityGenome
        val daily = state.dailyComposition
        val moment = state.momentState
        val season = state.lifeSeason
        val seed = id.seed

        // identity 层：恒定（拓扑/相位/光谱偏置只依赖 seed 与 genome）
        val identityTopology = id.coreTopology.coerceIn(0f, 1f)
        val identityPhase = frac(DeterministicRandom.mix(seed, SALT_PHASE))
        val spectralBias = id.accentHue.coerceIn(0f, 1f)

        // season 层：数周/月慢漂移 → 相位
        val seasonPhase = frac(season.phaseIndex * 0.25f + season.drift * 0.5f)

        // daily 层：当日构图指纹（由 daily 各维混合；一天内不漂移）
        val dayComposition = frac(
            daily.flowSpeed * 0.3f + daily.coherence * 0.3f + daily.turbulence * 0.4f,
        )

        val coherence = daily.coherence.coerceIn(0f, 1f)
            .let { if (it > 0f) it else state.confidence.coerceIn(0f, 1f) }
        val turbulence = daily.turbulence.coerceIn(0f, 1f)
            .let { if (it > 0f) it else state.behaviorState.deviation.coerceIn(0f, 1f) }
        val density = daily.particleDensity.coerceIn(0f, 1f)
            .let { if (it > 0f) it else (state.behaviorState.density * 0.9f + 0.1f).coerceIn(0f, 1f) }

        // data coverage → 清晰度（ RhythmState.coverage；低覆盖更轻更模糊）
        val dataClarity = state.rhythmState.coverage.coerceIn(0f, 1f)
            .let { if (it > 0f) it else (0.3f + state.confidence * 0.7f).coerceIn(0f, 1f) }

        return EchoVisualGenome(
            identitySeed = seed,
            identityTopology = identityTopology,
            identityPhase = identityPhase,
            seasonPhase = seasonPhase,
            dayComposition = dayComposition,
            coherence = coherence,
            radialSpread = daily.dispersion.coerceIn(0f, 1f)
                .let { if (it > 0f) it else 0.5f },
            orbitalEccentricity = id.orbitGeometry.coerceIn(0f, 1f),
            particleDensity = density,
            filamentDensity = (0.3f + id.textureFamily * 0.15f + coherence * 0.3f).coerceIn(0f, 1f),
            driftRate = daily.flowSpeed.coerceIn(0f, 1f)
                .let { if (it > 0f) it else state.rhythmState.activityLevel.coerceIn(0f, 1f) },
            pulseRate = moment.breathingPeriod.takeIf { it > 0f }
                ?: daily.pulsePeriod.takeIf { it > 0f }
                ?: (5.6f - state.rhythmState.activityLevel * 1.8f).coerceIn(3.6f, 6f),
            turbulence = turbulence,
            luminance = (dayBrightness(hourOfDay) * (0.55f + state.rhythmState.activityLevel * 0.45f))
                .coerceIn(0.12f, 1f),
            spectralBias = spectralBias,
            coreIntensity = daily.coreOpenness.coerceIn(0f, 1f)
                .let { if (it > 0f) it else maturityOpenness(state.maturity.name) },
            haloIntensity = (0.3f + coherence * 0.6f).coerceIn(0f, 1f),
            dataClarity = dataClarity,
            momentIntensity = moment.noiseScale.coerceIn(0f, 1f),
            revision = EchoVisualGenome.CURRENT_REVISION,
        )
    }

    /** 成熟度 → 核心开放度（与 presence 模块 maturityOpenness 同语义，避免反向依赖）。 */
    private fun maturityOpenness(maturityName: String): Float = when (maturityName) {
        "SEED" -> 0.15f
        "DISCOVERING" -> 0.3f
        "EMERGING" -> 0.5f
        "KNOWN" -> 0.75f
        "MATURE" -> 0.9f
        else -> 0.15f
    }

    /** 昼夜亮度曲线（与 presence dayBrightnessCurve 同语义）。 */
    private fun dayBrightness(hourOfDay: Float): Float {
        val h = (hourOfDay % 24f + 24f) % 24f
        return when {
            h < 6f -> 0.35f + h / 6f * 0.05f
            h < 10f -> 0.4f + (h - 6f) / 4f * 0.45f
            h < 17f -> 0.85f
            h < 22f -> 0.85f - (h - 17f) / 5f * 0.4f
            else -> 0.45f - (h - 22f) / 2f * 0.1f
        }
    }

    private fun frac(v: Float): Float = v - kotlin.math.floor(v)

    private fun frac(v: Long): Float {
        val u = (v ushr 40 and 0xFFFFFF).toFloat() / 16777215f
        return u
    }

    private const val SALT_PHASE = 7
}
