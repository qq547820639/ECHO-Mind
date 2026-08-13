package com.yunjue.echo.mind.data

import androidx.room.withTransaction
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.data.outbox.Outbox
import com.yunjue.echo.mind.security.FieldCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * 人工支持请求（escalation）客户端闭环仓库（v0.6.1，P0-2）。
 *
 * - 用户侧最小状态（QUEUED/DELIVERED/ACKNOWLEDGED/TAKEN_OVER/CLOSED/FAILED）绝不暴露内部策略；
 * - [requestHumanSupport] 生成幂等 event_id，事务内本地持久化 + 入 outbox；
 * - 服务端确认前 UI 只显示「等待送达」，绝不显示「人工已收到」（human_acknowledged 由服务端显式 ack）。
 */
class EscalationRepository(
    private val db: EchoDatabase,
    private val cipher: FieldCipher,
    private val outbox: Outbox,
    private val preferences: AppPreferences,
    private val apiClient: ApiClient,
) {
    fun observeEscalations(): Flow<List<EscalationEntity>> = db.escalationDao().observeAll()

    suspend fun latestEscalation(): EscalationEntity? = db.escalationDao().latest()

    suspend fun requestHumanSupport(
        trigger: String = "help_requested",
        evidenceSummary: String = "用户主动请求机构人工支持"
    ): String {
        val eventId = "esc_evt_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.escalationDao().upsert(
                EscalationEntity(
                    eventId = eventId,
                    userId = preferences.userId,
                    trigger = trigger,
                    evidenceSummaryCiphertext = cipher.encrypt(evidenceSummary),
                    status = EscalationStatus.QUEUED.name,
                    serverEscalationId = null,
                    serverStatusJson = null,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
            val payload = JSONObject().apply {
                put("event_id", eventId)
                put("user_id", preferences.userId)
                put("trigger", trigger)
                put("evidence_summary", evidenceSummary)
            }
            outbox.enqueue(eventId, "escalation", payload, 2000)
        }
        return eventId
    }

    /** 服务端处理结果回写（成功/幂等 replay → delivered）。 */
    suspend fun markEscalationDelivered(eventId: String, serverEscalationId: String?) {
        val row = db.escalationDao().byEventId(eventId) ?: return
        db.escalationDao().upsert(
            row.copy(
                status = EscalationStatus.DELIVERED.name,
                serverEscalationId = serverEscalationId ?: row.serverEscalationId,
                updatedAtEpochMs = System.currentTimeMillis(),
                outboxSynced = true
            )
        )
    }

    /** 服务端 user-status 查询结果回写（human_acknowledged 仅由服务端显式 ack/takeover 决定）。 */
    suspend fun updateEscalationServerStatus(eventId: String, serverStatusJson: String) {
        val row = db.escalationDao().byEventId(eventId) ?: return
        val status = parseServerStatus(serverStatusJson)
        db.escalationDao().upsert(
            row.copy(
                status = status,
                serverStatusJson = serverStatusJson,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    /** 解析服务端 user-status JSON → 本地最小状态（fail-closed：解析失败保持现状）。 */
    private fun parseServerStatus(json: String): String = runCatching {
        val o = JSONObject(json)
        when {
            o.optBoolean("human_acknowledged") -> EscalationStatus.TAKEN_OVER.name
            o.optBoolean("delivery_confirmed") -> EscalationStatus.DELIVERED.name
            else -> EscalationStatus.QUEUED.name
        }
    }.getOrDefault(EscalationStatus.QUEUED.name)

    /** escalation dead-letter（本地放弃）：用户可见 FAILED（需重新联系机构）。 */
    suspend fun markEscalationFailed(eventId: String) {
        val row = db.escalationDao().byEventId(eventId) ?: return
        db.escalationDao().upsert(
            row.copy(status = EscalationStatus.FAILED.name, updatedAtEpochMs = System.currentTimeMillis())
        )
    }

    /** 拉取某 escalation 的 user-status 并回写。 */
    suspend fun refreshEscalationStatus(escalationId: String) {
        if (escalationId.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = apiClient.get("/v1/escalations/$escalationId/user-status")
                if (code in 200..299 && !body.isNullOrBlank()) {
                    updateEscalationServerStatus(escalationId, body)
                }
            }
        }
    }
}
