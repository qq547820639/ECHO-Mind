package com.yunjue.echo.mind.data

import android.annotation.SuppressLint
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
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var institutionCode: String
        get() = prefs.getString("institution_code", "") ?: ""
        set(value) = prefs.edit().putString("institution_code", value).apply()

    var userId: String
        get() = prefs.getString("user_id", "") ?: ""
        set(value) = prefs.edit().putString("user_id", value).apply()

    /**
     * ERA 14 §54 — installation random seed（Identity Genome 唯一随机来源）。
     *
     * 一次性生成并持久化；禁止使用 IMEI / Android ID / 手机号 / 用户名 hash。
     */
    val identitySeed: Long
        get() {
            var v = prefs.getLong("identity_seed", 0L)
            if (v == 0L) {
                v = java.security.SecureRandom().nextLong().let { if (it == 0L) 1L else it }
                prefs.edit().putLong("identity_seed", v).apply()
            }
            return v
        }

    var accessToken: String?
        get() = prefs.getString("access_token_ciphertext", null)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("access_token_ciphertext")
                else putString("access_token_ciphertext", cipher.encrypt(value))
            }.apply()
            // ERA 32 R20：订阅/退订翻转 localMode——联动流供 UI 权限判定观察（与服务门控同构）。
            _localModeFlow.value = value.isNullOrBlank()
        }

    /** ERA 32 R25：轮换式刷新令牌（与 accessToken 同等加密存储；续期时成对替换）。 */
    var refreshToken: String?
        get() = prefs.getString("refresh_token_ciphertext", null)
            ?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("refresh_token_ciphertext")
                else putString("refresh_token_ciphertext", cipher.encrypt(value))
            }.apply()
        }

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

    // ===== ERA 29 §64：内部质量反馈（仅调试构建使用；dogfood 回填源） =====
    // 结构：{"2026-08-15": ["echoReal_yes", "explanationUseful_no", ...]}；
    // 每次点击翻转对应字段，不产生网络请求，不进正式 UI 数据流。

    /** 当日某字段是否已记录（翻转语义由 UI 层控制）。 */
    fun internalFeedbackFor(date: String): Set<String> {
        val json = prefs.getString(KEY_INTERNAL_FEEDBACK, null) ?: return emptySet()
        return runCatching {
            val o = JSONObject(json)
            val arr = o.optJSONArray(date) ?: return emptySet()
            (0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    /** 翻转某日某字段（存在则移除，不存在则加入）。 */
    fun recordInternalFeedback(date: String, field: String) {
        val current = runCatching { JSONObject(prefs.getString(KEY_INTERNAL_FEEDBACK, null)) }
            .getOrElse { JSONObject() }
        val fields = internalFeedbackFor(date).toMutableSet()
        if (field in fields) fields.remove(field) else fields.add(field)
        current.put(date, org.json.JSONArray(fields.toList()))
        prefs.edit().putString(KEY_INTERNAL_FEEDBACK, current.toString()).apply()
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
        prefs.edit().putString(KEY_FEATURE_FLAGS, json).apply()
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

    /**
     * ERA 32 R26：感知循环活性心跳（epoch ms；每个窗口边界刷新，空窗也刷新）。
     * 语义 ≠ lastCollectionTimestamp（数据新鲜度）——watchdog 以心跳判断
     * 「空闲但活着」vs「假活」，避免空窗设备每 15 分钟被误拉起。
     */
    var sensingHeartbeatAt: Long
        get() = prefs.getLong("sensing_heartbeat_at", 0L)
        set(value) = prefs.edit().putLong("sensing_heartbeat_at", value).apply()

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
        // ERA 32 R24：绝不能连 DB 秘密一起清——包装秘密/迁移标记与其它应用状态
        // 共用同一 prefs 文件，一次 clear() 会让下次启动重新供给新口令，
        // 既有加密库永久不可打开（数据孤儿化）。先快照再清除后还原。
        val wrappedSecret = prefs.getString(
            com.yunjue.echo.mind.security.PreferencesDatabaseSecretStorage.KEY_DB_SECRET, null
        )
        val secretMigrated = prefs.getBoolean(
            com.yunjue.echo.mind.security.PreferencesDatabaseSecretStorage.KEY_DB_SECRET_MIGRATED, false
        )
        prefs.edit().clear().apply()
        if (wrappedSecret != null) {
            prefs.edit()
                .putString(
                    com.yunjue.echo.mind.security.PreferencesDatabaseSecretStorage.KEY_DB_SECRET,
                    wrappedSecret
                )
                .putBoolean(
                    com.yunjue.echo.mind.security.PreferencesDatabaseSecretStorage.KEY_DB_SECRET_MIGRATED,
                    secretMigrated
                )
                .apply()
        }
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

    /** ERA 32 R20：本地模式流（accessToken 写入时联动更新；Journey 权限判定与服务门控同构消费）。 */
    private val _localModeFlow = MutableStateFlow(localMode)
    val localModeFlow: Flow<Boolean> = _localModeFlow

    // ===== 分析消息（v0.7 拉取式推送过渡） =====
    // 已读小结的幂等 id（新 id 才发本地通知；本地模式小结仅展示不通知）。

    var lastSeenMessageId: String?
        get() = if (prefs.contains("last_seen_message_id")) prefs.getString("last_seen_message_id", null) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("last_seen_message_id") else edit.putString("last_seen_message_id", value)
            edit.apply()
        }

    // ===== 订阅生命周期（v0.7 订阅制） =====
    // 订阅到期时间（epoch ms，null = 机构旧用户/无显式订阅=永不过期）与订阅档位。
    // 由 verify-code 响应 / GET /v1/me/subscription 刷新。

    var subscriptionExpiresAt: Long?
        get() = if (prefs.contains("subscription_expires_at")) prefs.getLong("subscription_expires_at", 0L) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("subscription_expires_at") else edit.putLong("subscription_expires_at", value)
            edit.apply()
        }

    var subscriptionPlan: String?
        get() = if (prefs.contains("subscription_plan")) prefs.getString("subscription_plan", null) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove("subscription_plan") else edit.putString("subscription_plan", value)
            edit.apply()
        }

    /** 订阅是否显式到期（null = 永不过期；到期时刻 <= now → true）。 */
    val subscriptionExpired: Boolean
        get() = subscriptionExpiresAt?.let { it <= System.currentTimeMillis() } ?: false

    // ===== v0.7.4 UX =====
    // 基线解锁仪式（一次性显示）与每晚小结提醒开关。

    var baselineUnlockedShown: Boolean
        get() = prefs.getBoolean("baseline_unlocked_shown", false)
        set(value) = prefs.edit().putBoolean("baseline_unlocked_shown", value).apply()

    var eveningReminderEnabled: Boolean
        get() = prefs.getBoolean("evening_reminder_enabled", true)
        set(value) = prefs.edit().putBoolean("evening_reminder_enabled", value).apply()

    // ===== ERA 1：ECHO Awakening 锚点 =====
    // 苏醒瞬间写入（epoch ms；0 = 尚未苏醒）。
    // Day-0 SEED 画报的「已观察 N 分钟」与成长成熟度的存在性表达依赖此锚点。
    // 老用户回填：取本地最早特征窗口日期（见 docs/architecture/MIGRATION_ARCHITECTURE.md §4）。

    var awakenedAtEpochMs: Long
        get() = prefs.getLong("awakened_at_epoch_ms", 0L)
        set(value) = prefs.edit().putLong("awakened_at_epoch_ms", value).apply()

    // ===== ERA 2/3：ECHO Presence 快照与设置 =====

    /**
     * 最近一版 EchoPresenceState 快照（[com.yunjue.echo.mind.presence.EchoPresenceCodec] 编码）。
     * Wallpaper / Dream 进程只读此快照（不初始化业务容器）；PresenceRepository 每次刷新落盘。
     * ERA 31 R16：apply → commit——这是进程死亡恢复锚点（crash-free Presence runtime 指标）：
     * 硬崩溃时 apply 的排队写入可能丢失 → 重启后 ECHO 退化为中性占位长达 15 分钟。
     * 15 分钟一次的小字符串同步写，主线程成本可忽略。
     */
    @set:SuppressLint("ApplySharedPref")
    var echoPresenceSnapshot: String?
        get() = prefs.getString(KEY_ECHO_PRESENCE_SNAPSHOT, null)
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove(KEY_ECHO_PRESENCE_SNAPSHOT) else edit.putString(KEY_ECHO_PRESENCE_SNAPSHOT, value)
            edit.commit()
        }

    /** 动态程度：QUIET / DEFAULT / LIVELY（Me → Presence 设置）。 */
    var presenceMotionLevel: String        get() = prefs.getString("presence_motion_level", "DEFAULT") ?: "DEFAULT"
        set(value) = prefs.edit().putString("presence_motion_level", value).apply()

    /** 增强夜间模式（额外降暗减速；昼夜亮度曲线本身已自动调暗）。默认关。 */
    var presenceNightMode: Boolean
        get() = prefs.getBoolean("presence_night_mode", false)
        set(value) = prefs.edit().putBoolean("presence_night_mode", value).apply()

    /** 减少动画（无障碍）：视觉参数 flowSpeed 归零。默认关。 */
    var presenceReduceMotion: Boolean
        get() = prefs.getBoolean("presence_reduce_motion", false)
        set(value) = prefs.edit().putBoolean("presence_reduce_motion", value).apply()

    /** 应用内建议（InterventionPolicy L2 opt-in）：打开时基于高置信状态给温和建议。默认开。 */
    var presenceSuggestionsEnabled: Boolean
        get() = prefs.getBoolean("presence_suggestions_enabled", true)
        set(value) = prefs.edit().putBoolean("presence_suggestions_enabled", value).apply()

    /** v2 §35：初次 AI 提示是否已关闭（「以后再说」；非阻塞、只出现一次）。 */
    var aiPromptDismissed: Boolean
        get() = prefs.getBoolean("ai_prompt_dismissed", false)
        set(value) = prefs.edit().putBoolean("ai_prompt_dismissed", value).apply()

    /** ERA 31 R34：一次性壁纸引导已关闭（打开过或「以后再说」；PART 57 Wallpaper adoption）。 */
    var wallpaperPromptDismissed: Boolean
        get() = prefs.getBoolean("wallpaper_prompt_dismissed", false)
        set(value) = prefs.edit().putBoolean("wallpaper_prompt_dismissed", value).apply()

    companion object {
        /** 应用状态 SharedPreferences 文件名（Wallpaper/Dream 进程直读快照用）。
         *  归属 security 模块常量（DB 秘密同文件；security 不得反向依赖 app root）。 */
        const val PREFS_FILE = com.yunjue.echo.mind.security.PreferencesDatabaseSecretStorage.PREFS_FILE

        private const val KEY_FEATURE_FLAGS = "feature_flags_json"
        private const val KEY_INTERNAL_FEEDBACK = "internal_quality_feedback_json"

        /** Presence 快照键（Wallpaper/Dream 进程经原始 SharedPreferences 直读）。 */
        const val KEY_ECHO_PRESENCE_SNAPSHOT = "echo_presence_snapshot"

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
