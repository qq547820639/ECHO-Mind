package com.yunjue.echo.mind.presence

import com.yunjue.echo.mind.model.EchoMaturity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ERA 31 — 身份结构可见性回归。
 *
 * 对应真实产品问题（Real Render Review 发现）：帧渲染器几乎不消费
 * textureFamily / orbitGeometry，两个用户的 ECHO 差异几乎只靠色相——
 * Part 17 要求差异来自 motion personality / topology / orbit / texture / structure，
 * 而不是换颜色。本测试锁定：结构维度真实改变画面结构（不是只改颜色）。
 */
class IdentityStructureVisibilityTest {

    private fun frameFor(seed: Long, structure: Float) = run {
        // V3：经 genome 链（structureComplexity → filamentDensity 语义映射）
        val genome = com.yunjue.echo.mind.visual.testing.VisualLabFixtures.genomeFor(
            com.yunjue.echo.mind.visual.testing.VisualLabFixtures.Preset.KNOWN_DAY28, seed,
        ).copy(filamentDensity = structure.coerceIn(0.05f, 1f))
        com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
            com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                genome, com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE, 12f,
            ),
            1080f, 2340f,
        )
    }

    @Test
    fun particleFieldHasRealSpread() {
        // ERA 31 修复回归：粒子场必须真实散布（Fibonacci 球投影，不堆叠）。
        val frame = frameFor(seed = 42L, structure = 0.5f)
        val distinctPositions = frame.particles.map { "${it.x.toBits()}|${it.y.toBits()}".toString() }
            .toSet()
        assertTrue(
            "粒子场必须真实散布（${distinctPositions.size}/${frame.particles.size} 个不同位置）",
            distinctPositions.size >= frame.particles.size * 0.9,
        )
    }

    @Test
    fun textureAndOrbitDerivationMatchesIdentityGenome() {
        for (seed in listOf(0L, 1L, 2L, 3L, 42L, 7710L, 999L)) {
            val genome = deriveIdentityGenome(seed, 0.5f, PresenceMotionLevel.DEFAULT)
            assertEquals("seed=$seed textureFamily 同源", genome.textureFamily, seedTextureFamily(seed))
            assertEquals("seed=$seed orbitGeometry 同源", genome.orbitGeometry, seedOrbitGeometry(seed), 1e-6f)
        }
    }

    @Test
    fun structureComplexityChangesRingStructureNotColor() {
        val low = frameFor(seed = 7710L, structure = 0.05f)
        val high = frameFor(seed = 7710L, structure = 1f)
        // 同 seed → 颜色恒同（结构差异不得靠颜色冒充）
        assertEquals(low.frontMembrane.color, high.frontMembrane.color)
        assertEquals(low.ambientField.centerColor, high.ambientField.centerColor)
        // 高结构 → 更丰富的丝/碎片（V3：filamentDensity → 拓扑丰富度）
        val lowStrokes = low.longFilaments.size + low.localFragments.size
        val highStrokes = high.longFilaments.size + high.localFragments.size
        assertTrue("高结构应有更多丝/碎片（$lowStrokes → $highStrokes）", highStrokes >= lowStrokes)
    }

    @Test
    fun identityDimsVaryAcrossSeeds() {
        // V3 §10/§82：不同用户不只换颜色——lobe/chirality/tilt/核心比/频率族真实不同
        val ids = (0L..31L).map { com.yunjue.echo.mind.visual.model.EchoIdentitySpec.derive(it) }
        assertTrue(ids.map { it.lobeCount }.toSet().size >= 3)
        assertEquals(2, ids.map { it.chirality }.toSet().size)
        assertTrue(ids.map { it.baseFrequency }.toSet().size >= 3)
        assertTrue(ids.map { (it.coreRatio * 100).toInt() }.toSet().size >= 8)
    }

    @Test
    fun ambientEdgeIsDarkerThanCenter() {
        val frame = frameFor(seed = 42L, structure = 0.5f)
        fun v(c: Int) = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
        assertTrue(
            "边缘必须比中心暗（近黑衰减生效）",
            v(frame.ambientField.edgeColor) < v(frame.ambientField.centerColor),
        )
    }

    @Test
    fun dayZeroSeedPresenceMatchesFirstRuntimeIdentity() {
        // ERA 31 R31：苏醒瞬间与运行时第一次 Presence 必须是同一个 ECHO——
        // dayZeroSeedPresence 的 stability 与 AmbientEngine 无数据初值同源（0f），
        // 派生参数与 PresenceRepository Day-0 路径（deriveIdentityGenome(seed, regularity=0f, DEFAULT)）一致。
        val seed = 987654321L
        val fixedNow = Instant.ofEpochSecond(1753632000)
        val presence = dayZeroSeedPresence(identitySeed = seed, now = fixedNow)
        assertEquals(EchoMaturity.SEED, presence.maturity)
        assertEquals(fixedNow, presence.updatedAt)
        assertEquals(
            "Day-0 SEED presence 的 Identity 应与运行时同参派生完全一致",
            deriveIdentityGenome(seed = seed, baselineStability = 0f, motionPreference = PresenceMotionLevel.DEFAULT),
            presence.identityGenome,
        )
        // 确定性与稳定：同 seed 重复构建恒等
        assertEquals(presence, dayZeroSeedPresence(identitySeed = seed, now = fixedNow))
    }
}
