package com.yunjue.echo.mind.visual

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
        assertTrue("structural rings 2..4", topo.rings.size in 2..4)
        assertTrue("long filaments present", topo.longFilaments.size >= 6)
        assertTrue("local fragments present", topo.fragments.size >= 4)
        // §14 比例：rings ~20% / long ~45% / fragments ~35%（按条数近似，容差 ±15%）
        val total = (topo.rings.size + topo.longFilaments.size + topo.fragments.size).toFloat()
        val longRatio = topo.longFilaments.size / total
        val fragRatio = topo.fragments.size / total
        assertTrue("long filaments ~45%", longRatio in 0.30f..0.60f)
        assertTrue("fragments ~35%", fragRatio in 0.20f..0.50f)
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
    fun fibonacciParticlesClassified() {
        val topo = OrganismTopologyBuilder.topologyFor(identity(11L), EchoRenderQuality.NORMAL, "MATURE")
        val total = topo.particles.size
        val ambient = topo.particles.count { it.kind == com.yunjue.echo.mind.visual.render.ParticleKind.AMBIENT }
        val glint = topo.particles.count { it.kind == com.yunjue.echo.mind.visual.render.ParticleKind.GLINT }
        // §17：Ambient ~78% / Glint ~6%（容差 ±5%）
        assertEquals(0.78f, ambient / total.toFloat(), 0.05f)
        assertEquals(0.06f, glint / total.toFloat(), 0.05f)
        // 壳层半径 .48..1.08
        topo.particles.forEach { assertTrue(it.shellRadius in 0.47f..1.09f) }
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
}
