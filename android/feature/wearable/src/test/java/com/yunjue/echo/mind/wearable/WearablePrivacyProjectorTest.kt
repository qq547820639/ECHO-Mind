package com.yunjue.echo.mind.wearable

import com.yunjue.echo.mind.model.EchoMaturity
import com.yunjue.echo.mind.model.EchoPresenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST — PRIVACY（ECHO_WRIST_PRIVACY.md §Never Send）：
 * 自动检查所有 Band payload。禁止发现：API key / raw Memory / Correction raw text /
 * private Context / provider config / raw notification content / raw audio /
 * precise location / secret identity seed。Privacy failure = hard FAIL。
 */
class WearablePrivacyProjectorTest {

    private fun state(maturity: EchoMaturity = EchoMaturity.KNOWN): EchoPresenceState = EchoPresenceState(
        maturity = maturity,
    )

    // ------------------------------------------------------------------ headline

    @Test
    fun headline_defaultVisualFirst_isNull() {
        // T5-P2-7：WHY 门控在 headlineFor 函数契约内——whyRequested=false（默认 VISUAL_FIRST）
        // 对任意 maturity/ambient 一律 null，隐私纪律不再依赖调用方绕过本函数。
        for (maturity in EchoMaturity.entries) {
            for (ambient in WearablePrivacyProjector.WearAmbientState.entries) {
                assertEquals(
                    "VISUAL_FIRST 默认无 headline（$maturity/$ambient）",
                    null,
                    WearablePrivacyProjector.headlineFor(state(maturity), ambient, whyRequested = false),
                )
            }
        }
    }

    @Test
    fun headline_earlyMaturity_learningPhaseNeutral() {
        assertEquals(
            WearablePrivacyProjector.HeadlineAllowlist.SEED,
            WearablePrivacyProjector.headlineFor(state(EchoMaturity.SEED), null, whyRequested = true),
        )
        assertEquals(
            WearablePrivacyProjector.HeadlineAllowlist.DISCOVERING,
            WearablePrivacyProjector.headlineFor(state(EchoMaturity.DISCOVERING), null, whyRequested = true),
        )
        assertEquals(
            WearablePrivacyProjector.HeadlineAllowlist.DISCOVERING,
            WearablePrivacyProjector.headlineFor(state(EchoMaturity.EMERGING), null, whyRequested = true),
        )
    }

    @Test
    fun headline_knownMaturity_ambientTemplatesFromAllowlist() {
        val late = WearablePrivacyProjector.headlineFor(
            state(EchoMaturity.KNOWN),
            WearablePrivacyProjector.WearAmbientState.LATE,
            whyRequested = true,
        )
        assertEquals(WearablePrivacyProjector.HeadlineAllowlist.LATE_START, late)

        val quiet = WearablePrivacyProjector.headlineFor(
            state(EchoMaturity.MATURE),
            WearablePrivacyProjector.WearAmbientState.QUIET,
            whyRequested = true,
        )
        assertEquals(WearablePrivacyProjector.HeadlineAllowlist.QUIET_DAY, quiet)

        val active = WearablePrivacyProjector.headlineFor(
            state(EchoMaturity.MATURE),
            WearablePrivacyProjector.WearAmbientState.ACTIVE,
            whyRequested = true,
        )
        assertEquals(WearablePrivacyProjector.HeadlineAllowlist.ACTIVE_DAY, active)
    }

    @Test
    fun headline_neverUsesPrivateContent() {
        // 即使状态里有 privateNarrative / affectiveState，headline 也绝不用它们。
        val withPrivate = state(EchoMaturity.KNOWN).copy(
            privateNarrative = "你最近压力很大，昨晚睡得很糟。",
        )
        val headline = WearablePrivacyProjector.headlineFor(
            withPrivate,
            WearablePrivacyProjector.WearAmbientState.QUIET,
            whyRequested = true,
        )
        assertTrue(WearablePrivacyProjector.HeadlineAllowlist.ALL.contains(headline!!))
    }

    @Test
    fun headline_alwaysInsideAllowlistOrNull() {
        // 穷举所有 maturity × ambient（WHY 请求下）：headline ∈ 允许清单 或 null
        // （氛围未知 → 宁可没有，不编造；禁止自由文本进入腕上）。
        for (maturity in EchoMaturity.entries) {
            for (ambient in WearablePrivacyProjector.WearAmbientState.entries) {
                val headline = WearablePrivacyProjector.headlineFor(state(maturity), ambient, whyRequested = true)
                assertTrue(
                    "headline $headline for $maturity/$ambient must be null or in allowlist",
                    headline == null || WearablePrivacyProjector.HeadlineAllowlist.ALL.contains(headline),
                )
            }
        }
    }

    @Test
    fun headline_knownMaturityUnknownAmbient_isNullNotFabricated() {
        // KDoc 契约落地：null/UNKNOWN/SLOW/DENSE 氛围 → null（宁可没有，不编造 KNOWN 文案）
        for (ambient in listOf(null, WearablePrivacyProjector.WearAmbientState.UNKNOWN,
                WearablePrivacyProjector.WearAmbientState.SLOW, WearablePrivacyProjector.WearAmbientState.DENSE)) {
            assertEquals(
                "氛围 $ambient → null（不编造）",
                null,
                WearablePrivacyProjector.headlineFor(state(EchoMaturity.KNOWN), ambient, whyRequested = true),
            )
        }
    }

