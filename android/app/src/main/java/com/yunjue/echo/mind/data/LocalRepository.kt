package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.model.DerivedFeatureInput
import com.yunjue.echo.mind.model.NarrativeDisplay
import com.yunjue.echo.mind.model.NarrativeEventDisplay
import com.yunjue.echo.mind.model.NarrativeFetchResult
import com.yunjue.echo.mind.model.ProfileDisplay
import com.yunjue.echo.mind.model.SafetyDecision
import com.yunjue.echo.mind.model.Severity
import com.yunjue.echo.mind.model.SkillCompletionInput
import com.yunjue.echo.mind.model.SkillDisplay
import com.yunjue.echo.mind.security.FieldCipher
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Onboarding 激活码交换结果（T02）：对应后端 POST /v1/onboarding/verify-code 响应。
 * 不暴露 tenant_id / role / external_ref 等内部字段。
 */
data class OnboardingVerifyResult(
    val userId: String,
    val accessToken: String,
    val consentVersions: Map<String, String> = emptyMap(),
    val l0Decision: String? = null,
    val restricted: Boolean = false
)

/**
 * 激活码交换失败（T02）。
 * reason: invalid_code（404）/ restricted（403）/ server_error / malformed。
 * UI 据此展示用户可读文案，不暴露内部 HTTP 码。
 */
