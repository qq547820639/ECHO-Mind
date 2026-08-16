package com.yunjue.echo.mind.wearable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST — Revision / TTL / Rate Limit / Haptic Constitution。
 */
class WearablePolicyTest {

    // ------------------------------------------------------------------ push triggers

    @Test
    fun semanticTriggers_alwaysPush() {
        val now = 10_000L
        val last = now - 1 // 刚推过
        for (trigger in listOf(
            WearablePolicy.PushTrigger.INITIAL_CONNECT,
            WearablePolicy.PushTrigger.RECONNECT,
            WearablePolicy.PushTrigger.PRESENCE_REVISION_CHANGED,
            WearablePolicy.PushTrigger.ACTION_CHANGED,
            WearablePolicy.PushTrigger.PRIVACY_CHANGED,
        )) {
            assertTrue(trigger.name, WearablePolicy.shouldPushPresence(trigger, last, now))
        }
    }

    @Test
    fun userRequestTriggers_rateLimited() {
        val now = 10_000L
        // 从未推过 → 允许
        assertTrue(WearablePolicy.shouldPushPresence(WearablePolicy.PushTrigger.BAND_REQUEST, null, now))
        assertTrue(WearablePolicy.shouldPushPresence(WearablePolicy.PushTrigger.EXPLICIT_REFRESH, null, now))
        // 刚推过 → 防抖拒绝
        assertFalse(WearablePolicy.shouldPushPresence(WearablePolicy.PushTrigger.BAND_REQUEST, now - 1, now))
        // 超过最小间隔 → 允许
        assertTrue(
            WearablePolicy.shouldPushPresence(
                WearablePolicy.PushTrigger.BAND_REQUEST,
                now - WearablePolicy.PUSH_MIN_INTERVAL_MS - 1,
                now,
            ),
        )
    }

    // ------------------------------------------------------------------ revision

    @Test
    fun revision_monotonic_acceptOnlyGreater() {
        assertTrue(WearablePolicy.shouldAcceptRevision(2, 1))
        assertFalse(WearablePolicy.shouldAcceptRevision(1, 1)) // 相等 → ignore
        assertFalse(WearablePolicy.shouldAcceptRevision(0, 1)) // 更旧 → ignore（乱序安全）
    }

    // ------------------------------------------------------------------ TTL / clock skew

    @Test
    fun expiry_withClockSkewTolerance() {
        val expiresAt = 100_000L
        assertFalse(WearablePolicy.isExpired(expiresAt, expiresAt))
        // 刚好在容差边界内 → 不判过期
        assertFalse(WearablePolicy.isExpired(expiresAt, expiresAt + WearablePolicy.CLOCK_SKEW_TOLERANCE_MS))
        // 超出容差 → 过期
        assertTrue(WearablePolicy.isExpired(expiresAt, expiresAt + WearablePolicy.CLOCK_SKEW_TOLERANCE_MS + 1))
    }

    // ------------------------------------------------------------------ semantic change

