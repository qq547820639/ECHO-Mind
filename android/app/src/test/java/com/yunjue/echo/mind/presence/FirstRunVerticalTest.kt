package com.yunjue.echo.mind.presence
import com.yunjue.echo.mind.model.echoMaturity
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.BehaviorState

import com.yunjue.echo.mind.model.SensingRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 80（ADR-068）——First-Run 纵向切面 Day-0 锚点：
 * 新装无授权无数据 → AmbientEngine 中性 → 安装种子派生 Identity →
 * ECHO 当场苏醒（SEED、非空白、确定性；「不接模型 ECHO 仍然存在」的视觉基础）。
 */
class FirstRunVerticalTest {

    /** 镜像 PresenceRepository.assemble 的 Day-0 无数据输入（授权前同样可苏醒）。 */
    private fun wakeUpPresence(seed: Long): EchoPresenceState {
        val ambient = AmbientEngine.compute(today = null, baseline = null)
        val identity = deriveIdentityGenome(
            seed = seed,
            baselineStability = ambient.vector.regularity,
            motionPreference = PresenceMotionLevel.DEFAULT,
        )
        return EchoPresenceState(
            sensingStatus = SensingRuntimeStatus.NOT_AUTHORIZED,
            maturity = echoMaturity(0),
            rhythmState = RhythmState(
                activityLevel = ambient.vector.activation,
                rhythmDelta = 0f,
                regularity = ambient.vector.regularity,
                coverage = ambient.coverage,
            ),
            behaviorState = BehaviorState(density = ambient.vector.density, deviation = 0f),
            confidence = 0.3f,
            identityGenome = identity,
        )
    }

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
    fun freshInstallWakesUpNeutralDeterministicEcho() {
        val ambient = AmbientEngine.compute(today = null, baseline = null)
        assertEquals(AmbientState.UNKNOWN, ambient.state)
        val state = wakeUpPresence(seed = 424_242L)
        assertEquals(EchoMaturity.SEED, state.maturity)
        val params = computeVisualParameters(state, 13f, SurfaceMode.APP)
        val frame = organismFrameFor(params, state.identityGenome.seed, 12f, 320f, 320f, state.maturity.name)
        // ECHO 苏醒而非空白：有粒子、有空心核、有强调色（前膜色）
        assertTrue("首帧必须有粒子（ECHO 可见）", frame.particles.isNotEmpty())
        assertTrue("空心核腔体必须为正", frame.coreCavity.radiusFraction > 0f)
        assertTrue("强调色必须非零", frame.frontMembrane.color != 0)
        // 完全确定性：同输入两次同帧
        assertEquals(frame, organismFrameFor(params, state.identityGenome.seed, 12f, 320f, 320f, state.maturity.name))
    }

    @Test
    fun identitySeedStableAcrossDaysAndStates() {
        // 同一安装种子跨时间同一 ECHO（§53：Day 1/Day 30/Day 180 明显同一个）
        val day0 = wakeUpPresence(seed = 777L)
        val dayN = wakeUpPresence(seed = 777L)
        assertEquals(day0.identityGenome, dayN.identityGenome)
        assertNotEquals(day0.identityGenome, wakeUpPresence(seed = 778L).identityGenome)
    }
}
