package com.yunjue.echo.mind.wearable

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.actions.EchoActionKind
import com.yunjue.echo.mind.actions.EchoActionRuntime
import com.yunjue.echo.mind.di.WearableContainer
import com.yunjue.echo.mind.model.EchoDailyComposition
import com.yunjue.echo.mind.model.EchoIdentityGenome
import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState
import com.yunjue.echo.mind.ports.PresenceStateSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * ERA 33 —— Production Integration Test（"神经真的接通"）。
 *
 * 用真实生产对象图验证完整链，不再只靠 isolated WearableRuntime unit tests：
 *
 *   Application 组合（AppContainer 同款装配：WearableContainer 最小依赖 + start()）
 *   + FakeWearablePlatform + Fake Presence source
 *   + real WearableRuntime + real EchoActionRuntime。
 *
 * 覆盖（ECHO_WRIST_CONTRACT §Production Wiring）：
 *   1. start() → Runtime active；重复 start 幂等；
 *   2. 平台 CONNECTED → Presence envelope 自动推送；
 *   3. 注入 REQUEST_WHY → Runtime 自动消费 → 响应（headline）发出；
 *   4. 注入 START_BREATHING → 同一个 EchoActionRuntime 变化；
 *   5. 手机侧 Action 启动 → 手环收到对应状态推送；
 *   6. 注入腕上观察 → Observation sink 收到（consent 关时绝不记录）；
 *   7. 断开 → 不崩溃；
 *   8. 重连 → 最新 Presence 重推；
 *   9. 重复消息 → 只处理一次；
 *   10. 乱序/伪造 Presence（手环发来）→ 安全忽略。
 *
 * 同时锁定 Phase 3 触觉管道：prefs.hapticsEnabled → envelope.surface.hapticsEnabled。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearableApplicationIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakePresenceSource(initial: EchoPresenceState) : PresenceStateSource {
        override val state: StateFlow<EchoPresenceState?> = MutableStateFlow(initial)
    }

    private fun presenceState(): EchoPresenceState = EchoPresenceState(
        updatedAt = Instant.EPOCH,
        maturity = EchoMaturity.KNOWN,
        identityGenome = EchoIdentityGenome(
            seed = 42L,
            accentHue = 0.6f,
            colorFamily = 2,
            textureFamily = 1,
            coreTopology = 0.5f,
            symmetryTendency = 0.5f,
            orbitGeometry = 0.5f,
            motionPersonality = 0.5f,
        ),
        dailyComposition = EchoDailyComposition(
            flowSpeed = 0.5f,
            coherence = 0.7f,
            turbulence = 0.2f,
            particleDensity = 0.4f,
            brightness = 0.6f,
        ),
        confidence = 0.8f,
    )

    private fun bandAction(messageId: String, command: String): String =
        WearMessageCodec.encode(
            WearMessage.Action(
                WearActionEnvelope(messageId = messageId, generatedAt = 1L, command = command)
            )
        )

    private fun bandObservation(messageId: String): String =
        WearMessageCodec.encode(
            WearMessage.Observation(
                WearObservationEnvelope(
                    messageId = messageId,
                    generatedAt = 1L,
                    motion = WearMotionSummary(
                        motionEnergy = 0.4f,
                        movementClass = "WALKING",
                        sampleCoverage = 0.95f,
                        quality = "GOOD",
                        windowStartMs = 1_000L,
                        windowEndMs = 11_000L,
                    ),
                )
            )
        )

    private fun bandPresence(messageId: String): String =
        WearMessageCodec.encode(
            WearMessage.Presence(
                WearPresenceEnvelope(
                    messageId = messageId,
                    generatedAt = 1L,
                    source = WearMessageSource.XIAOMI_BAND,
                    revision = 99L,
                    expiresAt = Long.MAX_VALUE,
                    maturity = "SEED",
                    identity = WearIdentityProjection(0.1f, 0.1f, 0.1f, 0.1f, 0, 0, 0.1f),
                    moment = WearMomentProjection(0.1f, 0.1f, 0.1f, 0.1f, 0.1f),
                    surface = WearSurfaceParams(motionLevel = "DEFAULT", lowPower = false, reducedMotion = false),
                )
            )
        )

    /** 轮询等待条件（真实 Dispatchers.Default；超时即断言失败）。 */
    // 全量并行负载下协程调度延迟实测可超过 15s（Dispatchers 饥饿）；语义不变，只放宽等待上限。
    private fun awaitUntil(timeoutMs: Long = 30_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("条件在 ${timeoutMs}ms 内未满足", condition())
    }

    private fun lastOutboundPresence(platform: FakeWearablePlatformAdapter): WearPresenceEnvelope {
        val decoded = WearMessageCodec.decode(platform.outbound.last())
        assertTrue("出站最后一条应是 Presence", decoded is WearMessageCodec.DecodeResult.Ok)
        val message = (decoded as WearMessageCodec.DecodeResult.Ok).message
        assertTrue("出站最后一条应是 Presence", message is WearMessage.Presence)
        return (message as WearMessage.Presence).envelope
    }

    private fun composeApplication(): Triple<WearableContainer, FakeWearablePlatformAdapter, EchoActionRuntime> {
        val platform = FakeWearablePlatformAdapter()
        val actionRuntime = EchoActionRuntime()
        val wearable = WearableContainer(
            applicationContext = context,
            presenceSource = FakePresenceSource(presenceState()),
            actionRuntime = actionRuntime,
            platformProvider = { platform },
        )
        return Triple(wearable, platform, actionRuntime)
    }

    @Test
    fun applicationComposition_startsRuntimeOnce_andIsIdempotent() {
        val (wearable, _, _) = composeApplication()
        wearable.start()
        assertNotNull(wearable.runtime.state.value)
        // 幂等：重复 start 不产生第二组 collectors / 不抛异常
        wearable.start()
        wearable.start()
        assertEquals(WearableConnectionState.DISCONNECTED, wearable.runtime.state.value.connection)
    }

    @Test
    fun platformConnected_presenceEnvelopeAutomaticallySent() {
        val (wearable, platform, _) = composeApplication()
        wearable.start()
        platform.setConnected(true)
        awaitUntil { platform.outbound.isNotEmpty() }
        val presence = lastOutboundPresence(platform)
        assertEquals("KNOWN", presence.maturity)
        assertEquals(WearMessageSource.PHONE, presence.source)
        assertTrue(wearable.runtime.state.value.isConnected)
        // 触觉默认 SILENT（Phase 3.1）：默认 envelope 必须 hapticsEnabled=false
        assertFalse(presence.surface.hapticsEnabled)
    }

    @Test
    fun inboundRequestWhy_runtimeAutomaticallyConsumes_responseSent() {
        val (wearable, platform, _) = composeApplication()
        wearable.start()
        // 首次请求（尚未推送过）：无防抖 → 立即响应（headlineRequested → PUBLIC_SAFE headline）
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(bandAction("why-1", "REQUEST_WHY"))
        }
        awaitUntil { platform.outbound.isNotEmpty() }
        val presence = lastOutboundPresence(platform)
        assertNotNull("REQUEST_WHY 应带克制公开表达", presence.publicHeadline)
    }

    @Test
    fun inboundStartBreathing_sameEchoActionRuntimeChanges() {
        val (wearable, platform, actionRuntime) = composeApplication()
        wearable.start()
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(bandAction("a-1", "START_BREATHING"))
        }
        awaitUntil { actionRuntime.running.value == EchoActionKind.BREATHING }
        // 同一个 Action：手机侧与手环侧共用同一实例（无第二套 Action Runtime）
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(bandAction("a-2", "START_PAUSE"))
        }
        awaitUntil { actionRuntime.running.value == EchoActionKind.PAUSE }
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(bandAction("a-3", "STOP_ACTION"))
        }
        awaitUntil { actionRuntime.running.value == null }
    }

    @Test
    fun phoneSideActionStart_wristReceivesActionStatePush() {
        val (wearable, platform, actionRuntime) = composeApplication()
        wearable.start()
        platform.setConnected(true)
        awaitUntil { platform.outbound.isNotEmpty() }
        val before = platform.outbound.size
        actionRuntime.start(EchoActionKind.BREATHING)
        awaitUntil { platform.outbound.size > before }
        val presence = lastOutboundPresence(platform)
        assertTrue(
            "动作状态推送应携带可用动作",
            presence.availableActions.contains("START_BREATHING") && presence.availableActions.contains("START_PAUSE"),
        )
    }

    @Test
    fun wristObservation_reachesNeutralLog_onlyWithConsent() {
        val (wearable, platform, _) = composeApplication()
        wearable.start()
        // consent 关（默认）：观察必须被 sink 拒绝（不记录）
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(bandObservation("obs-1"))
        }
        Thread.sleep(200)
        assertEquals(0, wearable.observationLog.size)

        // consent 开：观察进中立日志（观察 ≠ Presence）
        wearable.prefs.motionSummaryEnabled = true
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(bandObservation("obs-2"))
        }
        awaitUntil { wearable.observationLog.size == 1 }

        // 重复消息（同 messageId）→ 只处理一次
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(bandObservation("obs-2"))
        }
        Thread.sleep(200)
        assertEquals(1, wearable.observationLog.size)
    }

    @Test
    fun disconnectReconnect_noCrash_latestPresenceRePushed() {
        val (wearable, platform, _) = composeApplication()
        wearable.start()
        platform.setConnected(true)
        awaitUntil { platform.outbound.isNotEmpty() }
        val before = platform.outbound.size
        val pushedBefore = wearable.runtime.state.value.outboundPushCount

        kotlinx.coroutines.runBlocking { platform.disconnect() }
        awaitUntil { !wearable.runtime.state.value.isConnected }
        assertEquals(before, platform.outbound.size) // 断开不推送

        kotlinx.coroutines.runBlocking { platform.connect() }
        awaitUntil { platform.outbound.size > before }
        val presence = lastOutboundPresence(platform)
        assertTrue("重连后 revision 单调", presence.revision >= 1L)

        // 长跑仪表：断连计数 + 推送计数单调
        val state = wearable.runtime.state.value
        assertTrue("disconnectCount 应 ≥ 1", state.disconnectCount >= 1L)
        assertTrue("outboundPushCount 应增长", state.outboundPushCount > pushedBefore)
    }

    @Test
    fun longRunInstrumentation_inboundAndObservationCountersMonotonic() {
        val (wearable, platform, _) = composeApplication()
        wearable.start()
        wearable.prefs.motionSummaryEnabled = true
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(bandObservation("obs-1"))
            platform.injectFromBand(bandObservation("obs-2"))
            platform.injectFromBand(bandObservation("obs-2")) // 重复
            platform.injectFromBand("malformed-not-json")
        }
        // 竞态修复：等待条件必须覆盖传输层最后一条（malformed）消息的处理——
        // 观察日志只记合法去重结果，2 条观察就绪时第 4 条入站计数可能尚未入账。
        awaitUntil { wearable.runtime.state.value.inboundMessageCount >= 4L }
        awaitUntil { wearable.observationLog.size == 2 }
        val state = wearable.runtime.state.value
        assertEquals("入站计数按传输层计数（含重复/损坏消息）", 4L, state.inboundMessageCount)
        assertEquals("观察日志只记合法去重后的观察", 2, wearable.observationLog.size)
    }

    @Test
    fun bandForgedPresence_isIgnored_identityNeverTakenFromBand() {
        val (wearable, platform, _) = composeApplication()
        wearable.start()
        platform.setConnected(true)
        awaitUntil { platform.outbound.isNotEmpty() }
        val before = platform.outbound.size

        // 手环发来 Presence（乱序/伪造）→ BODY 不是 BRAIN：一律忽略
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(bandPresence("fake-1"))
        }
        Thread.sleep(200)
        assertEquals(before, platform.outbound.size)

        // 超范围 ACK（revision 超出已发送）→ 忽略，不污染 last sync
        kotlinx.coroutines.runBlocking {
            platform.injectFromBand(
                WearMessageCodec.encode(
                    WearMessage.Ack(
                        WearAckEnvelope(
                            messageId = "ack-1",
                            generatedAt = 1L,
                            ackFor = "p-x",
                            revision = Long.MAX_VALUE,
                            status = "OK",
                        )
                    )
                )
            )
        }
        Thread.sleep(200)
        assertEquals(null, wearable.runtime.state.value.lastAckAt)
    }

    @Test
    fun hapticsPreference_flowsIntoEnvelopeSurface() {
        val (wearable, platform, _) = composeApplication()
        wearable.start()
        platform.setConnected(true)
        awaitUntil { platform.outbound.isNotEmpty() }
        assertFalse("默认 SILENT", lastOutboundPresence(platform).surface.hapticsEnabled)

        val before = platform.outbound.size
        wearable.prefs.hapticsEnabled = true
        kotlinx.coroutines.runBlocking { wearable.notifySurfacePrefsChanged() }
        awaitUntil { platform.outbound.size > before }
        assertTrue("开启后 envelope.surface.hapticsEnabled=true（手环据此放行振动）",
            lastOutboundPresence(platform).surface.hapticsEnabled)

        val beforeOff = platform.outbound.size
        wearable.prefs.hapticsEnabled = false
        kotlinx.coroutines.runBlocking { wearable.notifySurfacePrefsChanged() }
        awaitUntil { platform.outbound.size > beforeOff }
        assertFalse("关闭后必须回到 false（手环端 vibrate 全部 no-op）",
            lastOutboundPresence(platform).surface.hapticsEnabled)
    }
}
