package com.yunjue.echo.mind.data.outbox

import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.OutboxEventEntity
import com.yunjue.echo.mind.security.FieldCipher
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * 跨域共享的 outbox 写入原语（bounded-context 拆分地基）。
 *
 * 所有需要「加密 payload → insertOutbox」的域仓库（consent / sensing / skill / escalation /
 * portrait）都必须经本类写入，避免在多处复制 `cipher.encrypt + insertOutbox`。
 *
 * - [basePayload]：构造统一的 event_id / user_id / client_time 基础载荷（userId 由调用方显式传入）。
 * - [enqueue]：加密 payload 后写入 outbox（eventType + priority）。
 * - 本地优先架构：本地模式（未绑定机构）数据只保存在本机——[localModeProvider] 为 true 时
 *   enqueue 直接跳过（不写入、不加密），与 SyncWorker 的静默短路构成双保险，
 *   避免 outbox 无界累积。
 */
class Outbox(
    private val db: EchoDatabase,
    private val cipher: FieldCipher,
    private val localModeProvider: () -> Boolean = { false },
) {
    fun basePayload(eventId: String, clientTime: Instant, userId: String): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("user_id", userId)
        put("client_time", clientTime.toString())
    }

    suspend fun enqueue(eventId: String, eventType: String, payload: JSONObject, priority: Int) {
        // 演示模式：数据仅保存在本机，不产生任何上行（不写 outbox）
        if (localModeProvider()) return
        db.dao().insertOutbox(
            OutboxEventEntity(
                eventId = eventId,
                eventType = eventType,
                payloadCiphertext = cipher.encrypt(payload.toString()),
                priority = priority,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * ERA 32 R26：本地→云端切换补发——本地模式期间已落库但未入队的事件
     * （escalation outboxSynced=false / derived_feature synced=false）重新入队，
     * 订阅后不再「永远等待送达」。调用时机：绑定成功（accessToken 已写入）之后。
     */
    suspend fun reEnqueueLocalBacklog() {
        for (esc in db.escalationDao().unsyncedEscalations()) {
            val summary = runCatching { cipher.decrypt(esc.evidenceSummaryCiphertext) }
                .getOrNull() ?: "用户主动请求机构人工支持"
            val payload = JSONObject().apply {
                put("event_id", esc.eventId)
                put("user_id", esc.userId)
                put("trigger", esc.trigger)
                put("evidence_summary", summary)
            }
            enqueue(esc.eventId, "escalation", payload, 2000)
        }
        for (feat in db.dao().pendingFeatureVectors()) {
            val summary = runCatching { cipher.decrypt(feat.summaryCiphertext) }.getOrNull() ?: continue
            val payload = basePayload(feat.id, Instant.ofEpochMilli(feat.windowStart), feat.userId).apply {
                put("schema_version", feat.schemaVersion)
                put("source", feat.source)
                put("window_start", Instant.ofEpochMilli(feat.windowStart).toString())
                put("window_end", Instant.ofEpochMilli(feat.windowEnd).toString())
                put("summary", summary)
                put("vector", JSONArray(feat.vector))
                if (!feat.sourcesPresentJson.isNullOrBlank()) {
                    put("sources_present", JSONArray(feat.sourcesPresentJson))
                }
            }
            enqueue(feat.id, "derived_feature", payload, 20)
        }
    }
}
