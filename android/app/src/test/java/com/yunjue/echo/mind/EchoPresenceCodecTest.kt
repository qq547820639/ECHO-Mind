package com.yunjue.echo.mind
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.EchoMomentState
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoLifeSeason
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoDailyComposition
import com.yunjue.echo.mind.model.BehaviorState

import com.yunjue.echo.mind.presence.EchoPresenceCodec
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.presence.SurfaceMode
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ERA 3 + ERA 53（§52 审计第 3 轮）：Presence 快照编解码回归——
 * Wallpaper/Dream 只读该快照，fail-closed 解析；v2 补齐 Identity 四层跨进程真值。
 */
class EchoPresenceCodecTest {

    private fun fullState() = EchoPresenceState(
        updatedAt = Instant.ofEpochSecond(1753632000),
        sensingStatus = SensingRuntimeStatus.ACTIVE,
        maturity = EchoMaturity.KNOWN,
        rhythmState = RhythmState(activityLevel = 0.62f, rhythmDelta = 0.11f, regularity = 0.55f, coverage = 0.71f),
        behaviorState = BehaviorState(density = 0.5f, deviation = 0.3f),
        confidence = 0.68f,
        identityGenome = EchoIdentityGenome(
            seed = 987654321L, accentHue = 0.6f, colorFamily = 2, textureFamily = 3,
            coreTopology = 0.7f, symmetryTendency = 0.8f, orbitGeometry = 0.4f, motionPersonality = 0.65f,
        ),
        lifeSeason = EchoLifeSeason(
            phaseIndex = 2, drift = 0.35f, rhythmShift = "later", screenFragmentation = "more_fragmented",
            activityVariability = "more_variable", mobilityTrend = "less_mobile", regularityTrend = "less_regular",
        ),
        dailyComposition = EchoDailyComposition(
            flowSpeed = 0.55f, coherence = 0.7f, turbulence = 0.2f, particleDensity = 0.5f,
            coreOpenness = 0.7f, dispersion = 0.4f, pulsePeriod = 4.2f, depth = 0.6f,
            brightness = 0.8f, contrast = 0.5f, accentIntensity = 0.4f, structureComplexity = 0.5f,
        ),
        momentState = EchoMomentState(breathingPeriod = 3.9f, noiseScale = 0.15f),
    )

    private fun organismFrameFor(
        params: com.yunjue.echo.mind.presence.EchoVisualParameters,
        seed: Long,
        timeSeconds: Float,
        width: Float,
        height: Float,
    ) = com.yunjue.echo.mind.visual.render.OrganismFrameComputer.compute(
        spec = com.yunjue.echo.mind.visual.surface.SurfacePolicy.crop(
            com.yunjue.echo.mind.journey.JourneyOrganismVisuals.genomeFromParams(params, seed),
            com.yunjue.echo.mind.visual.surface.EchoSurface.APP_PRIVATE,
            timeSeconds,
        ),
        width = width,
        height = height,
    )

    @Test
    fun roundTripPreservesFullFourLayers() {
        val state = fullState()
        val encoded = EchoPresenceCodec.encode(state)
        val decoded = EchoPresenceCodec.decode(encoded) ?: error("decode 不应失败")
        assertEquals("v2 往返应完整保留四层状态", state, decoded)

        // 跨进程渲染同帧：解码后的状态映射+渲染与编码前一致（进程死亡后同一个 ECHO）
        val frameLive = organismFrameFor(
            EchoVisualMapper.map(state, 12f, SurfaceMode.APP),
            state.identityGenome.seed, 600f, 1080f, 2340f,
        )
        val frameRecovered = organismFrameFor(
            EchoVisualMapper.map(decoded, 12f, SurfaceMode.APP),
            decoded.identityGenome.seed, 600f, 1080f, 2340f,
        )
        assertEquals("进程重启后快照恢复的 ECHO 应与前台同帧", frameLive, frameRecovered)
    }

    @Test
    fun v1SnapshotDecodesWithStructuralDefaults() {
        // 旧版 13 字段快照（ERA 3 格式）仍可解析；新字段取结构默认（与 v1 时代语义一致）
        val v1 = "v1|1753632000|ACTIVE|KNOWN|0.62|0.55|0.5|0.3|0.68|0.71|987654321|0.6|0.11"
        val decoded = EchoPresenceCodec.decode(v1) ?: error("v1 快照应可解")
        assertEquals(987654321L, decoded.identityGenome.seed)
        assertEquals(0.11f, decoded.rhythmState.rhythmDelta)
        assertEquals("v1 无四层字段 → 结构默认", EchoIdentityGenome(seed = 987654321L, accentHue = 0.6f), decoded.identityGenome)
        assertEquals(0f, decoded.lifeSeason.drift)
        assertEquals(0f, decoded.dailyComposition.flowSpeed)
    }

    @Test
    fun decodeIsFailClosed() {
        assertNull(EchoPresenceCodec.decode(null))
        assertNull(EchoPresenceCodec.decode(""))
        assertNull(EchoPresenceCodec.decode("garbage"))
        assertNull(EchoPresenceCodec.decode("v9|1|2|3"))
        // 未知枚举名 → null（向前兼容失败即中性占位）
        val badStatus = fullState().let {
            EchoPresenceCodec.encode(it).replace("ACTIVE", "WEIRD_STATE")
        }
        assertNull(EchoPresenceCodec.decode(badStatus))
        // 截断的 v1 / v2 → null
        assertNull(EchoPresenceCodec.decode("v1|1753632000|ACTIVE"))
        assertNull(EchoPresenceCodec.decode(EchoPresenceCodec.encode(fullState()).split("|").take(20).joinToString("|")))
    }

    @Test
    fun snapshotContainsNoNarrativeText() {
        // 快照只含数值与枚举，绝不含叙事文字（锁屏 Public Safe 由构造保证）
        val encoded = EchoPresenceCodec.encode(fullState())
        for (word in listOf("焦虑", "情绪", "压力", "安静", "活跃", "叙事")) {
            assertTrue("快照不应含叙事词：$word", !encoded.contains(word))
        }
    }
}
