package com.yunjue.echo.mind.visual.noise

import com.yunjue.echo.mind.visual.math.DeterministicRandom
import kotlin.math.PI
import kotlin.math.sin

/**
 * 有机噪声层 — filament 网状膜与湍流的数学基础。
 *
 * 不用 Perlin/Simplex（无第三方依赖、纯 JVM 可测、跨端可复现），
 * 用「多正弦叠加 + seeded 相位」的 value-noise 近似：
 * 低频分量给膜的有机起伏，高频分量给湍流，全部确定性。
 */
object OrganicNoise {

    /**
     * 一维有机噪声（角度/时间 → -1..1）。
     * @param seed 随机源；@param t 输入（弧度或秒）；@param octaves 叠加层数（越多越碎）。
     */
    fun fbm1(seed: Long, t: Float, octaves: Int = 3, turbulence: Float = 0f): Float {
        var sum = 0f
        var amp = 1f
        var freq = 1f
        var norm = 0f
        val layers = octaves.coerceIn(1, 5)
        for (o in 0 until layers) {
            val phase = DeterministicRandom.at(seed, o * 7 + 1) * 2f * PI.toFloat()
            sum += amp * sin(t * freq + phase)
            norm += amp
            amp *= 0.5f + turbulence * 0.2f
            freq *= 1.9f
        }
        return (sum / norm).coerceIn(-1f, 1f)
    }

    /**
     * 极坐标膜起伏：给定角度 θ 与半径基准，返回该角度的膜半径扰动 0..1。
     * coherence 越高 → 膜越平滑；turbulence 越高 → 膜越破碎（中性，非警告）。
     */
    fun membraneRadius(
        seed: Long,
        theta: Float,
        coherence: Float,
        turbulence: Float,
        timeSeconds: Float,
        flowSpeed: Float,
    ): Float {
        val smooth = fbm1(seed, theta * 2f + timeSeconds * flowSpeed * 0.2f, octaves = 2, turbulence = 0f)
        val rough = fbm1(seed, theta * 5f - timeSeconds * flowSpeed * 0.5f, octaves = 3, turbulence = turbulence)
        // coherence 高 → 用 smooth；低 → 混入 rough
        val mixed = smooth * coherence + rough * (1f - coherence)
        return (mixed * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    /** 缓慢漂移相位（driftRate 驱动的超低频摆动，用于 orbital/drift）。 */
    fun driftPhase(seed: Long, timeSeconds: Float, driftRate: Float): Float {
        val period = 30f - driftRate * 20f // 10s..30s 慢周期
        val phase = DeterministicRandom.at(seed, 999) * 2f * PI.toFloat()
        return sin(timeSeconds / period * 2f * PI.toFloat() + phase)
    }
}
