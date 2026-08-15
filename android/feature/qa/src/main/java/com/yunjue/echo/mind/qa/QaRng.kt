package com.yunjue.echo.mind.qa

/**
 * Product Quality Era — deterministic pseudo-random stream（SplitMix64）。
 *
 * 同一 seed → 任意 JVM / 任意平台完全同一序列；fixture 可重复性的根基。
 * 禁止使用 Random(System.currentTimeMillis) 之类非确定源。
 */
class QaRng(seed: Long) {

    private var state: Long = seed

    fun nextLong(): Long {
        state += -7046029254386353131L
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1)。 */
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    /** 标准正态（Box-Muller）。 */
    fun nextGaussian(): Double {
        var u1 = nextDouble()
        if (u1 <= 0.0) u1 = Double.MIN_VALUE
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    /** 以 seed 为基、dayIndex 为盐的独立日级流（跨天不共享状态 → 任一单天可独立重放）。 */
    companion object {
        fun forDay(seed: Long, dayIndex: Int): QaRng {
            val salted = seed xor dayIndex.toLong() * -7046029254386353131L
            return QaRng(salted xor (salted ushr 33))
        }
    }
}
