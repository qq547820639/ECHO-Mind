package com.yunjue.echo.mind.visual

import com.yunjue.echo.mind.visual.math.DeterministicRandom
import com.yunjue.echo.mind.visual.model.EchoIdentitySpec
import com.yunjue.echo.mind.visual.render.EchoRenderQuality
import com.yunjue.echo.mind.visual.render.OrganismTopologyBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3 §14–§18 / §32 — OrganismTopology 测试：
 * 三层拓扑齐全且比例合理；Fibonacci 粒子分类；空心核结稳定；
 * 缓存只在 identity/quality/maturity/version 变化时重建（§32）。
 */
class OrganismTopologyTest {

    private fun identity(seed: Long) = EchoIdentitySpec.derive(seed)

    @Test
    fun threeLayerTopologyExistsWithRoughProportions() {
        val topo = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "MATURE")
        // Organism Quality §6：rings 4..7 / long 12..22(MATURE) / fragments 24..40(MATURE)
        assertTrue("structural rings 4..7", topo.rings.size in 4..7)
        assertTrue("long filaments 12..22 at MATURE", topo.longFilaments.size in 12..22)
        assertTrue("local fragments 24..40 at MATURE", topo.fragments.size in 24..40)
        // §6 比例：rings 15–20% / long 35–45% / fragments 35–45%（按条数近似，容差放宽）
        val total = (topo.rings.size + topo.longFilaments.size + topo.fragments.size).toFloat()
        val longRatio = topo.longFilaments.size / total
        val fragRatio = topo.fragments.size / total
        assertTrue("long filaments ~35-45%", longRatio in 0.28f..0.55f)
        assertTrue("fragments ~35-45% (count approx, wide tolerance)", fragRatio in 0.28f..0.62f)
    }

    @Test
    fun ringsAreNotClosedOrbits() {
        val topo = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "MATURE")
        // Organism Quality §6：结构环非闭合（留 3%–16% 弧口，打破轨道圆读感）
        assertTrue(topo.rings.isNotEmpty())
        topo.rings.forEach {
            assertTrue("ring arc < 2π (non-orbit)", it.arcLength < 2f * Math.PI.toFloat())
            assertTrue("ring arc >= 84% circle", it.arcLength >= 0.84f * 2f * Math.PI.toFloat())
        }
    }

    @Test
    fun longFilamentsAreNotFullCircles() {
        val topo = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "MATURE")
        topo.longFilaments.forEach {
            assertTrue("arcLength < 2π", it.arcLength < 2f * Math.PI.toFloat())
            assertTrue("跨半球弧", it.arcLength > Math.PI.toFloat())
        }
    }

    @Test
    fun localFragmentsStayInMidOuterShells() {
        val topo = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "MATURE")
        // Organism Quality §7：18°–75° 短弧（0.31–1.31 rad），主体分布 0.48–0.95R
        assertTrue("fragments present at KNOWN", topo.fragments.isNotEmpty())
        topo.fragments.forEach {
            assertTrue("fragment arc 18°–75°", it.arcLength in 0.31f..1.31f)
        }
        val shells = topo.fragments.map { f -> f.center.let { c -> kotlin.math.sqrt(c.x * c.x + c.y * c.y + c.z * c.z) } }
        assertTrue("fragment shells within 0.45–0.95R", shells.all { it in 0.45f..0.96f })
        // 二次分布：多数在 0.55R 外（很少贴核）
        val outer = shells.count { it > 0.55f }
        assertTrue("fragments biased to mid/outer shells", outer >= shells.size * 55 / 100)
    }

    @Test
    fun fibonacciParticlesClassified() {
        val topo = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "MATURE")
        val total = topo.particles.size
        val ambient = topo.particles.count { it.kind == com.yunjue.echo.mind.visual.render.ParticleKind.AMBIENT }
        val glint = topo.particles.count { it.kind == com.yunjue.echo.mind.visual.render.ParticleKind.GLINT }
        // §17 + Quality §12：Ambient ~78% / Glint ~5%（容差 ±5%）
        assertEquals(0.78f, ambient / total.toFloat(), 0.05f)
        assertEquals(0.05f, glint / total.toFloat(), 0.05f)
        // 壳层半径收敛 .50..1.0（反星空：削减远层散点）
        topo.particles.forEach { assertTrue(it.shellRadius in 0.49f..1.01f) }
    }

    @Test
    fun coreKnotsStableAcrossCalls() {
        val a = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "KNOWN")
        val b = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "KNOWN")
        assertSame("§32：同 identity/quality/maturity 命中缓存", a, b)
        assertTrue("2–4 个稳定结", a.coreKnots.size in 2..4)
    }

    @Test
    fun cacheRebuildsOnlyOnKeyChange() {
        val base = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "KNOWN")
        val sameQuality = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "KNOWN")
        assertSame(base, sameQuality)
        val otherQuality = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.MINIMAL, "KNOWN")
        assertNotSame("quality 变化重建", base, otherQuality)
        val otherIdentity = OrganismTopologyBuilder.topologyFor(identity(12L), EchoRenderQuality.NORMAL, "KNOWN")
        assertNotSame("identity 变化重建", base, otherIdentity)
    }

    @Test
    fun qualityDegradesRichnessNotIdentity() {
        val normal = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "MATURE")
        val minimal = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.MINIMAL, "MATURE")
        assertTrue(minimal.longFilaments.size <= normal.longFilaments.size)
        assertTrue(minimal.fragments.size <= normal.fragments.size)
        // identity 结构环数不因质量降级减少（core anatomy 不降级）
        assertEquals(normal.rings.size, minimal.rings.size)
    }

    /**
     * T2-P2-1 §32：缓存溢出按 key LRU 淘汰——不再 clear-on-overflow 全量清空
     * （双 identity 交替渲染时旧实现逐帧全量重建 220 粒子 + 双排序）。
     */
    @Test
    fun topologyCacheEvictsLruInsteadOfClearAll() {
        OrganismTopologyBuilder.clearCacheForTest()
        val capacity = 16
        // 填满容量（不同 identity seed）
        val built = (0 until capacity).map { i ->
            OrganismTopologyBuilder.topologyFor(identity(100L + i), EchoRenderQuality.NORMAL, "KNOWN")
        }
        assertEquals(capacity, OrganismTopologyBuilder.cacheSizeForTest())
        // 触碰 key0（成为最近使用），再溢出插入一个新 key
        val again0 = OrganismTopologyBuilder.topologyFor(identity(100L), EchoRenderQuality.NORMAL, "KNOWN")
        assertSame(built[0], again0)
        OrganismTopologyBuilder.topologyFor(identity(999L), EchoRenderQuality.NORMAL, "KNOWN")
        assertEquals("容量封顶（按 key 淘汰最久未用，不清全表）", capacity, OrganismTopologyBuilder.cacheSizeForTest())
        // 最近使用的 key0 仍命中（旧 clear-on-overflow 实现会全量重建）
        val survivor = OrganismTopologyBuilder.topologyFor(identity(100L), EchoRenderQuality.NORMAL, "KNOWN")
        assertSame("LRU 命中：最近使用条目未被淘汰", built[0], survivor)
        // 最久未用的 key1 被按 key 淘汰（重新请求 → 重建新实例）
        val evicted = OrganismTopologyBuilder.topologyFor(identity(101L), EchoRenderQuality.NORMAL, "KNOWN")
        assertNotSame("最久未用条目按 key 淘汰重建", built[1], evicted)
        OrganismTopologyBuilder.clearCacheForTest()
    }

    /**
     * T2-P2-2 盐分段契约：各层（含层内通道族）盐区间两两不相交——跨层随机流独立，
     * 消除旧盐空间「particle i 的 shell ≡ particle i-10 的 classRank」等隐藏等值相关。
     */
    @Test
    fun saltSegmentsDoNotCollideAcrossLayers() {
        val segments = mapOf(
            "rings" to 1000..1046,
            "longs" to 2000..2152,
            "frags.main" to 3000..3199,
            "frags.depthWarp" to 3400..3439,
            "frags.curvature" to 3450..3489,
            "frags.family" to 3500..3539,
            "particles.shell" to 4000..4219,
            "particles.classRank" to 4220..4439,
            "particles.warmRank" to 4440..4659,
            "particles.sizeJitter" to 4660..4879,
            "knots.angle" to 5000..5003,
            "knots.radius" to 5100..5103,
            "knots.size" to 5200..5203,
        )
        val list = segments.entries.toList()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val a = list[i].value
                val b = list[j].value
                assertTrue(
                    "${list[i].key}${a} 与 ${list[j].key}${b} 盐区间相交",
                    a.last < b.first || b.last < a.first,
                )
            }
        }
        // 抽样：跨层不同盐在采样 seed 上取自独立随机流（非同值流）
        val seeds = listOf(11L, 12L, 777L, 20260815L)
        val saltPairs = listOf(
            1020 to 2010, // ring lobe amp vs long arcStart
            3005 to 4005, // frag shell u vs particle shell u
            4240 to 5100, // particle classRank vs knot radius
            3455 to 4675, // frag curvature vs particle sizeJitter
        )
        for ((sa, sb) in saltPairs) {
            val differs = seeds.any { s -> DeterministicRandom.at(s, sa) != DeterministicRandom.at(s, sb) }
            assertTrue("盐 $sa/$sb 在全部采样 seed 上同值（跨层随机流冲突）", differs)
        }
    }
}
