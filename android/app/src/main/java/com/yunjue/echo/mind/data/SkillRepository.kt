package com.yunjue.echo.mind.data

import androidx.room.withTransaction
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.data.outbox.Outbox
import com.yunjue.echo.mind.model.SkillCompletionInput
import com.yunjue.echo.mind.model.SkillDisplay
import org.json.JSONArray
import org.json.JSONObject

/**
 * P3 三态拉取结果：区分「加载中」「加载失败」「真无 Skill（冷启动）」。
 *
 * - skills == null + loadFailed == false → 加载中（首次拉取尚未返回）
 * - loadFailed == true → 网络失败，UI 应展示「加载失败」+ 重试按钮
 * - skills 非空 → 正常展示；skills 为空列表 → 按 [coldStartHint] 展示分阶段文案
 * - observationDays 用于格式化 stage_1_3 的「已采集 N 天」占位
 */
data class SkillFetchResult(
    val skills: List<SkillDisplay>?,
    val coldStartHint: String?,
    val loadFailed: Boolean,
    val observationDays: Int = 0
)

/**
 * Skill 会话持久化 + completion + 列表拉取仓库。
 *
 * - completion：同一事务内「删除 active_skill_sessions 行 + 入 outbox」（幂等 event_id）；
 * - session：ActiveSkillSession 的 DAO 透传；
 * - fetchSkills：GET /v1/skills 阻塞拉取 + SharedPreferences 缓存 + 三态解析。
 */
class SkillRepository(
    private val db: EchoDatabase,
    private val outbox: Outbox,
    private val preferences: AppPreferences,
    private val apiClient: ApiClient,
) {
    /** 记录 Skill 执行完成：同一事务内「删除 active_skill_sessions 行 + 插入 outbox」。 */
    suspend fun recordSkillCompletion(input: SkillCompletionInput, sessionId: String? = null) {
        val payload = JSONObject().apply {
            put("event_id", input.eventId)
            put("user_id", preferences.userId)
            put("skill_id", input.skillId)
            put("status", input.status)
            put("duration_seconds", input.durationSeconds)
            put("client_time", input.clientTime.toString())
        }
        db.withTransaction {
            if (sessionId != null) {
                db.dao().deleteActiveSkillSession(sessionId)
            }
            outbox.enqueue(input.eventId, "skill_completion", payload, 30)
        }
    }

    /** 便捷重载：status 取值 "completed" / "stopped" / "started"。 */
    suspend fun recordSkillCompletion(skillId: String, status: String, durationSeconds: Int) {
        recordSkillCompletion(
            SkillCompletionInput(skillId = skillId, status = status, durationSeconds = durationSeconds)
        )
    }

    // ===== ActiveSkillSession 持久化（T02，Room v5） =====

    suspend fun saveActiveSession(session: ActiveSkillSessionEntity) {
        db.dao().upsertActiveSkillSession(session)
    }

    /** 读取某 Skill 的执行会话（按 skillId 精确查询，P0-4 single-active-session）。 */
    suspend fun loadActiveSession(skillId: String): ActiveSkillSessionEntity? =
        db.dao().activeSkillSessionBySkillId(skillId)

    suspend fun hasAnyActiveSession(): Boolean = db.dao().anyActiveSkillSession() != null

    suspend fun clearActiveSessions() {
        db.dao().clearActiveSkillSessions()
    }

    suspend fun deleteActiveSession(sessionId: String) {
        db.dao().deleteActiveSkillSession(sessionId)
    }

    // ===== T11.4 Skill 卡片下发拉取 + 本地缓存 =====

    /**
     * 拉取已下发 Skill 列表（GET /v1/skills），返回三态结果 [SkillFetchResult]。
     * 缓存未过期直接返回缓存；过期/无缓存发起网络拉取；网络失败返回 loadFailed=true。
     */
    fun fetchSkills(): SkillFetchResult {
        val now = System.currentTimeMillis()
        val cacheJson = preferences.getSkillCacheJson()
        val cacheTs = preferences.getSkillCacheTimestamp()
        if (cacheJson != null && now - cacheTs < SKILL_CACHE_TTL_MS) {
            return parseSkillResponse(cacheJson)
        }
        return try {
            val (code, body) = apiClient.get("/v1/skills")
            if (code in 200..299 && !body.isNullOrBlank()) {
                preferences.setSkillCache(body)
                parseSkillResponse(body)
            } else {
                SkillFetchResult(skills = null, coldStartHint = null, loadFailed = true)
            }
        } catch (e: Exception) {
            SkillFetchResult(skills = null, coldStartHint = null, loadFailed = true)
        }
    }

    private fun parseSkillResponse(json: String): SkillFetchResult = runCatching {
        val o = JSONObject(json)
        val skills = o.optJSONArray("skills")?.let { parseSkillsArray(it) } ?: emptyList()
        val coldStartHint = if (!o.has("cold_start_hint") || o.isNull("cold_start_hint")) null
        else o.optString("cold_start_hint")
        val observationDays = o.optInt("observation_days", 0)
        SkillFetchResult(skills, coldStartHint, loadFailed = false, observationDays)
    }.getOrDefault(SkillFetchResult(skills = null, coldStartHint = null, loadFailed = true))

    private fun parseSkillsArray(arr: JSONArray): List<SkillDisplay> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SkillDisplay(
                id = o.optString("id"),
                name = o.optString("name"),
                version = o.optInt("version", 1),
                triggerConditions = o.optJSONArray("trigger_conditions")?.toTriggerStrings() ?: emptyList(),
                guardrails = o.optJSONArray("guardrails")?.toStringList() ?: emptyList(),
                steps = o.optJSONArray("steps")?.toStepDescriptions() ?: emptyList(),
                status = o.optString("status"),
                actionType = o.optString("action_type", "guided_steps"),
                estimatedDuration = if (o.has("estimated_duration") && !o.isNull("estimated_duration")) o.optInt("estimated_duration") else null,
                completionSchema = o.optString("completion_schema").takeIf { it.isNotBlank() && it != "null" },
                safetyConstraints = o.optJSONArray("safety_constraints")?.toStringList() ?: emptyList(),
                revision = o.optInt("revision", 1)
            )
        }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

    private fun JSONArray.toTriggerStrings(): List<String> =
        (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val field = o.optString("field")
            val op = o.optString("op")
            val rawValue = o.opt("value")
            val valueStr = when (rawValue) {
                is JSONArray -> (0 until rawValue.length()).joinToString("/") { rawValue.optString(it) }
                null -> ""
                else -> rawValue.toString()
            }
            listOf(field, op, valueStr).filter { it.isNotBlank() }.joinToString(" ")
        }.filter { it.isNotBlank() }

    private fun JSONArray.toStepDescriptions(): List<String> =
        (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i)
            if (o != null) o.optString("description").ifBlank { o.optString("key") }
            else optString(i)
        }.filter { it.isNotBlank() }

    companion object {
        /** Skill 缓存过期阈值：1 小时。 */
        private const val SKILL_CACHE_TTL_MS = 3_600_000L
    }
}
