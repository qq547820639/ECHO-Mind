package com.yunjue.echo.mind.sensing

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 端侧音频特征提取器（T03.2）：
 *
 * - 输入：PCM 16-bit 单声道 16kHz 音频片段（ShortArray）
 * - 输出：[MicDerivedFeature]（summary 中文自然语言摘要 + vector ≤ 256 维 float）
 *
 * 提取特征：
 * - 音量包络：RMS dB
 * - 语速估计：基于能量变化率（短时能量上升沿次数作为音节代理）
 * - 停顿次数：低能量段（< 全局均值 * 阈值）持续 > 200ms 的次数
 * - 基础情绪声学特征：基频 F0 估计（简单自相关法，60–400Hz 范围）
 *
 * 约束：
 * - 原始音频 buffer 处理后由调用方丢弃，本类不持有任何音频原始数据
 * - vector 维度固定为 [VECTOR_DIM]（=256），不足补零，符合后端 DerivedFeatureIn.vector ≤ 256 约束
 * - summary 为中文自然语言，长度 ≤ 4000 字符
 */
class MicFeatureExtractor {

    /**
     * 单次提取的派生特征。
     *
     * @param summary    中文自然语言摘要（≤ 4000 字符）
     * @param vector     ≤ 256 维 float 特征向量
     * @param durationMs 本次片段时长（毫秒）
     * @param rmsDb      音量包络 RMS dB
     * @param speechRate 语速估计（音节/秒）
     * @param pauseCount 停顿次数
     * @param f0Mean     基频均值（Hz），无有效语音时为 0
     */
    data class MicDerivedFeature(
        val summary: String,
        val vector: FloatArray,
        val durationMs: Long,
        val rmsDb: Float,
        val speechRate: Float,
        val pauseCount: Int,
        val f0Mean: Float
    ) {
        // FloatArray 默认用引用相等，单测中按需比较 size / 内容
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * 从一段 PCM 16-bit 音频提取特征。原始 samples 不被保留。
     *
     * @param samples    PCM 16-bit 单声道采样（-32768..32767）
     * @param sampleRate 采样率（默认 16000）
     */
    fun extract(samples: ShortArray, sampleRate: Int = SAMPLE_RATE_16K): MicDerivedFeature {
        if (samples.isEmpty()) return emptyFeature()

        val n = samples.size
        val durationMs = (n.toLong() * 1000L) / sampleRate.toLong()

        // 1. RMS dB（音量包络）
        val rms = computeRms(samples)
        val rmsDb = if (rms > 0.0) {
            (20f * log10((rms / Short.MAX_VALUE.toDouble())).toFloat()).coerceIn(MIN_DB, MAX_DB)
        } else MIN_DB

        // 2. 短时能量包络 + 过零率（按帧分析，20ms 帧）
        val frameSize = (sampleRate / 50).coerceAtLeast(1)
        val frames = frameSequence(samples, frameSize)
        val frameEnergies = FloatArray(frames.size)
        val frameZcr = FloatArray(frames.size)
        for ((idx, frame) in frames.withIndex()) {
            frameEnergies[idx] = computeFrameEnergy(frame)
            frameZcr[idx] = computeZeroCrossingRate(frame)
        }

        // 3. 语速估计：能量上升沿数量 / 时长（秒）
        val speechRate = estimateSpeechRate(frameEnergies, durationMs)

        // 4. 停顿次数：低能量帧连续段 > 200ms
        val pauseCount = countPauses(frameEnergies, frameSize, sampleRate)

        // 5. F0 估计：在最大能量帧上做自相关
        val f0Mean = estimateF0Mean(samples, sampleRate, frameEnergies, frameSize)

        // 6. 构建 vector
        val vector = buildVector(rmsDb, speechRate, pauseCount, f0Mean, frameEnergies, frameZcr)

        // 7. 中文摘要
        val summary = buildSummary(rmsDb, speechRate, pauseCount, f0Mean, durationMs)

        return MicDerivedFeature(summary, vector, durationMs, rmsDb, speechRate, pauseCount, f0Mean)
    }

    /** 空特征（用于无音频或失败兜底）。 */
    fun emptyFeature(): MicDerivedFeature = MicDerivedFeature(
        summary = "未捕获到音频。",
        vector = FloatArray(VECTOR_DIM),
        durationMs = 0L,
        rmsDb = MIN_DB,
        speechRate = 0f,
        pauseCount = 0,
        f0Mean = 0f
    )

    // ===== 内部计算 =====

    private fun computeRms(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sum += v * v
        }
        return sqrt(sum / samples.size)
    }