    // ------------------------------------------------------------------ scan

    @Test
    fun scan_cleanEnvelopePayload_isPublicSafe() {
        val envelope = WearPresenceEnvelope(
            messageId = "p-1",
            generatedAt = 1L,
            revision = 1L,
            expiresAt = 2L,
            maturity = "KNOWN",
            identity = WearIdentityProjection(0.5f, 0.5f, 0.5f, 0.5f, 1, 2, 0.5f),
            moment = WearMomentProjection(0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
            surface = WearSurfaceParams("DEFAULT", false, false),
        )
        val report = WearablePrivacyProjector.scanPayload(WearMessageCodec.encode(WearMessage.Presence(envelope)))
        assertTrue(report.isPublicSafe)
    }

    @Test
    fun scan_apiKey_hardFail() {
        val payload = """{"type":"presence","apiKey":"sk-abc12345678"}"""
        val report = WearablePrivacyProjector.scanPayload(payload)
        assertFalse(report.isPublicSafe)
        assertTrue(report.violations.any { it.kind == WearablePrivacyProjector.ViolationKind.API_KEY })
    }

    @Test
    fun scan_providerCredentialAndBaseUrl_hardFail() {
        val payload = """{"provider_base_url":"https://api.openai.com","authorization":"Bearer xyz123456789"}"""
        val report = WearablePrivacyProjector.scanPayload(payload)
        assertFalse(report.isPublicSafe)
        assertTrue(report.violations.any { it.kind == WearablePrivacyProjector.ViolationKind.PROVIDER_BASE_URL })
        assertTrue(report.violations.any { it.kind == WearablePrivacyProjector.ViolationKind.PROVIDER_CREDENTIAL })
    }

    @Test
    fun scan_rawMemoryAndCorrection_hardFail() {
        val payload = """{"raw_memory":"用户上周说过……","correction_text":"我不喜欢被叫作夜猫子"}"""
        val report = WearablePrivacyProjector.scanPayload(payload)
        assertFalse(report.isPublicSafe)
        assertTrue(report.violations.any { it.kind == WearablePrivacyProjector.ViolationKind.RAW_MEMORY })
        assertTrue(report.violations.any { it.kind == WearablePrivacyProjector.ViolationKind.CORRECTION_TEXT })
    }

    @Test
    fun scan_privateContextNarrativeNotificationAudio_hardFail() {
        val payload = """
            {"private_context":"…","private_narrative":"…","notification_body":"…","raw_audio":"…"}
        """.trimIndent()
        val report = WearablePrivacyProjector.scanPayload(payload)
        assertFalse(report.isPublicSafe)
        val kinds = report.violations.map { it.kind }.toSet()
        assertTrue(kinds.contains(WearablePrivacyProjector.ViolationKind.PRIVATE_CONTEXT))
        assertTrue(kinds.contains(WearablePrivacyProjector.ViolationKind.PRIVATE_NARRATIVE))
        assertTrue(kinds.contains(WearablePrivacyProjector.ViolationKind.NOTIFICATION_BODY))
        assertTrue(kinds.contains(WearablePrivacyProjector.ViolationKind.RAW_AUDIO))
    }

    @Test
    fun scan_preciseLocation_hardFail() {
        val payload = """{"latitude":39.9042,"longitude":116.4074}"""
        val report = WearablePrivacyProjector.scanPayload(payload)
        assertFalse(report.isPublicSafe)
        assertTrue(report.violations.any { it.kind == WearablePrivacyProjector.ViolationKind.PRECISE_LOCATION })
    }

    @Test
    fun scan_identitySeed_hardFail() {
        val payload = """{"identity_seed":"0xDEADBEEF"}"""
        val report = WearablePrivacyProjector.scanPayload(payload)
        assertFalse(report.isPublicSafe)
        assertTrue(report.violations.any { it.kind == WearablePrivacyProjector.ViolationKind.IDENTITY_SEED })
    }

    @Test
    fun scan_nestedForbiddenKey_hardFail() {
        // 嵌套对象里的敏感键同样触发（扫描递归）。
        val payload = """{"type":"presence","extra":{"deep":{"openai_key":"sk-123456789"}}}"""
        val report = WearablePrivacyProjector.scanPayload(payload)
        assertFalse(report.isPublicSafe)
        assertTrue(report.violations.any { it.kind == WearablePrivacyProjector.ViolationKind.API_KEY })
    }

    @Test
    fun scan_valuePatterns_hardFail() {
        val payload = """{"note":"Bearer abcdef123456"}"""
        val report = WearablePrivacyProjector.scanPayload(payload)
        assertFalse(report.isPublicSafe)
        assertTrue(report.violations.any { it.kind == WearablePrivacyProjector.ViolationKind.PROVIDER_CREDENTIAL })
    }

    @Test
    fun scan_unparseable_isViolation() {
        val report = WearablePrivacyProjector.scanPayload("{broken")
        assertFalse(report.isPublicSafe)
    }

    // ------------------------------------------------------------------ actions

    @Test
    fun availableActions_onlyBreathingPause() {
        assertEquals(
            listOf("START_BREATHING", "START_PAUSE"),
            WearablePrivacyProjector.availableActions(true, true),
        )
        assertEquals(
            listOf("START_BREATHING"),
            WearablePrivacyProjector.availableActions(true, false),
        )
        assertTrue(WearablePrivacyProjector.availableActions(false, false).isEmpty())
    }
}
