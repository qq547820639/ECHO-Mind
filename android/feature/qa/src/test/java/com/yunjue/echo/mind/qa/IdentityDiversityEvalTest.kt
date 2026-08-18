package com.yunjue.echo.mind.qa

import com.yunjue.echo.mind.presence.AmbientVector
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presence.PresenceMotionLevel
import com.yunjue.echo.mind.presence.buildDailyComposition
import com.yunjue.echo.mind.presence.deriveIdentityGenome
import com.yunjue.echo.mind.presence.identityDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ERA 21 §13 — Identity Diversity Eval（100 个 installation seed）：
 *
 * 禁止「100 个 ECHO 只是颜色不同」。验证：
 * 1. 任意两两 genome 距离不低于阈值（没有两个 ECHO 几乎相同）；
 * 2. 仅靠色相差的「伪多样性」显著小于全维度距离（结构维真的不同）；
 * 3. 视觉参数层（flow/coherence/openness）与渲染帧层（色相）都具备多样性。
 */
class IdentityDiversityEvalTest {

    companion object {
        const val SEED_COUNT = 100

        /**
         * 确定性 seed 序列（100 个 installation seed）：SplitMix64 流，
         * 模拟真实 SecureRandom 的均匀分布（避免乘性序列导致的伪碰撞）。
         */
        fun seeds(): List<Long> {
            val rng = QaRng(0x5EED_2026_08_15L)
            return (1..SEED_COUNT).map { rng.nextLong() }
        }
    }

    private fun genomes(): List<com.yunjue.echo.mind.model.EchoIdentityGenome> =
        seeds().map { deriveIdentityGenome(it, 0.6f, PresenceMotionLevel.DEFAULT) }

    private fun hueOnlyDistance(
        a: com.yunjue.echo.mind.model.EchoIdentityGenome,
        b: com.yunjue.echo.mind.model.EchoIdentityGenome,
    ): Float {
        val d = abs(a.accentHue - b.accentHue) % 1f
        return minOf(d, 1f - d) * 0.10f
    }

    @Test
    fun oneHundredSeedsProduceDistinctGenomes() {
        val genomes = genomes()
        assertEquals(SEED_COUNT, genomes.toSet().size)
    }

    @Test
    fun pairwiseMinimumDistanceIsSafe() {
        val genomes = genomes()
        var minDistance = Float.MAX_VALUE
        var minPair: Pair<Int, Int>? = null
        for (i in genomes.indices) {
            for (j in i + 1 until genomes.size) {
                val d = identityDistance(genomes[i], genomes[j])
                if (d < minDistance) {
                    minDistance = d
                    minPair = i to j
                }
            }
        }
        assertTrue(
            "任意两 ECHO 距离 ≥ 0.03（实际最小 ${"%.4f".format(minDistance)} @ seeds $minPair）",
            minDistance >= 0.03f,
        )
    }

    @Test
    fun diversityIsNotJustColor() {
        val genomes = genomes()
        // 仅色相差的最小距离（伪多样性下会趋近 0）
        var minHue = Float.MAX_VALUE
        var minFull = Float.MAX_VALUE
        for (i in genomes.indices) {
            for (j in i + 1 until genomes.size) {
                minHue = minOf(minHue, hueOnlyDistance(genomes[i], genomes[j]))
                minFull = minOf(minFull, identityDistance(genomes[i], genomes[j]))
            }
        }
        assertTrue("纯色相层面存在几乎撞色的对（前提成立）", minHue < 0.005f)
        assertTrue(
            "全维度最小距离应显著大于纯色相距离（颜色以外维度真实分化）",
            minFull > minHue + 0.03f,
        )
    }

    @Test
    fun categoricalIdentityDimensionsCoverWideSpace() {
        val genomes = genomes()
        val colorTexturePairs = genomes.map { it.colorFamily to it.textureFamily }.toSet().size
        val orbitBuckets = genomes.map { (it.orbitGeometry * 10f).roundToInt() }.toSet().size
        val topologyBuckets = genomes.map { (it.coreTopology * 10f).roundToInt() }.toSet().size
        val motionBuckets = genomes.map { (it.motionPersonality * 10f).roundToInt() }.toSet().size
        assertEquals("颜色族×纹理族应覆盖全部 5×4 组合", 20, colorTexturePairs)
        assertTrue("轨道几何 ≥ 8 桶（实际 $orbitBuckets）", orbitBuckets >= 8)
        // topology 有效范围 0.4..1 → 10 桶中最多覆盖 6 桶
        assertTrue("核心拓扑 ≥ 5 桶（实际 $topologyBuckets）", topologyBuckets >= 5)
        assertTrue("运动人格 ≥ 4 桶（实际 $motionBuckets）", motionBuckets >= 4)
    }

