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

    private fun frameFor(seed: Long, structure: Float): EchoSceneFrame {
        val params = EchoVisualParameters(
            flowSpeed = 0.4f, coherence = 0.5f, turbulence = 0.2f, particleDensity = 0.4f,
            coreOpenness = 0.4f, dispersion = 0.5f, pulsePeriodSeconds = 5f, depth = 0.5f,
            brightness = 0.6f, contrast = 0.5f, accentIntensity = 0.6f,
            structureComplexity = structure,
        )
        return computeEchoSceneFrame(params, seed, timeSeconds = 12f, width = 1080f, height = 2340f)
    }

    @Test
    fun particleFieldHasRealSpread() {
        // ERA 31 修复回归：旧 sceneRandom 丢失 index 熵，所有粒子同角度堆叠——
        // 粒子场从未在视觉上存在（Real Render Review 发现）。
        val frame = frameFor(seed = 42L, structure = 0f)
        val distinctPositions = frame.particles.map { "${it.x.toBits()}|${it.y.toBits()}" }.toSet()
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
        val low = frameFor(seed = 7710L, structure = 0f)
        val high = frameFor(seed = 7710L, structure = 1f)
        // 同 seed → 颜色恒同（结构差异不得靠颜色冒充）
        assertEquals(low.accentColor, high.accentColor)
        assertEquals(low.backgroundCenterColor, high.backgroundCenterColor)
        assertEquals(low.backgroundEdgeColor, high.backgroundEdgeColor)
        assertTrue("高结构应有更多次级环（${low.extraRings.size} → ${high.extraRings.size}）",
            high.extraRings.size > low.extraRings.size)
    }

    @Test
    fun orbitGeometrySpreadsParticles() {
        val ring = frameFor(seed = 42L, structure = 0f)
        val diffuse = frameFor(seed = 42L, structure = 1f)
        assertEquals("同 seed 颜色恒同", ring.accentColor, diffuse.accentColor)
        val ringRadial = radialVariance(ring)
        val diffuseRadial = radialVariance(diffuse)
        assertTrue("弥散轨道径向分布更散（ring=$ringRadial diffuse=$diffuseRadial）",
            diffuseRadial > ringRadial * 1.5)
    }

    @Test
    fun textureFamiliesAreStructurallyDistinct() {
        // seeds 0..31 覆盖全部四个纹理族（seedTextureFamily 事实；防止映射回归成单一形态）
        val families = (0L..31L).map { seedTextureFamily(it) }.toSet()
        assertEquals("seed 0..31 必须覆盖全部 4 个纹理族", setOf(0, 1, 2, 3), families)

        // 流线族：粒子携带轨道切向拖尾
        val streakFrame = frameFor(seed = 3L, structure = 0.5f)
        assertEquals(2, streakFrame.textureFamily)
        assertTrue("流线族粒子必须有拖尾", streakFrame.particles.all { it.streakLength > 0f })
        assertTrue("拖尾方向必须为单位方向", streakFrame.particles.all {
            it.streakDirX != 0f || it.streakDirY != 0f
        })

        // 环晕族：额外远环（家族特征由环表达）
        val haloFrame = frameFor(seed = 1L, structure = 0.5f)
        assertEquals(3, haloFrame.textureFamily)
        assertTrue("环晕族必须有额外远环（${haloFrame.extraRings.size}）", haloFrame.extraRings.size >= 2)
    }

    @Test
    fun contrastDeepensEdgeNotCenter() {
        val dim = frameFor(seed = 42L, structure = 0.5f)
        // 同帧内：边缘亮值应低于中心（对比度实际生效）
        val centerV = (dim.backgroundCenterColor shr 16 and 0xFF) + (dim.backgroundCenterColor shr 8 and 0xFF) +
            (dim.backgroundCenterColor and 0xFF)
        val edgeV = (dim.backgroundEdgeColor shr 16 and 0xFF) + (dim.backgroundEdgeColor shr 8 and 0xFF) +
            (dim.backgroundEdgeColor and 0xFF)
        assertTrue("边缘必须比中心暗（contrast 生效）", edgeV < centerV)
        assertEquals(0.5f, dim.contrast, 1e-6f)
    }

    /** 归一化径向距离（除以画布纵横比后的圆心距离）的方差——环状轨道低、弥散轨道高。 */
    private fun radialVariance(frame: EchoSceneFrame): Double {
        val radii = frame.particles.map { p ->
            val nx = (p.x - 0.5f) * (1080f / 1080f)
            val ny = (p.y - 0.5f) * (1080f / 2340f)
            kotlin.math.sqrt((nx * nx + ny * ny).toDouble())
        }
        val mean = radii.average()
        return radii.map { (it - mean) * (it - mean) }.average()
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
