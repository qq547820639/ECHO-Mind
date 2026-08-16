package com.yunjue.echo.mind.journey

import com.yunjue.echo.mind.model.DailyPortraitDto
import com.yunjue.echo.mind.model.PortraitDimensionDto
import com.yunjue.echo.mind.model.PortraitTimelineUiState
import com.yunjue.echo.mind.presence.computeLifeSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 52（§52 审计第 2 轮）——Life Season × Journey 解释链绑定 + §84 回退重建：
 *
 * - assembleJourneyUiState 的 seasonExplanation 与 computeLifeSeason→explainLifeSeasonVisual
 *   逐字绑定（SEASON/YEAR 尺度；用户看到的就是 season 事实的中性解释）；
 * - 稳定时间线无阶段解释（不编造）；
 * - Canonical 缺失时画像回退重建确定性；双缺失 → null（渲染弥散占位，不伪造）。
 */
class LifeSeasonJourneyBindingTest {

    private fun portrait(date: String, rhythm: String, screen: String = "SIMILAR") = DailyPortraitDto(
        date = date,
        status = "READY",
        confidence = "HIGH",
        baselineDays = 40,
        headline = listOf("接近"),
        summary = "今天和平时很接近。",
        dimensions = mapOf(
            "MOVEMENT" to PortraitDimensionDto(value = "SIMILAR", metric = "movement_index", z = 0.5),
            "SCREEN_AMOUNT" to PortraitDimensionDto(value = screen, metric = "screen_on_minutes", z = 0.5),
            "RHYTHM" to PortraitDimensionDto(value = rhythm, metric = "active_start_minute", z = 0.5),
        ),
    )

    private fun driftingTimeline(): List<DailyPortraitDto> {
        // 前半 EARLIER 后半 LATER → 真实跨日节律漂移（computeLifeSeason 阈值 0.25 之上）
        val days = (1..40).map { i ->
            val date = java.time.LocalDate.of(2026, 1, 1).plusDays(i - 1L).toString()
            portrait(date, rhythm = if (i <= 20) "EARLIER" else "LATER", screen = if (i > 20) "MORE_FRAGMENTED" else "SIMILAR")
        }
        return days
    }

    private fun assemble(
        scale: JourneyScale,
        portraits: List<DailyPortraitDto>,
    ): JourneyUiState = assembleJourneyUiState(
        scale = scale,
        timeline = PortraitTimelineUiState(days = portraits.size, loading = false, portraits = portraits),
        permissionEnabled = true,
        narrative = null,
        runtimeAvailability = null,
        runtimeDiagnostics = null,
        showEvidence = false,
        intelligenceAvailable = true,
        syncStatus = JourneySyncStatus(permissionEnabled = true),
        journeySeed = 42L,
    )

    @Test
    fun seasonScaleBindsExplanationToSeasonFacts() {
        val portraits = driftingTimeline()
        val state = assemble(JourneyScale.SEASON, portraits)
        val expected = explainLifeSeasonVisual(computeLifeSeason(portraits))

        assertTrue("漂移时间线应有阶段解释", expected.isNotEmpty())
        assertEquals("UI 的 seasonExplanation 必须与 season 事实逐字绑定", expected, state.seasonExplanation)
    }

    @Test
    fun yearScaleBindsExplanationToSeasonFacts() {
        val portraits = driftingTimeline()
        val state = assemble(JourneyScale.YEAR, portraits)
        assertEquals(
            "YEAR 尺度应给出同样的 season 解释（§86 阶段聚合）",
            explainLifeSeasonVisual(computeLifeSeason(portraits)),
            state.seasonExplanation,
        )
    }

    @Test
    fun stableTimelineYieldsOnlyDataDerivedRegularityLine() {
        val portraits = (1..40).map {
            portrait(java.time.LocalDate.of(2026, 1, 1).plusDays(it - 1L).toString(), rhythm = "SIMILAR")
        }
        val state = assemble(JourneyScale.SEASON, portraits)
        // 零方差时间线 → computeLifeSeason 真实算出 more_regular（数据派生，非编造）；
        // 但不得出现漂移/碎片化等不存在的趋势行。
        assertEquals(
            "全 SIMILAR 时间线只应有数据派生的规律性一行，不得编造漂移",
            listOf("生活更规律，ECHO 的构图更稳定。"),
            state.seasonExplanation,
        )
        assertFalse("无漂移时不得出现漂移总述", state.seasonExplanation.any { it.contains("缓慢变化") })
    }

    @Test
    fun fallbackReconstructionDeterministicAndNeverFabricated() {
        val portrait = portrait("2026-03-15", rhythm = "LATER", screen = "MORE_FRAGMENTED")

        // Canonical 缺失 → 画像回退重建；同输入同帧（§84 确定性）
        val frameA = reconstructJourneyFrame(null, portrait, fallbackSeed = 777L, width = 1080f, height = 2340f)
        val frameB = reconstructJourneyFrame(null, portrait, fallbackSeed = 777L, width = 1080f, height = 2340f)
        assertNotNull("画像回退应产生帧", frameA)
        assertEquals("回退重建应确定", frameA, frameB)

        // 回退帧 == journeyDayParams 直接渲染帧（链路一致，无第二套推导）
        val params = requireNotNull(journeyDayParams(portrait))
        assertEquals(
            "回退重建应与 journeyDayParams 直接渲染同帧",
            com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
                spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
                    com.yunjue.echo.mind.journey.JourneyOrganismVisuals.genomeFromParams(params, 777L),
                    com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
                    JOURNEY_CANONICAL_TIME_SECONDS,
                ),
                width = 1080f,
                height = 2340f,
                options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
                    maturityName = com.yunjue.echo.mind.model.echoMaturity(portrait.baselineDays).name,
                ),
            ),
            frameA,
        )

        // 双缺失 → null（不伪造历史）
        assertNull("快照与画像双缺失时必须不伪造", reconstructJourneyFrame(null, null, 777L, 1080f, 2340f))
    }
}
