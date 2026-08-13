package com.yunjue.echo.mind.data.outbox

import com.yunjue.echo.mind.data.EchoDatabase
import com.yunjue.echo.mind.data.OutboxEventEntity
import com.yunjue.echo.mind.security.FieldCipher
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
 */
class Outbox(
    private val db: EchoDatabase,
    private val cipher: FieldCipher,
) {
    fun basePayload(eventId: String, clientTime: Instant, userId: String): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("user_id", userId)
        put("client_time", clientTime.toString())
    }

    suspend fun enqueue(eventId: String, eventType: String, payload: JSONObject, priority: Int) {
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
}
