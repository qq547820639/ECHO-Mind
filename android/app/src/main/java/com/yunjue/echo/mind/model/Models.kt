package com.yunjue.echo.mind.model

import java.time.Instant
import java.util.UUID

data class CheckinInput(
    val mood: Int,
    val stress: Int,
    val energy: Int,
    val sleepRecovery: Int,
    val eventFlag: Boolean,
    val helpRequested: Boolean,
    val note: String?,
    val clientTime: Instant = Instant.now(),
    val eventId: String = "evt_${UUID.randomUUID()}"
)

data class JournalInput(
    val body: String,
    val tags: List<String> = emptyList(),
    val logicalId: String = "journal_${UUID.randomUUID()}",
    val revision: Int = 1,
    val clientTime: Instant = Instant.now(),
    val eventId: String = "evt_${UUID.randomUUID()}"
)

data class SafetyDecision(
    val severity: Severity,
    val matchedRuleIds: List<String>,
    val freezeGeneration: Boolean,
    val scriptKey: String? = null
)

enum class Severity { NONE, YELLOW, RED, EXIT }

data class QuestionnaireScore(val score: Int, val message: String, val urgentItem: Boolean = false)

data class QuestionnaireDefinition(
    val code: String,
    val title: String,
    val items: List<String>,
    val version: String = "1.0"
)

data class PracticeDefinition(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val steps: List<String>,
    val version: String = "1.0"
)
