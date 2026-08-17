package com.yunjue.echo.mind

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.model.BehaviorState
import com.yunjue.echo.mind.model.EchoDailyComposition
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoLifeSeason
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoMomentState
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.model.RhythmState
import com.yunjue.echo.mind.model.SensingRuntimeStatus
import com.yunjue.echo.mind.presence.EchoPresenceCodec
import com.yunjue.echo.mind.presence.EchoVisualMapper
import com.yunjue.echo.mind.security.JvmTestFieldCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * ERA 31 R16：进程死亡恢复锚点回归（Robolectric，SDK 35）。
 *
 * PresenceRepository 每次刷新把快照经 [AppPreferences.echoPresenceSnapshot] 落盘（commit 同步写）；
 * Wallpaper/Dream 进程启动时读同一个 key 解码。本测试把「写进程」与「读进程」拆成两个
 * 独立的 AppPreferences 实例，锚定恢复链路：写后即可读、identity seed 不变、恢复后的 ECHO
 * 与前台渲染同一帧。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EchoPresenceSnapshotRecoveryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 隔离用例：清空共享 prefs 文件，避免用例间残留
        context.getSharedPreferences(AppPreferences.PREFS_FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

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

    @Test
    fun snapshotSurvivesWriterProcessDeath() {
        // 「写进程」：PresenceRepository 的落盘路径（AppPreferences.echoPresenceSnapshot setter）
        AppPreferences(context, JvmTestFieldCipher()).echoPresenceSnapshot =
            EchoPresenceCodec.encode(fullState())

        // 「读进程」：Wallpaper/Dream 启动时的独立实例 + 同一 key
        val recovered = EchoPresenceCodec.decode(
            AppPreferences(context, JvmTestFieldCipher()).echoPresenceSnapshot
        ) ?: error("进程重启后快照应可读")

        assertEquals("恢复的 Presence 应与写进程完全一致", fullState(), recovered)

        // 同一个 ECHO：恢复后渲染与前台同帧（identity seed 是恢复锚点的核心）
        val frameLive = organismFrameFor(
            EchoVisualMapper.map(fullState(), 12f),
            fullState().identityGenome.seed, 600f, 1080f, 2340f,
        )
        val frameRecovered = organismFrameFor(
            EchoVisualMapper.map(recovered, 12f),
            recovered.identityGenome.seed, 600f, 1080f, 2340f,
        )
        assertEquals("重启后恢复的 ECHO 渲染与前台同帧", frameLive, frameRecovered)
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
    fun writingNullRemovesRecoveryAnchor() {
        val preferences = AppPreferences(context, JvmTestFieldCipher())
        preferences.echoPresenceSnapshot = EchoPresenceCodec.encode(fullState())
        preferences.echoPresenceSnapshot = null

        assertNull("null 写应移除 key（fail-closed → 中性占位）", preferences.echoPresenceSnapshot)
        assertNull(
            "独立实例也应读不到（key 已从磁盘删除）",
            AppPreferences(context, JvmTestFieldCipher()).echoPresenceSnapshot,
        )
    }
}
