package com.yunjue.echo.mind.wearable

import com.yunjue.echo.mind.model.EchoDailyComposition
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * SAME ECHO VISUAL — Visual Fixtures（ECHO_WRIST_CONTRACT §Visual Review）。
 *
 * 确定性视觉状态测试：同一 Profile / same state 在腕上投影必须稳定且保留
 * topology / symmetry / orbit / motion / texture / colorFamily / maturity。
 *
 * Fixture 集：SEED / DISCOVERING / KNOWN / MATURE / QUIET / ACTIVE /
 * LOW_CONFIDENCE / DISCONNECTED / BREATHING。
 */
class WearPresenceProjectorTest {

    private fun identity(
        topology: Float = 0.5f,
        symmetry: Float = 0.5f,
        orbit: Float = 0.5f,
        motion: Float = 0.5f,
        texture: Int = 1,
        colorFamily: Int = 2,
        accent: Float = 0.6f,
    ): EchoIdentityGenome = EchoIdentityGenome(
        seed = 42L,
        accentHue = accent,
        colorFamily = colorFamily,
        textureFamily = texture,
        coreTopology = topology,
        symmetryTendency = symmetry,
        orbitGeometry = orbit,
        motionPersonality = motion,
    )

    private fun state(
        maturity: EchoMaturity,
        id: EchoIdentityGenome = identity(),
        composition: EchoDailyComposition = EchoDailyComposition(
            flowSpeed = 0.5f,
            coherence = 0.7f,
            turbulence = 0.2f,
            particleDensity = 0.4f,
            brightness = 0.6f,
        ),
    ): EchoPresenceState = EchoPresenceState(
        updatedAt = Instant.EPOCH,
        maturity = maturity,
        identityGenome = id,
        dailyComposition = composition,
    )

    @Test
    fun seedFixture_projectsSeedMaturityAndIdentity() {
        val p = WearPresenceProjector.project(state(EchoMaturity.SEED))
        assertEquals("SEED", p.maturity)
        assertEquals(0.5f, p.identity.topology, 1e-6f)
    }

    @Test
    fun discoveringKnownMatureFixtures_preserveMaturityLanguage() {
        assertEquals("DISCOVERING", WearPresenceProjector.project(state(EchoMaturity.DISCOVERING)).maturity)
        assertEquals("EMERGING", WearPresenceProjector.project(state(EchoMaturity.EMERGING)).maturity)
        assertEquals("KNOWN", WearPresenceProjector.project(state(EchoMaturity.KNOWN)).maturity)
        assertEquals("MATURE", WearPresenceProjector.project(state(EchoMaturity.MATURE)).maturity)
    }

    @Test
    fun quietFixture_lowMotionPersonality_mapsToQuiet() {
        val p = WearPresenceProjector.project(state(EchoMaturity.KNOWN, id = identity(motion = 0.1f)))
        assertEquals("QUIET", p.surface.motionLevel)
    }

    @Test
    fun activeFixture_highMotionPersonality_mapsToLively() {
        val p = WearPresenceProjector.project(state(EchoMaturity.KNOWN, id = identity(motion = 0.9f)))
        assertEquals("LIVELY", p.surface.motionLevel)
    }

    @Test
    fun lowConfidenceFixture_lowPowerOrReducedMotion_quietRegardless() {
        val lively = identity(motion = 0.9f)
        val lowPower = WearPresenceProjector.project(
            state(EchoMaturity.KNOWN, id = lively),
            lowPower = true,
        )
        assertEquals("QUIET", lowPower.surface.motionLevel)
        assertTrue(lowPower.surface.lowPower)
        val reduced = WearPresenceProjector.project(
            state(EchoMaturity.KNOWN, id = lively),
            reducedMotion = true,
        )
        assertEquals("QUIET", reduced.surface.motionLevel)
        assertTrue(reduced.surface.reducedMotion)
    }

    @Test
    fun disconnectedFixture_identityContinuityIsDeterministic() {
        // 断连时手环保留缓存 Identity：同一 state 反复投影必须逐位一致（确定性）。
        val s = state(EchoMaturity.KNOWN)
        val a = WearPresenceProjector.project(s)
        val b = WearPresenceProjector.project(s)
        assertEquals(a, b)
    }

    @Test
    fun breathingFixture_momentOnly_identityNeverChanges() {
        // 呼吸 Action 只改变 moment/表面节奏，Identity 投影必须不变（同一个 ECHO）。
        val s = state(EchoMaturity.KNOWN)
        val before = WearPresenceProjector.project(s).identity
        val during = WearPresenceProjector.project(
            s.copy(dailyComposition = s.dailyComposition.copy(coherence = 0.9f, flowSpeed = 0.2f)),
        ).identity
        assertEquals(before, during)
    }

    @Test
    fun projectionClampsAllRanges() {
        val wild = identity(topology = 3f, symmetry = -1f, orbit = 7f, motion = -2f, texture = 9, colorFamily = 12, accent = 4f)
        val p = WearPresenceProjector.project(state(EchoMaturity.KNOWN, id = wild))
        assertEquals(1f, p.identity.topology, 1e-6f)
        assertEquals(0f, p.identity.symmetry, 1e-6f)
        assertEquals(1f, p.identity.orbit, 1e-6f)
        assertEquals(0f, p.identity.motion, 1e-6f)
        assertEquals(3, p.identity.texture)
        assertEquals(4, p.identity.colorFamily)
        assertEquals(1f, p.identity.accent, 1e-6f)
    }

    @Test
    fun momentProjectionMapsDailyCompositionFields() {
        val s = state(
            EchoMaturity.MATURE,
            composition = EchoDailyComposition(
                flowSpeed = 0.3f, coherence = 0.9f, turbulence = 0.1f,
                particleDensity = 0.5f, brightness = 0.8f,
            ),
        )
        val m = WearPresenceProjector.project(s).moment
        assertEquals(0.3f, m.flow, 1e-6f)
        assertEquals(0.9f, m.coherence, 1e-6f)
        assertEquals(0.5f, m.density, 1e-6f)
        assertEquals(0.1f, m.turbulence, 1e-6f)
        assertEquals(0.8f, m.brightness, 1e-6f)
    }

    @Test
    fun identityFamilySharedWithPhoneGenome() {
        // 与手机 EchoIdentityGenome 共享全部 7 个身份维度（不新增、不丢失）。
        val id = identity(topology = 0.62f, symmetry = 0.31f, orbit = 0.77f, motion = 0.44f, texture = 3, colorFamily = 4, accent = 0.88f)
        val p = WearPresenceProjector.projectIdentity(id)
        assertEquals(id.coreTopology, p.topology, 1e-6f)
        assertEquals(id.symmetryTendency, p.symmetry, 1e-6f)
        assertEquals(id.orbitGeometry, p.orbit, 1e-6f)
        assertEquals(id.motionPersonality, p.motion, 1e-6f)
        assertEquals(id.textureFamily, p.texture)
        assertEquals(id.colorFamily, p.colorFamily)
        assertEquals(id.accentHue, p.accent, 1e-6f)
    }
}
