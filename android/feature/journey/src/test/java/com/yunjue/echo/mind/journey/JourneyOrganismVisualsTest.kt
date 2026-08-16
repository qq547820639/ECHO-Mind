package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Journey Organism 确定性重建测试（ECHO_VISUAL_ACCEPTANCE §二）：
 * - 同一天 + 同一 identity → 逐值相同帧（存参数不存图，可重建）；
 * - 不同天 → 不同帧（时间流逝可见）；
 * - snapshot 重建 = 直接渲染（EchoPortraitSnapshot 是确定性重建的完整载体）；
 * - 无画像 → null（不编造）。
 */
class JourneyOrganismVisualsTest {

    private fun portrait(
        date: String,
        baselineDays: Int = 20,
        movement: String = "SIMILAR",
        screen: String = "SIMILAR",
        rhythm: String = "SIMILAR",
    ): DailyPortraitDto {
        val dims = mutableMapOf<String, PortraitDimensionDto>()
        fun dim(v: String, z: Double = 0.0) = PortraitDimensionDto(value = v, z = z)
        dims["MOVEMENT"] = dim(movement, if (movement == "MORE") 1.0 else 0.0)
        dims["SCREEN_AMOUNT"] = dim(screen)
        dims["RHYTHM"] = dim(rhythm)
        return DailyPortraitDto(
            date = date, status = "READY", confidence = "HIGH",
            baselineDays = baselineDays, summary = "测试画像",
            dimensions = dims,
        )
    }

    @Test
    fun sameDaySameIdentityProducesIdenticalFrame() {
        val p = portrait("2026-08-16")
        val a = JourneyOrganismVisuals.frameFor(p, identitySeed = 7L, width = 400f, height = 400f)
        val b = JourneyOrganismVisuals.frameFor(p, identitySeed = 7L, width = 400f, height = 400f)
        assertNotNull(a)
        assertEquals("同一天同 identity 帧必须逐值相同", a, b)
    }

    @Test
    fun differentDaysProduceDifferentFrames() {
        val d1 = JourneyOrganismVisuals.frameFor(portrait("2026-08-15"), 7L, 400f, 400f)
        val d2 = JourneyOrganismVisuals.frameFor(portrait("2026-08-16"), 7L, 400f, 400f)
        assertNotNull(d1); assertNotNull(d2)
        assertNotEquals("不同天应有差异", d1, d2)
    }

    @Test
    fun snapshotReconstructsSameFrameAsDirectRender() {
        val p = portrait("2026-08-16")
        val snapshot = JourneyOrganismVisuals.snapshotFor(p, identitySeed = 7L)
        assertNotNull(snapshot)
        val direct = JourneyOrganismVisuals.frameFor(p, 7L, 400f, 400f)
        val rebuilt = JourneyOrganismVisuals.reconstructFrame(snapshot!!, 400f, 400f)
        assertEquals("snapshot 重建必须与直接渲染一致", direct, rebuilt)
    }

    @Test
    fun noPortraitYieldsNull() {
        assertNull(JourneyOrganismVisuals.genomeFor(null, 7L))
        assertNull(JourneyOrganismVisuals.frameFor(null, 7L, 400f, 400f))
    }

    @Test
    fun maturityGrowsAcrossDays() {
        // SEED → MATURE：coreIntensity（核心开放度）随 baselineDays 单调不减（identity evolution 可辨）
        val seed = JourneyOrganismVisuals.genomeFor(portrait("2026-08-01", baselineDays = 0), 7L)
        val mature = JourneyOrganismVisuals.genomeFor(portrait("2026-08-28", baselineDays = 28), 7L)
        assertNotNull(seed); assertNotNull(mature)
        assertTrue(
            "成熟度成长应体现为核心开放度提升",
            mature!!.coreIntensity > seed!!.coreIntensity,
        )
    }
}