    @Test
    fun visualParametersDifferBeyondColor() {
        val vector = AmbientVector(activation = 0.5f, regularity = 0.6f, density = 0.5f, deviation = 0.3f, confidence = 0.7f)
        val signatures = seeds().map { seed ->
            val identity = deriveIdentityGenome(seed, 0.6f, PresenceMotionLevel.DEFAULT)
            val state = EchoPresenceState(
                maturity = EchoMaturity.KNOWN,
                identityGenome = identity,
                dailyComposition = buildDailyComposition(identity, vector, EchoMaturity.KNOWN),
            )
            val params = EchoVisualMapper.map(state, 12f)
            // 结构签名：流动 / 凝聚 / 开放度（不含颜色）
            Triple(
                (params.flowSpeed * 20f).roundToInt(),
                (params.coherence * 20f).roundToInt(),
                (params.coreOpenness * 20f).roundToInt(),
            )
        }
        assertTrue(
            "视觉结构签名应 ≥ 15 种（实际 ${signatures.toSet().size}）—— 差异不止颜色",
            signatures.toSet().size >= 15,
        )
    }

    @Test
    fun renderedFramesVaryInColorAndStructure() {
        val frames = seeds().map { seed ->
            val identity = deriveIdentityGenome(seed, 0.6f, PresenceMotionLevel.DEFAULT)
            val state = EchoPresenceState(
                maturity = EchoMaturity.KNOWN,
                identityGenome = identity,
                dailyComposition = buildDailyComposition(
                    identity,
                    AmbientVector(activation = 0.5f, regularity = 0.6f, density = 0.5f, deviation = 0.3f, confidence = 0.7f),
                    EchoMaturity.KNOWN,
                ),
            )
            com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                    com.yunjue.echo.mind.visual.model.VisualGenomeCompiler.compile(
                        EchoVisualMapper.map(state, 12f), state.identityGenome,
                    ),
                    com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                    QaTimeline.frameTimeSeconds(),
                ),
                width = 1080f,
                height = 2340f,
                options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                    maturityName = state.maturity.name,
                ),
            )
        }
        // V3 §12：identity 色族收敛蓝—紫 218°..262°——色相差异收敛是**设计决策**，
        // 身份多样性由几何维度承担（lobe/chirality/tilt/频率族/核心比）。
        // 强调色只需可分辨下限（同族内不同 hue 仍可见）。
        val distinctAccents = frames.map { it.frontMembrane.color and 0x00FFFFFF }.toSet().size
        val distinctBackgrounds = frames.map { it.ambientField.centerColor }.toSet().size
        val distinctRadii = frames.map { (it.coreCavity.radiusFraction * 1000f).roundToInt() }.toSet().size
        val distinctCounts = frames.map { it.particles.size }.toSet().size
        // 几何多样性（V3 §82：至少 3 个几何维度明显变化）
        val identityDims = (0 until 60).map { seed ->
            com.yunjue.echo.mind.visual.model.EchoIdentitySpec.derive(seed.toLong())
        }
        val lobeVariety = identityDims.map { it.lobeCount }.toSet().size
        val tiltVariety = identityDims.map { (it.primaryTilt * 100).toInt() }.toSet().size
        val coreVariety = identityDims.map { (it.coreRatio * 100).toInt() }.toSet().size
        val freqVariety = identityDims.map { it.baseFrequency }.toSet().size
        assertTrue("强调色 ≥ 4 种（蓝紫族内可辨；实际 $distinctAccents）", distinctAccents >= 4)
        assertTrue("背景色 ≥ 8 种（实际 $distinctBackgrounds）", distinctBackgrounds >= 8)
        assertTrue("核心半径 ≥ 5 种（实际 $distinctRadii）", distinctRadii >= 5)
        assertTrue("粒子数 ≥ 3 种（实际 $distinctCounts）", distinctCounts >= 3)
        assertTrue("lobe 数 ≥ 3 种", lobeVariety >= 3)
        assertTrue("主倾角 ≥ 10 种", tiltVariety >= 10)
        assertTrue("核心比 ≥ 10 种", coreVariety >= 10)
        assertTrue("频率族 ≥ 3 种", freqVariety >= 3)
    }
}
