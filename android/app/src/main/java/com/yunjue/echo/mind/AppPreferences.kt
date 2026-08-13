package com.yunjue.echo.mind

import android.content.Context
import com.yunjue.echo.mind.security.FieldCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

class AppPreferences(
    context: Context,
    private val cipher: FieldCipher,
    val passiveSensingPrefs: PassiveSensingPrefs = PassiveSensingPrefs(context)
) {
    private val prefs = context.getSharedPreferences("echo_mind_app_state", Context.MODE_PRIVATE)

    var institutionCode: String
        get() = prefs.getString("institution_code", "") ?: ""
        set(value) = prefs.edit().putString("institution_code", value).apply()

    var userId: String
        get() = prefs.getString("user_id", "") ?: ""
        set(value) = prefs.edit().putString("user_id", value).apply()

    var accessToken: String?
        get() = prefs.getString("access_token_ciphertext", null)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove("access_token_ciphertext")
            else putString("access_token_ciphertext", cipher.encrypt(value))
        }.apply()

    // ===== Skill 卡片下发缓存（T11.4） =====
    // 用 SharedPreferences 缓存 GET /v1/skills 的原始 JSON + 时间戳，避免 Room 迁移。
    // Skill 为只读下发数据，不加密存储；过期由 [SkillRepository.fetchSkills] 判定刷新。

    fun getSkillCacheJson(): String? = prefs.getString("skill_cache_json", null)
    fun getSkillCacheTimestamp(): Long = prefs.getLong("skill_cache_ts", 0L)
    fun setSkillCache(json: String) = prefs.edit()
        .putString("skill_cache_json", json)
        .putLong("skill_cache_ts", System.currentTimeMillis())
        .apply()

    // ===== 趋势叙事缓存（T05） =====
    // GET /v1/narratives 批量响应的原始 JSON 缓存；网络失败时供 OFFLINE_CACHED 态使用。

    fun setNarrativeCache(json: String) = prefs.edit().putString("narrative_cache_json", json).apply()
    fun getNarrativeCacheJson(): String? = prefs.getString("narrative_cache_json", null)

    // ===== 画像反馈本地记录（Milestone F） =====
    // 「这个描述像今天的你吗？」点击后仅本地记录（date → "yes"/"no"），
    // 不新增网络请求；后续版本再按此键值上报到 /v1/portraits/{date}/feedback。
    // 存储为 JSON 字符串：{"2026-08-10":"yes", ...}。

    private val KEY_PORTRAIT_FEEDBACK = "portrait_feedback_json"

    fun recordPortraitFeedback(date: String, helpful: Boolean) {
        val current = runCatching { JSONObject(prefs.getString(KEY_PORTRAIT_FEEDBACK, null)) }
            .getOrElse { JSONObject() }
        current.put(date, if (helpful) "yes" else "no")
        prefs.edit().putString(KEY_PORTRAIT_FEEDBACK, current.toString()).apply()
    }

    /** 某日画像反馈：true=挺像 / false=不太像 / null=尚未反馈。 */
    fun portraitFeedback(date: String): Boolean? {
        val json = prefs.getString(KEY_PORTRAIT_FEEDBACK, null) ?: return null
        return runCatching {
            val o = JSONObject(json)
            when (o.optString(date, "")) {
                "yes" -> true
                "no" -> false
                else -> null
            }
        }.getOrDefault(null)
    }

    // ===== P5 灰度回滚：feature flags 缓存（SharedPreferences） =====
    // 移动端拉取 GET /v1/config/flags 后缓存，端侧灰度联动：
    // - passive_sensing_enabled=false → PassiveSensingService 不启动
    // - skills_delivery_enabled=false → 隐藏 Skill 卡片区
    // 缓存为 JSON 字符串（与后端返回格式一致），读取时解析为 Map。
    // **fail-closed（02b 共享知识 2）**：无缓存 / 缺 key / 解析失败时，
    // passive_sensing_enabled 与 sandbox_enabled 一律默认 false（隐私敏感不采集、不跑沙箱）；
    // skills_delivery_enabled 非隐私敏感默认 true。
    // 使用 SharedPreferences 而非 DataStore，因 PassiveSensingService 需同步读取。

    private val _featureFlagsFlow = MutableStateFlow(getFeatureFlagsSnapshot())
    val featureFlagsFlow: Flow<Map<String, Boolean>> = _featureFlagsFlow

    /** 同步写入 feature flags 缓存（commit，确保落盘后才返回）。 */
    fun setFeatureFlags(flags: Map<String, Boolean>) {
        val json = JSONObject().apply {
            flags.forEach { (k, v) -> put(k, v) }
        }.toString()
        prefs.edit().putString(KEY_FEATURE_FLAGS, json).commit()
        _featureFlagsFlow.value = parseFlagsJson(json)
    }

    /** 同步读取 feature flags 缓存；无缓存返回 fail-closed 默认（隐私 flag=false）。 */
    fun getFeatureFlagsSnapshot(): Map<String, Boolean> {
        val json = prefs.getString(KEY_FEATURE_FLAGS, null) ?: return defaultFlags()
        return parseFlagsJson(json)
    }

    private fun parseFlagsJson(json: String): Map<String, Boolean> = runCatching {
        val o = JSONObject(json)
        mapOf(
            "passive_sensing_enabled" to o.optBoolean("passive_sensing_enabled", false),
            "sandbox_enabled" to o.optBoolean("sandbox_enabled", false),
            "skills_delivery_enabled" to o.optBoolean("skills_delivery_enabled", true),
        )
    }.getOrDefault(defaultFlags())

    private fun defaultFlags() = mapOf(
        "passive_sensing_enabled" to false,
        "sandbox_enabled" to false,
        "skills_delivery_enabled" to true,
    )

    // ===== 采集 / 同步状态（T02/T05） =====

    /** 最近成功采集时间（epoch ms；saveDerivedFeatures 落库成功时刷新）。 */
    var lastCollectionTimestamp: Long
        get() = prefs.getLong("last_collection_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_collection_timestamp", value).apply()

    /** 最近成功同步时间（epoch ms；SyncWorker 处理完一批后刷新）。 */
    var lastSyncTimestamp: Long
        get() = prefs.getLong("last_sync_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_sync_timestamp", value).apply()

    /** 最近一次窗口持久化失败时间（epoch ms；无失败时为 null）。 */
    var lastPersistenceFailure: Long?
        get() = if (prefs.contains("last_persistence_failure")) prefs.getLong("last_persistence_failure", 0L) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("last_persistence_failure") else edit.putLong("last_persistence_failure", value)
            edit.apply()
        }

    /** 连续窗口持久化失败计数（成功后清零，供支持页观测）。 */
    var consecutivePersistenceFailures: Int
        get() = prefs.getInt("consecutive_persistence_failures", 0)
        set(value) = prefs.edit().putInt("consecutive_persistence_failures", value).apply()

    /** 最近一次同步批次末尾的 HTTP code（仅供 UI 映射 SyncState，不暴露给用户）。 */
    var lastSyncHttpCode: Int?
        get() = if (prefs.contains("last_sync_http_code")) prefs.getInt("last_sync_http_code", 0) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("last_sync_http_code") else edit.putInt("last_sync_http_code", value)
            edit.apply()
        }

    // ===== v0.6.1（P1-6）：同步状态语义拆分 =====
    // 不再用单个 timestamp 同时表达"尝试同步"与"成功同步"。

    /** 最近一次**尝试**同步时间（SyncWorker 每批开始时刷新；无论成败）。 */
    var lastSyncAttemptAt: Long
        get() = prefs.getLong("last_sync_attempt_at", 0L)
        set(value) = prefs.edit().putLong("last_sync_attempt_at", value).apply()

    /**
     * 最近一次**成功**同步时间：只有满足成功条件（本批无 pending 遗留
     * 且无 retry/blocked/失败事件）才更新。
     */
    var lastSuccessfulSyncAt: Long
        get() = prefs.getLong("last_successful_sync_at", 0L)
        set(value) = prefs.edit().putLong("last_successful_sync_at", value).apply()

    /** 最近一次部分成功同步时间（本批有成功事件但仍有遗留：429/5xx/blocked）。 */
    var lastPartialSyncAt: Long?
        get() = if (prefs.contains("last_partial_sync_at")) prefs.getLong("last_partial_sync_at", 0L) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("last_partial_sync_at") else edit.putLong("last_partial_sync_at", value)
            edit.apply()
        }

    /** 最近一次同步错误类别（分类而非原始 exception；UI 据此显示可读文案）。 */
    var lastSyncErrorClass: String?
        get() = if (prefs.contains("last_sync_error_class")) prefs.getString("last_sync_error_class", null) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("last_sync_error_class") else edit.putString("last_sync_error_class", value)
            edit.apply()
        }

    // ===== v0.6.2（Batch A）：401/403 认证暂停语义 =====
    // 认证失效是「暂停」不是「放弃」：批内遇 401/403 后停止后台重试
    // （WorkManager 不再因 auth 高频 retry），直到用户重新认证成功清除该状态。

    /** 最近一次 401/403 认证失败时间（epoch ms）；>0 表示后台同步处于认证暂停态。 */
    var lastAuthBlockedAt: Long
        get() = prefs.getLong("last_auth_blocked_at", 0L)
        set(value) = prefs.edit().putLong("last_auth_blocked_at", value).apply()

    /** 认证暂停态（401/403 后为 true，SyncWorker.doWork 开头据此直接 success 返回）。 */
    val authRequired: Boolean
        get() = lastAuthBlockedAt > 0L

    /** 清除认证暂停态（重新认证成功时调用，见 OnboardingRepository.verifyOnboardingCode）。 */
    fun clearAuthBlocked() {
        prefs.edit().remove("last_auth_blocked_at").apply()
    }

    /**
     * 待上传事件数（每次 SyncWorker 批处理结束时写回；UI「有 N 项待同步」）。
     */
    var pendingCountSnapshot: Int
        get() = prefs.getInt("pending_count_snapshot", 0)
        set(value) = prefs.edit().putInt("pending_count_snapshot", value).apply()

    // ===== v0.6.1（P0-3 B）：consent 重新授权等待同步标记 =====
    // 本地已 ON、服务端尚未接受 granted 证据前为 true；
    // SyncWorker 收到 consent 成功（2xx）后清除。

    var consentSyncPending: Boolean
        get() = prefs.getBoolean("consent_sync_pending", false)
        set(value) = prefs.edit().putBoolean("consent_sync_pending", value).apply()

    /** 被动采集服务是否处于运行状态（服务 start/stop 时更新；UI 观察用）。 */
    private val _sensingActiveFlow = MutableStateFlow(prefs.getBoolean("sensing_active", false))
    val sensingActiveFlow: Flow<Boolean> = _sensingActiveFlow

    var sensingActive: Boolean
        get() = prefs.getBoolean("sensing_active", false)
        set(value) {
            prefs.edit().putBoolean("sensing_active", value).apply()
            _sensingActiveFlow.value = value
        }

    // ===== SyncWorker 本地状态（dead-letter / 迁移 telemetry / Retry-After） =====

    /** 已 dead-letter 的事件标记集合（毒丸保护：超限/永久失败不再重试）。 */
    fun getDeadLetterEvents(): Set<String> = prefs.getStringSet("dead_letter_events", emptySet()) ?: emptySet()

    fun addDeadLetterEvent(event: String) {
        val current = prefs.getStringSet("dead_letter_events", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(event)
        prefs.edit().putStringSet("dead_letter_events", current).apply()
    }

    fun deadLetterCount(): Int = getDeadLetterEvents().size

    /** 410 迁移 telemetry 计数（不敏感，如 {"event_type":"journal","migrated":true} 的计数）。 */
    fun recordMigrationTelemetry(eventType: String) {
        val key = "migrated_$eventType"
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun migrationTelemetryCount(eventType: String): Int = prefs.getInt("migrated_$eventType", 0)

    /** 未知事件类型 telemetry 计数（毒丸防毒化路径可观测；v0.6.2 Batch A）。 */
    fun recordUnknownTypeTelemetry(eventType: String) {
        val key = "unknown_type_$eventType"
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun unknownTypeTelemetryCount(eventType: String): Int = prefs.getInt("unknown_type_$eventType", 0)

    /** 记录 429 Retry-After 秒数（WorkManager 指数退避接管重试节奏）。 */
    fun recordRetryAfter(eventType: String, seconds: Int) {
        prefs.edit().putInt("${eventType}_retry_after", seconds).apply()
    }

    // ===== 被动采集相关（DataStore 存储，委托给 PassiveSensingPrefs） =====

    fun passiveSensingEnabledFlow(): Flow<Boolean> = passiveSensingPrefs.passiveSensingEnabled
    fun micEnabledFlow(): Flow<Boolean> = passiveSensingPrefs.micEnabled
    fun samplingConfigFlow(): Flow<String> = passiveSensingPrefs.samplingConfig

    suspend fun setPassiveSensingEnabled(enabled: Boolean) =
        passiveSensingPrefs.setPassiveSensingEnabled(enabled)

    suspend fun setMicEnabled(enabled: Boolean) =
        passiveSensingPrefs.setMicEnabled(enabled)

    suspend fun setSamplingConfig(config: String) =
        passiveSensingPrefs.setSamplingConfig(config)

    fun clearServiceState() {
        prefs.edit().clear().apply()
        _featureFlagsFlow.value = defaultFlags()
        _sensingActiveFlow.value = false
    }

    // ===== Onboarding 七态状态机（T02） =====
    // 与服务端激活资源可推导：consents / L0 经 GET /v1/onboarding/consents/latest 核对；
    // restricted 由 verify-code 响应/403 决定；withdrawn 由 DSR revoke_service 状态决定。

    var onboardingState: String
        get() = prefs.getString("onboarding_state", ONBOARDING_NOT_STARTED) ?: ONBOARDING_NOT_STARTED
        set(value) = prefs.edit().putString("onboarding_state", value).apply()

    /** Onboarding 是否已完成（READY_OFFLINE / READY）。旧版 onboarding_completed 位向后兼容。 */
    val onboardingCompleted: Boolean
        get() = when (onboardingState) {
            ONBOARDING_READY, ONBOARDING_READY_OFFLINE -> true
            else -> prefs.getBoolean("legacy_onboarding_completed", false)
        }

    /** 服务端激活确认位（READY 语义）。 */
    var serverActivated: Boolean
        get() = prefs.getBoolean("onboarding_server_activated", false)
        set(value) = prefs.edit().putBoolean("onboarding_server_activated", value).apply()

    /** v0.6.1（P1-7）：Onboarding 本地提交幂等位（已提交过则重复点击不重复入队）。 */
    var onboardingLocalSubmitted: Boolean
        get() = prefs.getBoolean("onboarding_local_submitted", false)
        set(value) = prefs.edit().putBoolean("onboarding_local_submitted", value).apply()

    /** v0.6.1（P0-3）：服务撤回已提交位（revokeService 幂等重放保护）。 */
    var serviceRevocationSubmitted: Boolean
        get() = prefs.getBoolean("service_revocation_submitted", false)
        set(value) = prefs.edit().putBoolean("service_revocation_submitted", value).apply()

    // ===== 本地模式（v0.7 本地优先架构） =====
    // 产品模式：默认本地使用（无账号/激活码门槛）；用户可选在「支持」页订阅。
    // 本地模式判定 = 尚未订阅（无 access token）：此时画像由端侧引擎生成、
    // 数据仅保存在本机、同步队列与 outbox 静默（不产生任何上行）。
    // 订阅后本值自动变为 false（进入云端同步模式，本地引擎转为离线回退）。

    val localMode: Boolean
        get() = accessToken.isNullOrBlank()

    // ===== 分析消息（v0.7 拉取式推送过渡） =====
    // 已读小结的幂等 id（新 id 才发本地通知；本地模式小结仅展示不通知）。

    var lastSeenMessageId: String?
        get() = if (prefs.contains("last_seen_message_id")) prefs.getString("last_seen_message_id", null) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("last_seen_message_id") else edit.putString("last_seen_message_id", value)
            edit.apply()
        }

    companion object {
        private const val KEY_FEATURE_FLAGS = "feature_flags_json"

        // ===== Onboarding 七态 =====
        const val ONBOARDING_NOT_STARTED = "NOT_STARTED"
        const val ONBOARDING_ACTIVATING = "ACTIVATING"
        const val ONBOARDING_ACTIVATION_FAILED = "ACTIVATION_FAILED"
        const val ONBOARDING_BOUND = "BOUND"
        const val ONBOARDING_CONSENT_PENDING = "CONSENT_PENDING"
        const val ONBOARDING_READY_OFFLINE = "READY_OFFLINE"
        const val ONBOARDING_READY = "READY"
    }
}
