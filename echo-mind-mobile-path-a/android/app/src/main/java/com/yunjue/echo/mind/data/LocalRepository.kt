package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.model.CheckinInput
import com.yunjue.echo.mind.model.JournalInput
import com.yunjue.echo.mind.model.QuestionnaireScore
import com.yunjue.echo.mind.model.SafetyDecision
import com.yunjue.echo.mind.model.Severity
import com.yunjue.echo.mind.security.FieldCipher
import com.yunjue.echo.mind.security.QuestionnaireScorer
import com.yunjue.echo.mind.security.SafetyEngine
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class LocalRepository(
    private val db: EchoDatabase,
    private val cipher: FieldCipher,
    private val preferences: AppPreferences
) {
    fun observeCheckins(): Flow<List<CheckinEntity>> = db.dao().observeCheckins()
    fun observeJournals(): Flow<List<JournalEntity>> = db.dao().observeJournals()
    fun observeQuestionnaires(): Flow<List<QuestionnaireEntity>> = db.dao().observeQuestionnaires()
    fun observePractices(): Flow<List<PracticeCompletionEntity>> = db.dao().observePractices()
    fun observePendingCount(): Flow<Int> = db.dao().observePendingCount()

    private fun basePayload(eventId: String, clientTime: Instant): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("user_id", preferences.userId)
        put("client_time", clientTime.toString())
    }

    private suspend fun enqueue(eventId: String, type: String, payload: JSONObject, priority: Int) {
        db.dao().insertOutbox(
            OutboxEventEntity(eventId, type, cipher.encrypt(payload.toString()), priority, System.currentTimeMillis())
        )
    }

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
        enqueue(eventId, "consent", payload, priority)
    }

    suspend fun saveL0(
        currentDanger: Boolean,
        priorAttempt: Boolean,
        psychosisOrMania: Boolean,
        substanceImpairment: Boolean,
        hasProfessionalSupport: Boolean
    ) {
        val eventId = "evt_${UUID.randomUUID()}"
        val payload = basePayload(eventId, Instant.now()).apply {
            put("current_danger", currentDanger)
            put("prior_attempt_or_admission", priorAttempt)
            put("psychosis_or_mania", psychosisOrMania)
            put("substance_impairment", substanceImpairment)
            put("has_professional_support", hasProfessionalSupport)
        }
        enqueue(eventId, "l0", payload, if (currentDanger) 2000 else 500)
    }

    suspend fun saveEmergencyContact(name: String, phone: String, relationship: String) {
        val eventId = "ec_${UUID.randomUUID()}"
        val payload = JSONObject().apply {
            put("user_id", preferences.userId)
            put("name", name)
            put("phone", phone)
            put("relationship", relationship)
        }
        enqueue(eventId, "emergency_contact", payload, 500)
    }

    suspend fun saveCheckin(input: CheckinInput): SafetyDecision {
        val decision = SafetyEngine.evaluate(input.note.orEmpty())
        db.dao().insertCheckin(
            CheckinEntity(
                eventId = input.eventId,
                mood = input.mood,
                stress = input.stress,
                energy = input.energy,
                sleepRecovery = input.sleepRecovery,
                eventFlag = input.eventFlag,
                helpRequested = input.helpRequested,
                noteCiphertext = input.note?.let(cipher::encrypt),
                clientTimeEpochMs = input.clientTime.toEpochMilli()
            )
        )
        val payload = basePayload(input.eventId, input.clientTime).apply {
            put("mood", input.mood)
            put("stress", input.stress)
            put("energy", input.energy)
            put("sleep_recovery", input.sleepRecovery)
            put("event_flag", input.eventFlag)
            put("help_requested", input.helpRequested)
            put("note", input.note ?: JSONObject.NULL)
            put("device_timezone", ZoneId.systemDefault().id)
        }
        enqueue(input.eventId, "checkin", payload, if (decision.severity == Severity.RED || input.helpRequested) 100 else 10)
        if (decision.severity == Severity.RED || input.helpRequested) {
            enqueueEscalation(
                trigger = if (input.helpRequested) "help_requested" else "text_red_signal",
                evidence = "手机端确定性规则或用户主动求助触发"
            )
        }
        return decision
    }

    suspend fun saveJournal(input: JournalInput): SafetyDecision {
        val decision = SafetyEngine.evaluate(input.body)
        db.dao().insertJournal(
            JournalEntity(
                eventId = input.eventId,
                logicalId = input.logicalId,
                revision = input.revision,
                bodyCiphertext = cipher.encrypt(input.body),
                tagsJson = JSONArray(input.tags).toString(),
                clientTimeEpochMs = input.clientTime.toEpochMilli()
            )
        )
        val payload = basePayload(input.eventId, input.clientTime).apply {
            put("logical_id", input.logicalId)
            put("body", input.body)
            put("event_tags", JSONArray(input.tags))
        }
        enqueue(input.eventId, "journal", payload, if (decision.severity == Severity.RED) 100 else 10)
        if (decision.severity == Severity.RED) enqueueEscalation("journal_red_signal", "日记命中手机端确定性红色规则")
        return decision
    }

    suspend fun saveQuestionnaire(code: String, answers: List<Int>): QuestionnaireScore {
        val eventId = "evt_${UUID.randomUUID()}"
        val score = when (code) {
            "phq9" -> QuestionnaireScorer.phq9(answers)
            "gad7" -> QuestionnaireScorer.gad7(answers)
            else -> error("Unsupported questionnaire")
        }
        db.dao().insertQuestionnaire(
            QuestionnaireEntity(eventId, code, "1.0", JSONArray(answers).toString(), score.score, score.urgentItem, System.currentTimeMillis())
        )
        val payload = basePayload(eventId, Instant.now()).apply {
            put("version", "1.0")
            put("answers", JSONArray(answers))
        }
        enqueue(eventId, "questionnaire:$code", payload, if (score.urgentItem) 100 else 20)
        if (score.urgentItem) enqueueEscalation("phq9_item9_positive", "PHQ-9 高风险题项非零，需人工复核")
        return score
    }

    suspend fun recordPractice(practiceId: String, status: String, durationSeconds: Int) {
        val eventId = "evt_${UUID.randomUUID()}"
        val now = Instant.now()
        db.dao().insertPracticeCompletion(
            PracticeCompletionEntity(eventId, practiceId, "1.0", status, durationSeconds, now.toEpochMilli())
        )
        val payload = basePayload(eventId, now).apply {
            put("practice_id", practiceId)
            put("content_version", "1.0")
            put("status", status)
            put("duration_seconds", durationSeconds)
        }
        enqueue(eventId, "practice", payload, 5)
    }

    suspend fun requestDataAction(type: String) {
        val eventId = "evt_${UUID.randomUUID()}"
        val payload = JSONObject().apply {
            put("event_id", eventId)
            put("user_id", preferences.userId)
            put("request_type", type)
        }
        enqueue(eventId, "dsr", payload, 200)
    }

    private suspend fun enqueueEscalation(trigger: String, evidence: String) {
        val escalationId = "evt_${UUID.randomUUID()}"
        val payload = JSONObject().apply {
            put("event_id", escalationId)
            put("user_id", preferences.userId)
            put("level", "L3")
            put("trigger", trigger)
            put("evidence_summary", evidence)
        }
        enqueue(escalationId, "escalation", payload, 1000)
    }

    fun decryptJournal(value: JournalEntity): String = value.bodyCiphertext?.let(cipher::decrypt).orEmpty()
}
