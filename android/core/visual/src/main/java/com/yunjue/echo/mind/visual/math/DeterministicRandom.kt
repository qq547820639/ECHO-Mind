package com.yunjue.echo.mind.visual.math

/**
 * 确定性伪随机（SplitMix64 终混）。
 *
 * 视觉随机必须 seeded：同一 identity/day/state/time 画面可复现（Journey 重建、golden test 的前提）。
 * 拒绝 `kotlin.random.Random` 默认实例（非确定性）；一切随机源都来自 identitySeed + 序号。
 */
object DeterministicRandom {
    /** seed + index → 0..1（消费完整 64 位熵，避免低位丢失导致粒子堆叠）。 */
    fun at(seed: Long, index: Int): Float {
        var x = seed + index.toLong() * GOLDEN_GAMMA
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        x = x xor (x ushr 31)
        return (x ushr 40 and 0xFFFFFF).toFloat() / 16777215f
    }

    /** seed + salt → 混合后的 Long（区分同一 seed 的独立序列空间）。 */
    fun mix(seed: Long, salt: Int): Long {
        var x = seed xor (salt.toLong() shl 40)
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }

    /** seed + index → [min, max) 区间浮点。 */
    fun range(seed: Long, index: Int, min: Float, max: Float): Float =
        min + at(seed, index) * (max - min)

    private const val GOLDEN_GAMMA: Long = -7046029254386353131L
}
