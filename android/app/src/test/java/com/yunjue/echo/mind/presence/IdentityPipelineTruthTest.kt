package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.echoMaturity
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.EchoMomentState
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoLifeSeason
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoDailyComposition
import com.yunjue.echo.mind.model.BehaviorState

import com.yunjue.echo.mind.journey.JourneyCanonicalCodec
import com.yunjue.echo.mind.journey.JOURNEY_CANONICAL_TIME_SECONDS
import com.yunjue.echo.mind.journey.buildCanonicalDay
import com.yunjue.echo.mind.journey.reconstructJourneyFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 51（ADR-058）§52 真值审计锚点——Identity 全链路数据流：
 *
 * 1. 四层（Identity / LifeSeason / DailyComposition / MomentState）真实改变最终视觉参数；
 * 2. Canonical Daily State 编解码往返 → 历史重建与当日渲染同帧（§84 确定性）；
 * 3. 同一 Identity 种子跨成熟度（Day 1 vs Day 180）保持同一个 ECHO（§53 连续性）。
 *
 * 与 EchoIdentityTest（纯函数层）互补：本测试锚定「装配状态 → 映射 → 帧/快照」的管道真值。
 */
class IdentityPipelineTruthTest {

    private fun genome(seed: Long, motion: Float, symmetry: Float, hue: Float, topology: Float) =
        EchoIdentityGenome(
            seed = seed,
            accentHue = hue,
            colorFamily = 1,
            textureFamily = 2,
            coreTopology = topology,
            symmetryTendency = symmetry,
            orbitGeometry = 0.4f,
            motionPersonality = motion,
        )

    private fun state(
        seed: Long = 42L,
        maturity: EchoMaturity = echoMaturity(30),
        drift: Float = 0.2f,
        motion: Float = 0.5f,
        symmetry: Float = 0.6f,
        dailyFlow: Float = 0.55f,
        dailyCoherence: Float = 0.7f,
        dailyOpenness: Float = 0.7f,
        breathing: Float = 0f,
    ) = EchoPresenceState(
        updatedAt = java.time.Instant.EPOCH,
        maturity = maturity,
        rhythmState = RhythmState(activityLevel = 0.5f, regularity = 0.6f, coverage = 0.8f),
        behaviorState = BehaviorState(density = 0.5f, deviation = 0.3f),
        confidence = 0.7f,
        identityGenome = genome(seed, motion, symmetry, hue = 0.55f, topology = 0.6f),
        lifeSeason = EchoLifeSeason(phaseIndex = 2, drift = drift),
        dailyComposition = EchoDailyComposition(
            flowSpeed = dailyFlow,
            coherence = dailyCoherence,
            turbulence = 0.2f,
            particleDensity = 0.5f,
            coreOpenness = dailyOpenness,
            pulsePeriod = 0f,
            depth = 0.6f,
            brightness = 0.8f,
            contrast = 0.5f,
            accentIntensity = 0.4f,
            structureComplexity = 0.5f,
        ),
        momentState = EchoMomentState(breathingPeriod = breathing, noiseScale = 0.1f),
    )

    // ===== 1. 四层真实进入视觉参数 =====

