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
 * - 同一天 + 同一 identity → 逐值相同 genome（存参数不存图，帧由 genome 确定性重建）；
 * - 不同天 → 不同 genome（时间流逝可见）；
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
    fun sameDaySameIdentityProducesIdenticalGenome() {
        val p = portrait("2026-08-16")
        val a = JourneyOrganismVisuals.genomeFor(p, identitySeed = 7L)
        val b = JourneyOrganismVisuals.genomeFor(p, identitySeed = 7L)
        assertNotNull(a)
        assertEquals("同一天同 identity genome 必须逐值相同（帧确定性由此保证）", a, b)
    }

    @Test
    fun differentDaysProduceDifferentGenomes() {
        val d1 = JourneyOrganismVisuals.genomeFor(portrait("2026-08-15"), 7L)
        val d2 = JourneyOrganismVisuals.genomeFor(portrait("2026-08-16"), 7L)
        assertNotNull(d1); assertNotNull(d2)
        assertNotEquals("不同天应有差异", d1, d2)
    }

    /**
     * T5-P2-5：genomeFromParams 不再以 0.47 常量覆盖 dayComposition——
     * canonical v2 持久化字段（params.dayComposition）经编译器一一映射真实生效。
     */
    @Test
    fun genomeFromParamsPreservesPersistedDayComposition() {
        val params = journeyDayParams(portrait("2026-08-16"))!!.copy(dayComposition = 0.83f)
        val genome = JourneyOrganismVisuals.genomeFromParams(params, seed = 7L)
        assertEquals("v2 持久化 dayComposition 必须真实生效（非 0.47 覆盖）", 0.83f, genome.dayComposition)
        val other = JourneyOrganismVisuals.genomeFromParams(params.copy(dayComposition = 0.12f), seed = 7L)
        assertNotEquals("不同 dayComposition 应产出不同 genome", genome, other)
    }

    @Test
    fun noPortraitYieldsNull() {
        assertNull(JourneyOrganismVisuals.genomeFor(null, 7L))
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
