package com.yunjue.echo.mind.data

import androidx.room.withTransaction
import com.yunjue.echo.mind.security.FieldCipher
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * 本地数据权利（v0.7 本地优先架构）：
 * 本地模式（未订阅）下「你的数据你做主」承诺的端侧实现——导出与删除直接在本机完成，
 * 不走 Outbox（本地模式 outbox 静默，旧路径会假成功）。
 *
 * - [exportLocalData]：聚合本地派生特征窗口 / 画像缓存 / 同意记录 / 记忆 / Journey Canonical
 *   快照为 JSON（summary 解密；五域与 [deleteLocalData] 一一对齐）；
 * - [deleteLocalData]：事务内清除该用户的派生特征、画像缓存、同意记录、记忆与 Canonical 快照。
 *
 * 已订阅用户仍走服务端 DSR（/v1/data-subject-requests，含依法保留分类矩阵）。
 */
class LocalDataRights(
    private val db: EchoDatabase,
    private val cipher: FieldCipher
) {
    /** 导出本地数据为 JSON 字符串（纯本机，无上行）。 */
    suspend fun exportLocalData(userId: String): String {
        val windows = db.dao().allPassiveCoreRows(userId)
        val portraits = db.portraitDao().queryByDateRange(userId, "1900-01-01", "2999-12-31")
        val consents = db.consentDao().allByUser(userId)
        // ERA 47 覆盖复核：记忆 + Journey Canonical 快照纳入导出（与 deleteLocalData 五域对齐）
        val memories = db.memoryDao().allByUser(userId)
        val canonicalDays = db.journeyCanonicalDao().range(userId, "1900-01-01", "2999-12-31")

        val windowJson = JSONArray()
        windows.forEach { w ->
            windowJson.put(
                JSONObject().apply {
                    put("window_start", Instant.ofEpochMilli(w.windowStart).toString())
                    put("window_end", Instant.ofEpochMilli(w.windowEnd).toString())
                    put("source", w.source)
                    put("summary", runCatching { cipher.decrypt(w.summaryCiphertext) }.getOrElse { "[无法解密]" })
                    put("vector", runCatching { JSONArray(w.vector) }.getOrElse { JSONArray() })
                    put("sources_present", LocalPortraitDataSource.parseSourcesJson(w.sourcesPresentJson, w.source))
                }
            )
        }
        val portraitJson = JSONArray()
        portraits.forEach { p ->
            portraitJson.put(
                JSONObject().apply {
                    put("date", p.localDate)
                    put("status", p.status)
                    put("summary", p.summary)
                }
            )
        }
        val consentJson = JSONArray()
        consents.forEach { c ->
            consentJson.put(
                JSONObject().apply {
                    put("type", c.consentType)
                    put("version", c.version)
                    put("granted", c.granted)
                    put("granted_at", Instant.ofEpochMilli(c.grantedAt).toString())
                    put("evidence_hash", c.evidenceHash)
                }
            )
        }
        val memoryJson = JSONArray()
        memories.forEach { m ->
            memoryJson.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("type", m.type)
                    put("content", m.content)
                    put("source", m.source)
                    put("confidence", m.confidence)
                    put("created_at", Instant.ofEpochMilli(m.createdAt).toString())
                    put("last_confirmed_at", Instant.ofEpochMilli(m.lastConfirmedAt).toString())
                    put("importance", m.importance)
                    put("retention_class", m.retentionClass)
                    put("deleted", m.deleted)
                }
            )
        }
        val canonicalJson = JSONArray()
        canonicalDays.forEach { d ->
            canonicalJson.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("local_date", d.localDate)
                    put("payload", d.payload)
                    put("created_at", Instant.ofEpochMilli(d.createdAtEpochMs).toString())
                }
            )
        }

        return JSONObject().apply {
            put("exported_at", Instant.now().toString())
            put("mode", "local")
            put("user_id", userId)
            put("derived_feature_windows", windowJson)
            put("portraits", portraitJson)
            put("consents", consentJson)
            put("memories", memoryJson)
            put("journey_canonical_days", canonicalJson)
        }.toString(2)
    }

    /** 删除本地数据（事务内；本地模式无云端副本，删除即最终删除）。 */
    suspend fun deleteLocalData(userId: String) {
        db.withTransaction {
            db.dao().deleteFeatureVectorsByUser(userId)
            db.portraitDao().deleteByUser(userId)
            db.consentDao().deleteConsentsByUser(userId)
            db.memoryDao().deleteByUser(userId)
            db.journeyCanonicalDao().deleteByUser(userId)
        }
    }
}
