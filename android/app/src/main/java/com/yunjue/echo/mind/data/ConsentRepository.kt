package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.outbox.Outbox
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Consent / L0 / 紧急联系人 / DSR 写入域仓库。
 *
 * 所有同意证据、L0 准入门禁、紧急联系人、数据主体请求（DSR）均经 [Outbox] 可靠入队，
 * 由 SyncWorker 上传；证据哈希可重算校验（SHA-256 固定盐 + userId + granted）。
 */
class ConsentRepository(
    private val outbox: Outbox,
    private val preferences: AppPreferences,
) {
    suspend fun saveConsent(
        granted: Boolean,
        evidenceHash: String,
        consentType: String = "psychological_data",
        version: String = "path-a-consent-2026.07",
        priority: Int = 500
    ) {
        val eventId = "consent_${UUID.randomUUID()}"
        val payload = JSONObject().apply {
            put("user_id", preferences.userId)
            put("consent_type", consentType)
            put("version", version)
            put("granted", granted)
            put("evidence_hash", evidenceHash)
        }
        outbox.enqueue(eventId, "consent", payload, priority)
    }

    /** passive_sensing consent 证据（granted=true/false），版本化 evidence hash。 */
    suspend fun savePassiveSensingConsent(granted: Boolean, priority: Int = 600) {
        val userId = preferences.userId
        val evidence = MessageDigest.getInstance("SHA-256")
            .digest("passive-sensing-consent-2026.07:$userId:$granted".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        saveConsent(
            granted = granted,
            evidenceHash = evidence,
            consentType = "passive_sensing",
            version = "passive-sensing-consent-2026.07",
            priority = priority
        )
    }

    /**
     * 麦克风派生特征专用 consent（voice_features，P1.3）。
     * evidence_hash = SHA-256("voice-features-consent-2026.07:$userId:$granted")，
     * 复用 [saveConsent]，priority=600（高于普通 consent 500）。
     */
    suspend fun saveVoiceFeaturesConsent(granted: Boolean) {
        val userId = preferences.userId
        val evidenceHash = MessageDigest.getInstance("SHA-256")
            .digest("voice-features-consent-2026.07:$userId:$granted".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        saveConsent(
            granted = granted,
            evidenceHash = evidenceHash,
            consentType = "voice_features",
            version = "voice-features-consent-2026.07",
            priority = 600
        )
    }

    suspend fun saveL0(
        currentDanger: Boolean,
        priorAttempt: Boolean,
        psychosisOrMania: Boolean,
        substanceImpairment: Boolean,
        hasProfessionalSupport: Boolean
    ) {
        val eventId = "evt_${UUID.randomUUID()}"
        val payload = outbox.basePayload(eventId, Instant.now(), preferences.userId).apply {
            put("current_danger", currentDanger)
            put("prior_attempt_or_admission", priorAttempt)
            put("psychosis_or_mania", psychosisOrMania)
            put("substance_impairment", substanceImpairment)
            put("has_professional_support", hasProfessionalSupport)
        }
        outbox.enqueue(eventId, "l0", payload, if (currentDanger) 2000 else 500)
    }

    suspend fun saveEmergencyContact(name: String, phone: String, relationship: String) {
        val eventId = "ec_${UUID.randomUUID()}"
        val payload = JSONObject().apply {
            put("user_id", preferences.userId)
            put("name", name)
            put("phone", phone)
            put("relationship", relationship)
        }
        outbox.enqueue(eventId, "emergency_contact", payload, 500)
    }

    suspend fun requestDataAction(type: String) {
        val eventId = "evt_${UUID.randomUUID()}"
        val payload = JSONObject().apply {
            put("event_id", eventId)
            put("user_id", preferences.userId)
            put("request_type", type)
        }
        outbox.enqueue(eventId, "dsr", payload, 200)
    }
}
