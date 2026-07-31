package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.model.CheckinInput
import com.yunjue.echo.mind.model.DerivedFeatureInput
import com.yunjue.echo.mind.model.JournalInput
import com.yunjue.echo.mind.model.NarrativeDisplay
import com.yunjue.echo.mind.model.NarrativeEventDisplay
import com.yunjue.echo.mind.model.ProfileDisplay
import com.yunjue.echo.mind.model.QuestionnaireScore
import com.yunjue.echo.mind.model.SafetyDecision
import com.yunjue.echo.mind.model.Severity
import com.yunjue.echo.mind.model.SkillDisplay
import com.yunjue.echo.mind.security.FieldCipher
import com.yunjue.echo.mind.security.QuestionnaireScorer
import com.yunjue.echo.mind.security.SafetyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

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

class LocalRepository(
    private val db: EchoDatabase,
    private val cipher: FieldCipher,
    private val preferences: AppPreferences,
    private val apiClient: ApiClient = ApiClient { preferences.accessToken }
) {
    fun observeCheckins(): Flow<List<CheckinEntity>> = db.dao().observeCheckins()
    fun observeJournals(): Flow<List<JournalEntity>> = db.dao().observeJournals()
    fun observeQuestionnaires(): Flow<List<QuestionnaireEntity>> = db.dao().observeQuestionnaires()
    fun observePractices(): Flow<List<PracticeCompletionEntity>> = db.dao().observePractices()
    fun observePendingCount(): Flow<Int> = db.dao().observePendingCount()

    // P5 灰度回滚：feature flags Flow，UI 观察后联动 Skill 卡片显示/隐藏。
    val featureFlagsFlow: Flow<Map<String, Boolean>> = preferences.featureFlagsFlow

    // 被动特征安全状态：saveDerivedFeature 产出 SafetyDecision 后推送，UI 观察后切到 SafetyScreen。
    // 主动文本（saveCheckin/saveJournal）通过返回值直接驱动 UI，无需经此 Flow。
    private val _passiveSafety = MutableStateFlow<SafetyDecision?>(null)
    val passiveSafety: StateFlow<SafetyDecision?> = _passiveSafety.asStateFlow()

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

    /**
     * P1.3：保存麦克风派生特征专用 consent（voice_features）。
     *
     * - evidence_hash = SHA-256("voice-features-consent-2026.07:$userId:$granted")
     *   固定盐 + 用户 ID + granted 状态，确保可重算可校验
     * - 复用 [saveConsent]，consentType="voice_features"，
     *   version="voice-features-consent-2026.07"，priority=600（高于普通 consent 500）
     * - 入 outbox（eventType="consent"）走 SyncWorker 上传
     *
     * @param granted true=授权开启；false=撤回（权限拒绝或用户撤回时调用）
     */
    suspend fun saveVoiceFeaturesConsent(granted: Boolean) {
        val userId = preferences.userId
        val evidenceInput = "voice-features-consent-2026.07:$userId:$granted"
        val evidenceHash = MessageDigest.getInstance("SHA-256")
            .digest(evidenceInput.toByteArray(Charsets.UTF_8))
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

    /**
     * 保存派生特征：特征落库（summary 加密）+ 入 outbox + 调用 SafetyEngine.evaluatePassive。
     *
     * - eventType = "derived_feature"，priority = 20
     * - 上传 payload 仅含 summary + vector，不含原始传感数据
     * - 被动命中 RED 时 enqueue escalation（trigger="passive_red_signal"，priority=1000），
     *   与现有 saveCheckin 文本 RED 路径一致
     * - 同时把 SafetyDecision 推送到 [passiveSafety] StateFlow，供 UI 观察切到 SafetyScreen
     */
    suspend fun saveDerivedFeature(input: DerivedFeatureInput): SafetyDecision {
        val eventId = "feat_${UUID.randomUUID()}"
        val now = Instant.now()
        // 特征落库（summary 加密）
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
                createdAt = now.toEpochMilli()
            )
        )
        // 入 outbox：payload 含 DerivedFeatureInput JSON
        val payload = basePayload(eventId, now).apply {
            put("schema_version", input.schemaVersion)
            put("source", input.source)
            put("window_start", input.windowStart.toString())
            put("window_end", input.windowEnd.toString())
            put("summary", input.summary)
            put("vector", JSONArray(input.vector))
        }
        enqueue(eventId, "derived_feature", payload, 20)
        // 被动特征安全评估：命中确定性红色规则即冻结生成并升级
        val decision = SafetyEngine.evaluatePassive(input.summary)
        if (decision.severity == Severity.RED) {
            enqueueEscalation(
                trigger = "passive_red_signal",
                evidence = "被动特征命中确定性红色规则"
            )
        }
        // 推送到 StateFlow，UI 观察后在 severity==RED 时切到 SafetyScreen
        _passiveSafety.value = decision
        return decision
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

    // ===== P5 灰度回滚：feature flags 拉取 + 缓存 =====

    /**
     * 拉取本租户的 feature flags（GET /v1/config/flags），缓存到 [AppPreferences]。
     *
     * - 网络成功：解析 JSON 为 Map<String, Boolean>，同步写入 AppPreferences 后返回。
     * - 网络失败（异常 / 非 2xx / 解析失败）：返回 AppPreferences 缓存；无缓存返回默认全 true。
     *
     * 阻塞调用（HttpURLConnection），调用方须在 IO 线程执行。
     */
    suspend fun fetchFeatureFlags(): Map<String, Boolean> {
        return withContext(Dispatchers.IO) {
            val (code, body) = try {
                apiClient.get("/v1/config/flags")
            } catch (e: Exception) {
                return@withContext preferences.getFeatureFlagsSnapshot()
            }
            if (code in 200..299 && !body.isNullOrBlank()) {
                runCatching {
                    val o = JSONObject(body)
                    val flags = mapOf(
                        "passive_sensing_enabled" to o.optBoolean("passive_sensing_enabled", true),
                        "sandbox_enabled" to o.optBoolean("sandbox_enabled", true),
                        "skills_delivery_enabled" to o.optBoolean("skills_delivery_enabled", true),
                    )
                    preferences.setFeatureFlags(flags)
                    flags
                }.getOrDefault(preferences.getFeatureFlagsSnapshot())
            } else {
                preferences.getFeatureFlagsSnapshot()
            }
        }
    }

    // ===== T11.4 Skill 卡片下发拉取 + 本地缓存 =====

    /**
     * 拉取已下发 Skill 列表（GET /v1/skills），返回三态结果 [SkillFetchResult]。
     *
     * 缓存策略：
     * - 缓存（SharedPreferences JSON + 时间戳）未过期（< [SKILL_CACHE_TTL_MS]）直接返回缓存解析结果。
     * - 过期或无缓存则发起网络拉取；成功后写入缓存。
     * - 网络失败（异常 / 非 2xx / 解析失败）返回 [SkillFetchResult] 的 loadFailed=true，
     *   UI 据此展示「加载失败」+ 重试按钮，与「真无 Skill」冷启动区分。
     *
     * 注意：本方法为阻塞调用（HttpURLConnection），调用方须在 IO 线程执行。
     */
    fun fetchSkills(): SkillFetchResult {
        val now = System.currentTimeMillis()
        val cacheJson = preferences.getSkillCacheJson()
        val cacheTs = preferences.getSkillCacheTimestamp()
        // 1. 缓存未过期 → 直接用缓存，避免网络
        if (cacheJson != null && now - cacheTs < SKILL_CACHE_TTL_MS) {
            return parseSkillResponse(cacheJson)
        }
        // 2. 过期或无缓存 → 网络拉取
        return try {
            val (code, body) = apiClient.get("/v1/skills")
            if (code in 200..299 && !body.isNullOrBlank()) {
                preferences.setSkillCache(body)
                parseSkillResponse(body)
            } else {
                // 非 2xx：加载失败
                SkillFetchResult(skills = null, coldStartHint = null, loadFailed = true)
            }
        } catch (e: Exception) {
            // 网络异常：加载失败
            SkillFetchResult(skills = null, coldStartHint = null, loadFailed = true)
        }
    }

    /**
     * T12.4：拉取近 [days] 天的每日叙事（GET /v1/narratives），返回 [List]<[NarrativeDisplay]>。
     *
     * 后端 /v1/narratives 按单日返回（date 参数），此处按 UTC 日期逐日拉取最近 [days] 天，
     * 供趋势视图绘制 mood_hint 折线。任一日拉取失败则跳过该日；全部失败返回空列表。
     *
     * 阻塞调用（HttpURLConnection），调用方须在 IO 线程执行。
     */
    fun fetchNarratives(days: Int = 7): List<NarrativeDisplay> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val result = mutableListOf<NarrativeDisplay>()
        for (i in days - 1 downTo 0) {
            val day = today.minusDays(i.toLong())
            val (code, body) = try {
                apiClient.get("/v1/narratives?user_id=${preferences.userId}&date=$day")
            } catch (e: Exception) {
                continue
            }
            if (code in 200..299 && !body.isNullOrBlank()) {
                parseNarrative(body)?.let { result.add(it) }
            }
        }
        return result
    }

    /**
     * T12.4：拉取用户画像（GET /v1/profile/{user_id}），返回 [ProfileDisplay]。
     *
     * traits 含 observation_days / narrative_days_last_7 / recent_mood_hint。
     * 拉取失败（网络异常 / 非 2xx / 解析失败）返回缺省画像（观察天数 0、最近情绪「未知」）。
     *
     * 阻塞调用（HttpURLConnection），调用方须在 IO 线程执行。
     */
    fun fetchProfile(): ProfileDisplay {
        val (code, body) = try {
            apiClient.get("/v1/profile/${preferences.userId}")
        } catch (e: Exception) {
            return defaultProfile()
        }
        if (code !in 200..299 || body.isNullOrBlank()) return defaultProfile()
        return runCatching {
            val o = JSONObject(body)
            val traits = o.optJSONObject("traits") ?: JSONObject()
            ProfileDisplay(
                observationDays = traits.optInt("observation_days", 0),
                narrativeDaysLast7 = traits.optInt("narrative_days_last_7", 0),
                recentMoodHint = traits.optString("recent_mood_hint", "未知"),
                version = o.optInt("version", 1),
                updatedAt = o.optString("updated_at").takeIf { it.isNotBlank() && it != "null" }
            )
        }.getOrDefault(defaultProfile())
    }

    private fun defaultProfile() = ProfileDisplay(
        observationDays = 0,
        narrativeDaysLast7 = 0,
        recentMoodHint = "未知",
        version = 0
    )

    /** 解析 /v1/narratives 单日响应为 [NarrativeDisplay]；失败返回 null。 */
    private fun parseNarrative(json: String): NarrativeDisplay? = runCatching {
        val o = JSONObject(json)
        val events = o.optJSONArray("events")?.let { arr ->
            (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                NarrativeEventDisplay(
                    source = e.optString("source"),
                    summary = e.optString("summary"),
                    moodHint = e.optString("mood_hint")
                )
            }
        } ?: emptyList()
        NarrativeDisplay(
            date = o.optString("date"),
            moodHint = o.optString("mood_hint"),
            events = events,
            gaps = o.optJSONArray("gaps")?.toStringList() ?: emptyList()
        )
    }.getOrNull()

    /**
     * 解析 /v1/skills 响应（P3 起为 JSON 对象 {"skills":[...], "cold_start_hint":..., "observation_days":N}）。
     *
     * - skills 数组降维为 [List]<[SkillDisplay]>（trigger_conditions / steps 转可读字符串）。
     * - cold_start_hint 仅在列表为空时非 null，用于端侧分阶段文案。
     * - observation_days 用于格式化 stage_1_3 的「已采集 N 天」占位。
     * - 解析失败返回 loadFailed=true，UI 据此展示重试按钮。
     */
    private fun parseSkillResponse(json: String): SkillFetchResult = runCatching {
        val o = JSONObject(json)
        val skills = o.optJSONArray("skills")?.let { parseSkillsArray(it) } ?: emptyList()
        val coldStartHint = if (!o.has("cold_start_hint") || o.isNull("cold_start_hint")) null
            else o.optString("cold_start_hint")
        val observationDays = o.optInt("observation_days", 0)
        SkillFetchResult(skills, coldStartHint, loadFailed = false, observationDays)
    }.getOrDefault(SkillFetchResult(skills = null, coldStartHint = null, loadFailed = true))

    /**
     * 解析 skills JSON 数组为 [List]<[SkillDisplay]>。
     *
     * - trigger_conditions / steps 在后端是 list[dict]，此处降维为可读字符串：
     *   trigger_conditions 取 field/op/value 拼接；steps 取 description（缺则 key）。
     * - guardrails 后端即 list[str]，原样映射。
     */
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
                status = o.optString("status")
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
