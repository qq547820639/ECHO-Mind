package com.yunjue.echo.mind.data

import androidx.room.withTransaction
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.outbox.Outbox
import com.yunjue.echo.mind.model.DerivedFeatureInput
import com.yunjue.echo.mind.model.SafetyDecision
import com.yunjue.echo.mind.model.Severity
import com.yunjue.echo.mind.security.FieldCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

/**
 * 派生特征（Sensing）仓库：批量落库 + ACK 状态机 + 失败观测。
 *
 * - [saveDerivedFeatures]：Room withTransaction 内批量落库（feature_vectors）+ 入 outbox
 *   （derived_feature），任一失败返回 false；成功/失败分别回写 preferences 观测字段。
 * - [saveDerivedFeature]：单条兼容旧调用方，恒 NONE decision（PRD v0.6 契约点 1 收口，
 *   行为派生特征不得触发危机链路）。
 */
class SensingRepository(
    private val db: EchoDatabase,
    private val cipher: FieldCipher,
    private val outbox: Outbox,
    private val preferences: AppPreferences,
) {
    // 被动特征安全状态（PRD v0.6 契约点 1 收口后恒为 NONE，不再触发危机 UI）。
    private val _passiveSafety = MutableStateFlow<SafetyDecision?>(null)
    val passiveSafety: StateFlow<SafetyDecision?> = _passiveSafety.asStateFlow()

    suspend fun saveDerivedFeatures(inputs: List<DerivedFeatureInput>): Boolean {
        if (inputs.isEmpty()) return true
        return try {
            db.withTransaction {
                for (input in inputs) {
                    persistDerivedFeature(input)
                }
            }
            preferences.lastCollectionTimestamp = System.currentTimeMillis()
            preferences.consecutivePersistenceFailures = 0
            true
        } catch (e: Exception) {
            preferences.lastPersistenceFailure = System.currentTimeMillis()
            preferences.consecutivePersistenceFailures = preferences.consecutivePersistenceFailures + 1
            false
        }
    }

    /** 单条派生特征落库（兼容旧调用方，委托批量语义）。 */
    suspend fun saveDerivedFeature(input: DerivedFeatureInput): SafetyDecision {
        saveDerivedFeatures(listOf(input))
        val decision = SafetyDecision(Severity.NONE, emptyList(), false)
        _passiveSafety.value = decision
        return decision
    }

    /** 单条派生特征持久化（feature_vectors + outbox），须在事务内调用。 */
    private suspend fun persistDerivedFeature(input: DerivedFeatureInput) {
        val eventId = "feat_${UUID.randomUUID()}"
        val now = Instant.now()
        db.dao().insertFeatureVector(
            FeatureVectorEntity(
                id = eventId,
                userId = preferences.userId,
                schemaVersion = input.schemaVersion,
                source = input.source,
                windowStart = input.windowStart.toEpochMilli(),
                windowEnd = input.windowEnd.toEpochMilli(),
                summaryCiphertext = cipher.encrypt(input.summary),
                vector = JSONArray(input.vector).toString(),
                synced = false,
                createdAt = now.toEpochMilli(),
                // v8 离线画像引擎：窗口实际信号源随行持久化（本地聚合 missing_sources 还原用）
                sourcesPresentJson = if (input.sourcesPresent.isEmpty()) null
                else JSONArray(input.sourcesPresent).toString()
            )
        )
        val payload = outbox.basePayload(eventId, now, preferences.userId).apply {
            put("schema_version", input.schemaVersion)
            put("source", input.source)
            put("window_start", input.windowStart.toString())
            put("window_end", input.windowEnd.toString())
            put("summary", input.summary)
            put("vector", JSONArray(input.vector))
            if (input.sourcesPresent.isNotEmpty()) {
                put("sources_present", JSONArray(input.sourcesPresent))
            }
        }
        outbox.enqueue(eventId, "derived_feature", payload, 20)
    }
}