    private fun organismFrameFor(
        params: com.yunjue.echo.mind.presence.EchoVisualParameters,
        seed: Long,
        timeSeconds: Float,
        width: Float,
        height: Float,
        maturityName: String = "KNOWN",
    ) = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
        spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
            com.yunjue.echo.mind.journey.JourneyOrganismVisuals.genomeFromParams(params, seed),
            com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
            timeSeconds,
        ),
        width = width,
        height = height,
        options = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.EchoRenderOptions(
            maturityName = maturityName,
        ),
    )

    @Test
    fun fourLayersReachVisualParameters() {
        val base = state()
        val baseParams = EchoVisualMapper.map(base, hourOfDay = 12f, surface = SurfaceMode.APP)

        // LifeSeason.drift → 慢湍流下限（§56 进入视觉但不突变）
        val highDrift = state(drift = 0.9f)
        val driftParams = EchoVisualMapper.map(highDrift, 12f, SurfaceMode.APP)
        assertTrue(
            "lifeSeason.drift 升高应抬高 turbulence（真实数据流）",
            driftParams.turbulence > baseParams.turbulence,
        )

        // IdentityGenome.motionPersonality → 长效流动调制（§53）
        val lively = state(motion = 0.9f)
        val livelyParams = EchoVisualMapper.map(lively, 12f, SurfaceMode.APP)
        assertTrue("运动人格应改变 flowSpeed", livelyParams.flowSpeed > baseParams.flowSpeed)

        // IdentityGenome.symmetryTendency → coherence
        val symmetric = state(symmetry = 0.95f)
        val symmetricParams = EchoVisualMapper.map(symmetric, 12f, SurfaceMode.APP)
        assertTrue("对称倾向应改变 coherence", symmetricParams.coherence > baseParams.coherence)

        // DailyComposition → 密度/开放度
        val dense = state(dailyFlow = 0.9f, dailyOpenness = 0.2f)
        val denseParams = EchoVisualMapper.map(dense, 12f, SurfaceMode.APP)
        assertTrue("日构图 flow 应改变粒子流动", denseParams.flowSpeed > baseParams.flowSpeed)
        assertTrue("日构图 openness 应改变核心开放度", denseParams.coreOpenness < baseParams.coreOpenness)

        // MomentState.breathingPeriod → 帧呼吸周期（§59 优先）
        val breathing = state(breathing = 4.2f)
        val breathingParams = EchoVisualMapper.map(breathing, 12f, SurfaceMode.APP)
        assertEquals("分钟级呼吸周期应优先进入参数", 4.2f, breathingParams.pulsePeriodSeconds, 1e-4f)
    }

    // ===== 2. Canonical 编解码往返 → 历史重建同帧（§84） =====

    @Test
    fun canonicalRoundtripReconstructsSameFrame() {
        val original = state(seed = 20260815L)
        val params = EchoVisualMapper.map(original, hourOfDay = 14f, surface = SurfaceMode.APP)
        val directFrame = organismFrameFor(
            params, 20260815L, JOURNEY_CANONICAL_TIME_SECONDS, 1080f, 2340f, original.maturity.name,
        )

        val day = buildCanonicalDay(
            date = "2026-08-15",
            state = original,
            keyEvidenceIds = listOf("evt_a", "evt_b"),
            createdAtEpochMs = 1L,
        )
        val decoded = JourneyCanonicalCodec.decode(JourneyCanonicalCodec.encode(day))
        requireNotNull(decoded)

        assertEquals("编解码往返应保留视觉种子", original.identityGenome.seed, decoded.visualSeed)
        assertEquals("编解码往返应保留 identity 引用", original.identityGenome, decoded.identityReference)

        val reconstructed = reconstructJourneyFrame(decoded, fallbackPortrait = null, fallbackSeed = 0L, 1080f, 2340f)
        requireNotNull(reconstructed)
        assertEquals("历史重建帧应与当日渲染帧完全一致（确定性）", directFrame, reconstructed)
    }

    // ===== 3. 同一 Identity 跨成熟度连续性（§53） =====

    @Test
    fun sameSeedAcrossMaturityIsSameEcho() {
        // 无日构图层（旧快照/回退路径）：核心开放度由成熟度决定 → 观察成长但不重置身份
        val day1 = state(seed = 777L, maturity = echoMaturity(3), dailyFlow = 0f, dailyCoherence = 0f, dailyOpenness = 0f)
        val day180 = state(seed = 777L, maturity = echoMaturity(180), dailyFlow = 0f, dailyCoherence = 0f, dailyOpenness = 0f)

        val frameDay1 = organismFrameFor(
            EchoVisualMapper.map(day1, 12f, SurfaceMode.APP),
            777L, JOURNEY_CANONICAL_TIME_SECONDS, 1080f, 2340f, day1.maturity.name,
        )
        val frameDay180 = organismFrameFor(
            EchoVisualMapper.map(day180, 12f, SurfaceMode.APP),
            777L, JOURNEY_CANONICAL_TIME_SECONDS, 1080f, 2340f, day180.maturity.name,
        )

        // 颜色族（Identity 决定，非状态决定）跨成熟度不变 → 同一个 ECHO
        assertEquals("主色应由 Identity 决定：Day1/Day180 背景中心色一致", frameDay1.ambientField.centerColor, frameDay180.ambientField.centerColor)
        assertEquals("主色应由 Identity 决定：Day1/Day180 强调色一致", frameDay1.frontMembrane.color, frameDay180.frontMembrane.color)
        // 成熟度只塑形开放度，不重置身份
        assertNotEquals("成熟度应塑形开放度（成长），但颜色保持连续", frameDay1.coreCavity.radiusFraction, frameDay180.coreCavity.radiusFraction)
    }
}