    @Test
    fun semanticChange_onlyProjectedFieldsMatter() {
        val a = WearProjection(
            maturity = "KNOWN",
            identity = WearIdentityProjection(0.5f, 0.5f, 0.5f, 0.5f, 1, 2, 0.5f),
            moment = WearMomentProjection(0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
            surface = WearSurfaceParams("DEFAULT", false, false),
        )
        assertFalse(WearablePolicy.hasSemanticChange(a, a))
        assertTrue(WearablePolicy.hasSemanticChange(null, a))
        assertTrue(WearablePolicy.hasSemanticChange(a, null))

        // moment 微小变化 < epsilon → 不算语义变化（防浮点抖动）
        val b = a.copy(moment = a.moment.copy(flow = a.moment.flow + WearablePolicy.SEMANTIC_EPSILON / 2))
        assertFalse(WearablePolicy.hasSemanticChange(a, b))

        // identity 显著变化 → 语义变化
        val c = a.copy(identity = a.identity.copy(topology = 0.9f))
        assertTrue(WearablePolicy.hasSemanticChange(a, c))

        // maturity 变化 → 语义变化
        assertTrue(WearablePolicy.hasSemanticChange(a, a.copy(maturity = "MATURE")))

        // surface 变化（低电量开关）→ 语义变化
        assertTrue(WearablePolicy.hasSemanticChange(a, a.copy(surface = a.surface.copy(lowPower = true))))
    }

    // ------------------------------------------------------------------ haptic constitution

    @Test
    fun hapticConstitution_defaultSilent_explicitOnly() {
        // v1 允许的三类显式触觉
        assertTrue(WearablePolicy.hapticAllowed(WearablePolicy.HapticEvent.TAP_CONFIRMATION))
        assertTrue(WearablePolicy.hapticAllowed(WearablePolicy.HapticEvent.BREATHING_CADENCE))
        assertTrue(WearablePolicy.hapticAllowed(WearablePolicy.HapticEvent.ACTION_COMPLETION))
    }

    @Test
    fun hapticConstitution_noPassiveTriggersRepresentable() {
        // 结构上不存在 inferred stress / anomaly / AI suggestion / engagement 触觉事件：
        // HapticEvent 枚举只有 3 个显式成员 —— 被动触觉无法表达（硬禁）。
        val allowedEvents = WearablePolicy.HapticEvent.entries
        assertEquals(3, allowedEvents.size)
        for (event in allowedEvents) {
            assertTrue(event.name, WearablePolicy.hapticAllowed(event))
        }
    }

    @Test
    fun hapticMode_onlyShortLong_neverPattern() {
        // Band10 官方只支持 short/long；pattern 不存在于模型中。
        assertEquals(WearablePolicy.HapticMode.SHORT, WearablePolicy.hapticModeFor(WearablePolicy.HapticEvent.TAP_CONFIRMATION))
        assertEquals(WearablePolicy.HapticMode.SHORT, WearablePolicy.hapticModeFor(WearablePolicy.HapticEvent.BREATHING_CADENCE))
        assertEquals(WearablePolicy.HapticMode.LONG, WearablePolicy.hapticModeFor(WearablePolicy.HapticEvent.ACTION_COMPLETION))
    }

    @Test
    fun hapticRateLimiter_preventsSpam() {
        var now = 0L
        val limiter = HapticRateLimiter(minIntervalMs = 1500L) { now }
        assertTrue(limiter.tryAllow())
        now = 500L
        assertFalse(limiter.tryAllow()) // 太近
        now = 1600L
        assertTrue(limiter.tryAllow()) // 过了间隔
    }

    // ------------------------------------------------------------------ memory writing ban

    @Test
    fun wristObservation_neverWritesMemory() {
        val envelope = WearObservationEnvelope(messageId = "o-1", generatedAt = 1L)
        assertFalse(WearablePolicy.wristObservationWritesMemory(envelope))
    }

    // ------------------------------------------------------------------ fingerprint

    @Test
    fun fingerprint_isDeterministicAndSemantic() {
        val s1 = com.yunjue.echo.mind.model.EchoPresenceState(
            maturity = com.yunjue.echo.mind.model.EchoMaturity.KNOWN,
        )
        val s2 = s1.copy()
        assertEquals(PresenceRevisionCounter.fingerprint(s1), PresenceRevisionCounter.fingerprint(s2))
        val s3 = s1.copy(maturity = com.yunjue.echo.mind.model.EchoMaturity.MATURE)
        assertFalse(PresenceRevisionCounter.fingerprint(s1) == PresenceRevisionCounter.fingerprint(s3))
    }

    @Test
    fun revisionCounter_monotonicGrowth() {
        val counter = PresenceRevisionCounter()
        assertEquals(0L, counter.current)
        counter.next()
        counter.next()
        assertEquals(2L, counter.current)
        counter.advanceIfChanged(false)
        assertEquals(2L, counter.current) // 未变化不推进
        counter.advanceIfChanged(true)
        assertEquals(3L, counter.current)
    }
}