class OnboardingVerifyException(val reason: String) : Exception(reason)

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

    // 被动特征安全状态（PRD v0.6 契约点 1 收口后恒为 NONE，不再触发危机 UI）。
    private val _passiveSafety = MutableStateFlow<SafetyDecision?>(null)
    val passiveSafety: StateFlow<SafetyDecision?> = _passiveSafety.asStateFlow()

    // ===== UI 状态访问器（T05） =====

    fun passiveSensingConsentFlow(): Flow<Boolean> = preferences.passiveSensingEnabledFlow()
    // v0.6.1（P1-6）："最近同步"一律指**最近成功同步**（成功条件才更新）。
    fun lastSyncTimestamp(): Long = preferences.lastSuccessfulSyncAt
    fun lastCollectionTimestamp(): Long = preferences.lastCollectionTimestamp
    fun lastSyncHttpCode(): Int? = preferences.lastSyncHttpCode
    fun deadLetterCount(): Int = preferences.deadLetterCount()

    // ===== 持久化失败观测（T02 窗口 ACK 可观测性） =====

    /** 最近一次窗口持久化失败时间（epoch ms；无失败为 null）。 */
    fun lastPersistenceFailure(): Long? = preferences.lastPersistenceFailure

    /** 连续窗口持久化失败计数（成功后清零）。 */
    fun consecutivePersistenceFailures(): Int = preferences.consecutivePersistenceFailures

    /** 待上传事件数（outbox pending；趋势页"等待上传"原因用）。 */
    fun pendingUploadCount(): Int = runCatching {
        kotlinx.coroutines.runBlocking { db.dao().pendingOutbox().size }
    }.getOrDefault(0)

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

    // legacy: v0.8 removal target — saveCheckin / saveJournal / saveQuestionnaire / recordPractice
    // 已删除（主动输入范式停用，后端 410 存根 + SyncWorker deprecated 类型处理保留）。

    /**
     * 批量保存派生特征（T02 窗口 ACK 核心）：Room withTransaction 内
     * 批量落库（feature_vectors）+ 入 outbox（derived_feature），任一失败返回 false。
     *
     * - 成功才更新最近成功采集时间，并清零连续持久化失败计数；
     * - 失败记录 lastPersistenceFailure + consecutivePersistenceFailures（可观测，供支持页展示），
     *   返回 false 由调度器保留快照/缓冲并 bounded retry；
     * - **被动安全语义已收口（PRD v0.6 契约点 1）**：行为派生特征不得用于推断自杀/自伤意图，
     *   不调用 SafetyEngine.evaluatePassive，不触发 passive_red_signal 升级链路。
     */
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
        // 被动安全收口：恒 NONE，不触发危机 UI/升级（PRD 契约点 1）
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
                createdAt = now.toEpochMilli()
            )
        )
        val payload = basePayload(eventId, now).apply {
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
        enqueue(eventId, "derived_feature", payload, 20)
    }

    /**
     * 记录 Skill 执行完成（T02）：同一事务内「删除 active_skill_sessions 行 + 插入 outbox」。
     *
     * payload: {event_id, user_id, skill_id, status, duration_seconds, client_time}
     * SyncWorker 映射到 POST /v1/skills/completions（服务端 tenant+event_id 幂等）。
     * 进程死在 enqueue 前 → 会话行仍在 → 恢复流程兜底；死在 enqueue 后 → outbox 重试兜底。
     */
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
            db.dao().insertOutbox(
                OutboxEventEntity(
                    eventId = input.eventId,
                    eventType = "skill_completion",
                    payloadCiphertext = cipher.encrypt(payload.toString()),
                    priority = 30,
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    /** 便捷重载：status 取值 "completed" / "stopped" / "started"。 */
    suspend fun recordSkillCompletion(skillId: String, status: String, durationSeconds: Int) {
        recordSkillCompletion(
            SkillCompletionInput(skillId = skillId, status = status, durationSeconds = durationSeconds)
        )
    }

    // ===== ActiveSkillSession 持久化（T02，Room v5） =====

    /** 持久化/更新 Skill 执行会话（upsert）。 */
    suspend fun saveActiveSession(session: ActiveSkillSessionEntity) {
        db.dao().upsertActiveSkillSession(session)
    }

    /**
     * 读取某 Skill 的执行会话（进程重建后恢复用；**按 skillId 精确查询**，P0-4）。
     *
     * 领域规则：single-active-session —— 同时只允许一个 Skill 执行；
     * 协调器（SkillSessionCoordinator）在 start 前清除旧会话，
     * 任何卡片不得自行读取/删除全局会话（消除跨卡误删与 LIMIT 1 随机取问题）。
     */
    suspend fun loadActiveSession(skillId: String): ActiveSkillSessionEntity? =
        db.dao().activeSkillSessionBySkillId(skillId)

    /** 是否有任一 Skill 处于活动会话（协调器 start 前检查用）。 */
    suspend fun hasAnyActiveSession(): Boolean = db.dao().anyActiveSkillSession() != null

    /** 清除全部活动会话（single-active-session 切换时由协调器调用）。 */
    suspend fun clearActiveSessions() {
        db.dao().clearActiveSkillSessions()
    }

    /** 删除 Skill 执行会话（完成/停止/主动清理）。 */
    suspend fun deleteActiveSession(sessionId: String) {
        db.dao().deleteActiveSkillSession(sessionId)
    }

    // ===== 人工支持请求（escalation）客户端闭环（v0.6.1，P0-2） =====

    /** 本地 escalation 记录 Flow（支持页展示最小状态）。 */
    fun observeEscalations(): Flow<List<EscalationEntity>> = db.escalationDao().observeAll()

    /** 最新一条 escalation（支持页状态卡用）。 */
    suspend fun latestEscalation(): EscalationEntity? = db.escalationDao().latest()

    /**
     * 用户请求机构人工支持（P0-2 客户端闭环唯一入口）：
     *
     * 1. 生成 event_id（幂等键，服务端 tenant+event_id 幂等）；
     * 2. 本地持久化 escalation_requests（status=queued，等待送达）；
     * 3. 进入 Outbox（eventType="escalation"，SyncWorker 网络恢复后 POST /v1/escalations）；
     * 4. 服务端返回 escalation id 后由 SyncWorker 回写（serverEscalationId）；
     * 5. 未收到服务端 delivery 确认前，UI 只显示「等待送达」，绝不显示「人工已收到」。
     *
     * @return 生成的 eventId（幂等键）
     */
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
            db.dao().insertOutbox(
                OutboxEventEntity(
                    eventId = eventId,
                    eventType = "escalation",
                    payloadCiphertext = cipher.encrypt(payload.toString()),
                    priority = 2000, // 高于普通事件：人工支持请求尽快送达
                    createdAtEpochMs = now
                )
            )
        }
        return eventId
    }

    /**
     * 服务端处理结果回写（由 SyncWorker 调用）：
     * - 成功（2xx）：serverEscalationId + status=delivered（服务端已接收，不等于人工已收到）
     * - 幂等 replay（409/idempotent）：同样视为 delivered
     */
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
    private fun parseServerStatus(json: String): String {
        return runCatching {
            val o = JSONObject(json)
            when {
                o.optBoolean("human_acknowledged") -> EscalationStatus.TAKEN_OVER.name
                o.optBoolean("delivery_confirmed") -> EscalationStatus.DELIVERED.name
                else -> EscalationStatus.QUEUED.name
            }
        }.getOrDefault(EscalationStatus.QUEUED.name)
    }

    /** escalation dead-letter（本地放弃）：用户可见 FAILED（需重新联系机构）。 */
    suspend fun markEscalationFailed(eventId: String) {
        val row = db.escalationDao().byEventId(eventId) ?: return
        db.escalationDao().upsert(
            row.copy(status = EscalationStatus.FAILED.name, updatedAtEpochMs = System.currentTimeMillis())
        )
    }

    /**
     * 拉取某 escalation 的 user-status（GET /v1/escalations/{id}/user-status）并回写。
     * 未收到服务端确认前 UI 不得显示"人工已收到"（服务端 human_acknowledged 才回写）。
     */
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

    // ===== v0.6.1（P1-7）：Onboarding READY 收敛 =====

    /**
     * 服务端 ack 依据收敛：GET /v1/onboarding/consents/latest 核对本地已提交的
     * 各 consent 均已在服务端生效后，置 serverActivated=true 并推进 READY。
     *
     * 幂等：已 READY 时直接返回；网络失败保持 READY_OFFLINE（下次同步重试）。
     */
    suspend fun confirmServerActivation(): Boolean {
        val state = preferences.onboardingState
        if (state == AppPreferences.ONBOARDING_READY) return true
        return withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = apiClient.get("/v1/onboarding/consents/latest?user_id=${preferences.userId}")
                if (code in 200..299 && !body.isNullOrBlank()) {
                    val o = JSONObject(body)
                    // 服务端 ack 依据：至少 psychological_data 已 grant 且被动感知 grant 与本地一致
                    val psy = o.optJSONObject("psychological_data")
                    val localPassive = kotlinx.coroutines.runBlocking {
                        preferences.passiveSensingPrefs.passiveSensingEnabled.first()
                    }
                    val serverPassive = o.optJSONObject("passive_sensing")
                    val passiveOk = if (localPassive) {
                        serverPassive?.optBoolean("granted") == true && (serverPassive.isNull("revoked_at") || !serverPassive.optBoolean("revoked_at"))
                    } else {
                        serverPassive == null || serverPassive.optBoolean("granted") == false
                    }
                    val accepted = psy != null && psy.optBoolean("granted") && passiveOk
                    if (accepted) {
                        preferences.serverActivated = true
                        preferences.onboardingState = AppPreferences.ONBOARDING_READY
                        preferences.consentSyncPending = false
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }.getOrDefault(false)
        }
    }

    // ===== Onboarding 激活码交换（T02） =====

    /**
     * 激活码交换：POST /v1/onboarding/verify-code（预认证，无 Authorization 头）。
     *
     * 成功 → 安全存储 userId + 加密 accessToken（AppPreferences），推进 onboardingState=BOUND。
     * 失败 → 抛 [OnboardingVerifyException]（reason: invalid_code / restricted / server_error / malformed），
     * UI 据此展示用户可读文案（不暴露内部码）。
     */
    suspend fun verifyOnboardingCode(code: String): OnboardingVerifyResult {
        return withContext(Dispatchers.IO) {
            val requestBody = JSONObject().apply { put("code", code) }.toString()
            val (httpCode, responseBody) = apiClient.postWithBody("/v1/onboarding/verify-code", requestBody)
            when {
                httpCode == 404 -> throw OnboardingVerifyException("invalid_code")
                httpCode == 403 -> throw OnboardingVerifyException("restricted")
                httpCode !in 200..299 || responseBody.isNullOrBlank() -> throw OnboardingVerifyException("server_error")
                else -> {
                    val result = runCatching { parseVerifyCodeResponse(responseBody) }
                        .getOrElse { throw OnboardingVerifyException("malformed") }
                    if (result.userId.isBlank() || result.accessToken.isBlank()) {
                        throw OnboardingVerifyException("malformed")
                    }
                    preferences.userId = result.userId
                    preferences.accessToken = result.accessToken
                    preferences.onboardingState = AppPreferences.ONBOARDING_BOUND
                    result
                }
            }
        }
    }

    private fun parseVerifyCodeResponse(body: String): OnboardingVerifyResult {
        val o = JSONObject(body)
        val consentVersions = o.optJSONObject("consent_versions")?.let { co ->
            co.keys().asSequence().associateWith { co.optString(it) }
        } ?: emptyMap()
        return OnboardingVerifyResult(
            userId = o.optString("user_id"),
            accessToken = o.optString("access_token"),
            consentVersions = consentVersions,
            l0Decision = o.optString("l0_decision").takeIf { it.isNotBlank() && it != "null" },
            restricted = o.optBoolean("restricted", false)
        )
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

    fun decryptJournal(value: JournalEntity): String = value.bodyCiphertext?.let(cipher::decrypt).orEmpty()

    // ===== P5 灰度回滚：feature flags 拉取 + 缓存 =====

    /**
     * 拉取本租户的 feature flags（GET /v1/config/flags），缓存到 [AppPreferences]。
     *
     * - 网络成功：解析 JSON 为 Map<String, Boolean>，同步写入 AppPreferences 后返回。
     * - 网络失败（异常 / 非 2xx / 解析失败）：返回 AppPreferences 缓存；无缓存返回 **fail-closed 默认**
     *   （passive_sensing_enabled / sandbox_enabled = false，skills_delivery_enabled = true）。
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
                        "passive_sensing_enabled" to o.optBoolean("passive_sensing_enabled", false),
                        "sandbox_enabled" to o.optBoolean("sandbox_enabled", false),
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
     * 批量拉取近 [days] 天每日叙事（T05，GET /v1/narratives?user_id=&from=&to=）。
     *
     * 替代旧实现逐日 7 次串行请求；响应支持数组 / {"narratives":[...]} / {"items":[...]} /
     * {"results":[...]} / 单日对象等多种形态（后端 T03 收口前向后兼容）。
     *
     * 失败语义：
     * - 网络/非 2xx/解析失败且无缓存 → loadFailed=true（趋势页 ERROR 态，不得伪装 no_data）
     * - 网络/非 2xx 但存在缓存 → fromCache=true（OFFLINE_CACHED 态）
     *
     * 阻塞调用（HttpURLConnection），调用方须在 IO 线程执行。
     */
    fun fetchNarratives(days: Int = 7): NarrativeFetchResult {
        val today = LocalDate.now(ZoneOffset.UTC)
        val from = today.minusDays((days - 1).toLong())
        val to = today
        return try {
            val (code, body) = apiClient.get(
                "/v1/narratives?user_id=${preferences.userId}&from=$from&to=$to"
            )
            if (code in 200..299 && !body.isNullOrBlank()) {
                val result = parseNarrativeRange(body, from, to)
                if (!result.loadFailed) {
                    preferences.setNarrativeCache(body)
                }
                result
            } else {
                fromNarrativeCacheOrFail(from, to)
            }
        } catch (e: Exception) {
            fromNarrativeCacheOrFail(from, to)
        }
    }

    private fun fromNarrativeCacheOrFail(from: LocalDate, to: LocalDate): NarrativeFetchResult {
        val cached = preferences.getNarrativeCacheJson()
        if (cached != null) {
            return parseNarrativeRange(cached, from, to).copy(fromCache = true, loadFailed = false)
        }
        return NarrativeFetchResult(
            narratives = emptyList(),
            loadFailed = true,
            fromCache = false,
            dataCoverage = 0f,
            missingDates = emptyList(),
            isPartial = false
        )
    }

    /**
     * 拉取用户画像（GET /v1/profile/{user_id}），返回 [ProfileDisplay]。
     *
     * 失败语义（T05）：网络异常 / 非 2xx / 解析失败返回 loadFailed=true 的缺省画像，
     * 趋势页据此区分 error（loadFailed）与 no_data（真无数据）。
     *
     * 阻塞调用（HttpURLConnection），调用方须在 IO 线程执行。
     */
    fun fetchProfile(): ProfileDisplay {
        val (code, body) = try {
            apiClient.get("/v1/profile/${preferences.userId}")
        } catch (e: Exception) {
            return defaultProfile(loadFailed = true)
        }
        if (code !in 200..299 || body.isNullOrBlank()) return defaultProfile(loadFailed = true)
        return runCatching {
            val o = JSONObject(body)
            val traits = o.optJSONObject("traits") ?: JSONObject()
            val sourcesUnion = traits.optJSONArray("sources_present_union")?.toStringList() ?: emptyList()
            ProfileDisplay(
                observationDays = traits.optInt("observation_days", 0),
                narrativeDaysLast7 = traits.optInt("narrative_days_last_7", 0),
                version = o.optInt("version", 1),
                sourcesPresentUnion = sourcesUnion,
                updatedAt = o.optString("updated_at").takeIf { it.isNotBlank() && it != "null" },
                loadFailed = false
            )
        }.getOrDefault(defaultProfile(loadFailed = true))
    }

    private fun defaultProfile(loadFailed: Boolean = false) = ProfileDisplay(
        observationDays = 0,
        narrativeDaysLast7 = 0,
        version = 0,
        sourcesPresentUnion = emptyList(),
        loadFailed = loadFailed
    )

    /** 解析 /v1/narratives 单日对象为 [NarrativeDisplay]；失败返回 null。 */
    private fun parseNarrative(json: String): NarrativeDisplay? = runCatching {
        val o = JSONObject(json)
        val events = o.optJSONArray("events")?.let { arr ->
            (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                NarrativeEventDisplay(
                    source = e.optString("source"),
                    summary = e.optString("summary"),
                    sourcesPresent = e.optJSONArray("sources_present")?.toStringList() ?: emptyList()
                )
            }
        } ?: emptyList()
        NarrativeDisplay(
            date = o.optString("date"),
            events = events,
            gaps = o.optJSONArray("gaps")?.toStringList() ?: emptyList()
        )
    }.getOrNull()

    /**
     * 解析批量叙事响应为 [NarrativeFetchResult]（含 data coverage / missing dates）。
     */
    private fun parseNarrativeRange(json: String, from: LocalDate, to: LocalDate): NarrativeFetchResult =
        runCatching {
            val root = JSONObject(json)
            val narratives = mutableListOf<NarrativeDisplay>()
            val arr = when {
                root.has("narratives") -> root.optJSONArray("narratives")
                root.has("items") -> root.optJSONArray("items")
                root.has("results") -> root.optJSONArray("results")
                else -> null
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    parseNarrative(item.toString())?.let { narratives.add(it) }
                }
            } else {
                // 单日对象兜底
                parseNarrative(json)?.let { narratives.add(it) }
            }
            val sorted = narratives.sortedBy { it.date }
            val presentDates = sorted.map { it.date }.toSet()
            val missingDates = generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to) }
                .map { it.toString() }
                .filter { it !in presentDates }
                .toList()
            val totalDays = ChronoUnit.DAYS.between(from, to) + 1
            val coverage = if (totalDays > 0) {
                (sorted.size.toFloat() / totalDays.toFloat()).coerceIn(0f, 1f)
            } else 0f
            NarrativeFetchResult(
                narratives = sorted,
                loadFailed = false,
                fromCache = false,
                dataCoverage = coverage,
                missingDates = missingDates,
                isPartial = missingDates.isNotEmpty() && sorted.isNotEmpty()
            )
        }.getOrElse {
            NarrativeFetchResult(
                narratives = emptyList(),
                loadFailed = true,
                fromCache = false,
                dataCoverage = 0f,
                missingDates = emptyList(),
                isPartial = false
            )
        }

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
     * - v0.6 执行契约字段（PRD 契约点 4）全部保留：action_type / estimated_duration /
     *   completion_schema / safety_constraints / revision（T02 修复缺陷 7）。
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