    private fun frameSequence(samples: ShortArray, frameSize: Int): List<ShortArray> {
        val result = ArrayList<ShortArray>(samples.size / frameSize + 1)
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + frameSize, samples.size)
            result.add(samples.copyOfRange(i, end))
            i += frameSize
        }
        return result
    }

    private fun computeFrameEnergy(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (s in frame) {
            val v = s.toDouble()
            sum += v * v
        }
        return (sum / frame.size).toFloat()
    }

    private fun computeZeroCrossingRate(frame: ShortArray): Float {
        if (frame.size < 2) return 0f
        var zc = 0
        for (i in 1 until frame.size) {
            val prev = frame[i - 1]
            val curr = frame[i]
            if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) zc++
        }
        return zc.toFloat() / (frame.size - 1)
    }

    /** 语速估计：能量上升沿数量 / 时长（秒），作为音节速率代理。 */
    private fun estimateSpeechRate(energies: FloatArray, durationMs: Long): Float {
        if (energies.size < 2 || durationMs <= 0L) return 0f
        val mean = energies.average().toFloat()
        val threshold = mean * ENERGY_CHANGE_RATIO
        var rising = 0
        for (i in 1 until energies.size) {
            if (energies[i] - energies[i - 1] > threshold && energies[i] > mean) rising++
        }
        val seconds = durationMs / 1000.0
        return (rising / seconds).toFloat()
    }

    /** 停顿次数：低能量帧（< 全局均值 * PAUSE_RATIO）连续段且总时长 > 200ms。 */
    private fun countPauses(energies: FloatArray, frameSize: Int, sampleRate: Int): Int {
        if (energies.isEmpty()) return 0
        val mean = energies.average().toFloat()
        val pauseThreshold = mean * PAUSE_RATIO
        val framesPer200ms = (sampleRate * 0.2 / frameSize).toInt().coerceAtLeast(1)
        var count = 0
        var run = 0
        for (e in energies) {
            if (e < pauseThreshold) {
                run++
            } else {
                if (run >= framesPer200ms) count++
                run = 0
            }
        }
        if (run >= framesPer200ms) count++
        return count
    }

    /**
     * F0 估计：选取能量 top-K 帧，做自相关，在 60–400Hz 范围内找峰值对应的 lag。
     * 返回平均 F0（Hz）；无有效语音时返回 0。
     */
    private fun estimateF0Mean(
        samples: ShortArray,
        sampleRate: Int,
        energies: FloatArray,
        frameSize: Int
    ): Float {
        if (energies.isEmpty()) return 0f
        val k = minOf(F0_TOP_K, energies.size)
        val topIndices = energies.indices.sortedByDescending { energies[it] }.take(k)
        val f0s = ArrayList<Float>(k)
        val minLag = (sampleRate / F0_MAX_HZ).coerceAtLeast(2)
        val maxLag = (sampleRate / F0_MIN_HZ).coerceAtLeast(minLag + 1)
        for (idx in topIndices) {
            val start = idx * frameSize
            val end = minOf(start + frameSize, samples.size)
            if (end - start < maxLag + 2) continue
            val f0 = autocorrelateF0(samples, start, end, minLag, maxLag, sampleRate)
            if (f0 > 0f) f0s.add(f0)
        }
        return if (f0s.isEmpty()) 0f else f0s.average().toFloat()
    }

    private fun autocorrelateF0(
        samples: ShortArray,
        start: Int,
        end: Int,
        minLag: Int,
        maxLag: Int,
        sampleRate: Int
    ): Float {
        val len = end - start
        if (len <= maxLag + 1) return 0f
        // 中心化（去 DC）
        var mean = 0.0
        for (i in start until end) mean += samples[i].toDouble()
        mean /= len
        val normalized = FloatArray(len) { (samples[start + it].toFloat() - mean.toFloat()) }

        var energy = 0.0
        for (v in normalized) energy += (v * v).toDouble()
        if (energy <= 0.0) return 0f

        var bestLag = -1
        var bestCorr = 0.0
        for (lag in minLag..maxLag) {
            var corr = 0.0
            val limit = len - lag
            for (i in 0 until limit) {
                corr += normalized[i] * normalized[i + lag]
            }
            val normCorr = corr / energy
            if (normCorr > bestCorr) {
                bestCorr = normCorr
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestCorr < F0_MIN_CORR) return 0f
        return sampleRate.toFloat() / bestLag.toFloat()
    }

    /**
     * 构建 256 维特征向量：
     * - [0..7]：8 个标量特征（rmsDb / speechRate / pauseCount / f0Mean / 能量 std / 能量 min / 能量 max / zcr 均值）
     * - [8..39]：32-bin 能量分布
     * - [40..71]：32-bin 能量差分分布
     * - [72..103]：32-bin 过零率分布
     * - [104..135]：32-bin F0 占位（以 f0Mean 落入对应 bin）
     * - [136..255]：补零
     */
    private fun buildVector(
        rmsDb: Float,
        speechRate: Float,
        pauseCount: Int,
        f0Mean: Float,
        frameEnergies: FloatArray,
        frameZcr: FloatArray
    ): FloatArray {
        val v = FloatArray(VECTOR_DIM)
        v[0] = (rmsDb / MAX_DB).coerceIn(0f, 1f)
        v[1] = speechRate.coerceAtLeast(0f)
        v[2] = pauseCount.toFloat()
        v[3] = (f0Mean / F0_MAX_HZ).coerceIn(0f, 1f)
        if (frameEnergies.isNotEmpty()) {
            val eMean = frameEnergies.average().toFloat()
            val eVar = frameEnergies.map { (it - eMean) * (it - eMean) }.average().toFloat()
            val eStd = sqrt(eVar)
            v[4] = (eStd / (eMean + 1e-6f)).coerceIn(0f, 1f)
            v[5] = frameEnergies.minOrNull() ?: 0f
            v[6] = frameEnergies.maxOrNull() ?: 0f
        }
        v[7] = if (frameZcr.isNotEmpty()) frameZcr.average().toFloat() else 0f

        fillHistogram(v, 8, 8 + HIST_BINS, frameEnergies)
        fillHistogram(v, 8 + HIST_BINS, 8 + 2 * HIST_BINS, energyDeltas(frameEnergies))
        fillHistogram(v, 8 + 2 * HIST_BINS, 8 + 3 * HIST_BINS, frameZcr)
        if (f0Mean > 0f) {
            val bin = ((f0Mean - F0_MIN_HZ) / (F0_MAX_HZ - F0_MIN_HZ) * HIST_BINS).toInt()
                .coerceIn(0, HIST_BINS - 1)
            v[8 + 3 * HIST_BINS + bin] = 1f
        }
        return v
    }

    private fun energyDeltas(energies: FloatArray): FloatArray {
        if (energies.size < 2) return FloatArray(0)
        val out = FloatArray(energies.size - 1)
        for (i in 1 until energies.size) {
            out[i - 1] = energies[i] - energies[i - 1]
        }
        return out
    }

    private fun fillHistogram(target: FloatArray, start: Int, end: Int, source: FloatArray) {
        if (source.isEmpty()) return
        val min = source.minOrNull() ?: 0f
        val max = source.maxOrNull() ?: 0f
        val range = (max - min).coerceAtLeast(1e-6f)
        val bins = end - start
        for (value in source) {
            val bin = (((value - min) / range) * (bins - 1)).toInt().coerceIn(0, bins - 1)
            target[start + bin] += 1f
        }
        val sum = (0 until bins).sumOf { target[start + it].toDouble() }.toFloat()
        if (sum > 0f) {
            for (i in 0 until bins) target[start + i] /= sum
        }
    }

    private fun buildSummary(
        rmsDb: Float,
        speechRate: Float,
        pauseCount: Int,
        f0Mean: Float,
        durationMs: Long
    ): String {
        val seconds = durationMs / 1000.0
        val volDesc = when {
            rmsDb < -40f -> "音量很低"
            rmsDb < -25f -> "音量偏低"
            rmsDb < -10f -> "音量正常"
            else -> "音量较高"
        }
        val rateDesc = when {
            speechRate < 1.5f -> "语速偏慢"
            speechRate < 4f -> "语速正常"
            else -> "语速偏快"
        }
        val pauseDesc = when {
            pauseCount == 0 -> "无明显长停顿"
            pauseCount <= 3 -> "有 ${pauseCount} 次长停顿"
            else -> "有 ${pauseCount} 次较多长停顿"
        }
        val f0Desc = if (f0Mean > 0f) "基频约 ${f0Mean.toInt()} Hz" else "无明显基频"
        return "过去 ${"%.1f".format(seconds)} 秒：${rateDesc}，${pauseDesc}，${volDesc}，${f0Desc}。"
    }

    companion object {
        const val SAMPLE_RATE_16K = 16000
        const val VECTOR_DIM = 256
        const val HIST_BINS = 32

        const val MIN_DB = -80f
        const val MAX_DB = 0f

        const val F0_MIN_HZ = 60f
        const val F0_MAX_HZ = 400f
        const val F0_TOP_K = 5
        const val F0_MIN_CORR = 0.3

        const val ENERGY_CHANGE_RATIO = 0.15f
        const val PAUSE_RATIO = 0.2f
    }
}
