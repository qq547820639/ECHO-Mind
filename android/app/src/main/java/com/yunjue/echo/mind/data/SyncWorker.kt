package com.yunjue.echo.mind.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.EchoMindApplication
import java.util.concurrent.TimeUnit

/** 单事件同步结果动作（纯状态机，供 [classifySyncOutcome] 返回）。 */
internal enum class SyncAction {
    /** 删除 outbox（成功 / 409 幂等）。 */
    DELETE,

    /** 删除 outbox 并记录 410 迁移 telemetry（deprecated 事件类型 terminal）。 */
    DELETE_AND_MIGRATE,

    /** 重试（网络错误 / 5xx / 429，WorkManager 指数退避接管）。 */
    RETRY,

    /** 移到 dead-letter（超限毒丸 / 非 deprecated 410），不再重试。 */
    DEAD_LETTER,

    /** 保留 pending（401/403/412/422 terminal 语义，但不中断整队列）。 */
    KEEP_PENDING
}

/**
 * Outbox 上行 Worker：
 *
 * - **每个事件独立处理**：单事件失败（含毒丸/解密失败）不中断整队列；
 * - 事件类型映射（含 skill_completion → POST /v1/skills/completions）；
 * - 410 Gone：deprecated 事件类型（checkin / journal / questionnaire 前缀 / practice）→ 删除 outbox（terminal）+ 迁移 telemetry；
 * - max attempts（[MAX_ATTEMPTS]）：超限 → dead-letter（本地 SharedPreferences 记录 event_id），不再重试；
 * - 429 Retry-After：读取 Retry-After 头并记录，返回 Result.retry()（WorkManager 指数退避已有）；
 * - 401/403/412/422：保留现有 failure 语义（不删除）但不中断队列，continue 处理下一事件。
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as EchoMindApplication).container
        val dao = container.database.dao()
        val client = ApiClient(tokenProvider = { container.preferences.accessToken })
        val context = applicationContext
        if (container.preferences.accessToken.isNullOrBlank()) return Result.success()

        // v0.6.2（Batch A）：认证暂停态（401/403）→ 直接 success 返回，暂停后台重试；
        // 直到用户重新认证成功清除 lastAuthBlockedAt（见 LocalRepository.verifyOnboardingCode）
        if (container.preferences.authRequired) return Result.success()

        // v0.6.1（P1-6）：批开始记录"尝试同步"时间（不再复用成功时间戳）
        container.preferences.lastSyncAttemptAt = System.currentTimeMillis()
        container.preferences.lastSyncHttpCode = null
        var anyRetry = false
        var anyBlockedPending = false
        var anyAuthBlocked = false
        var anySuccess = false
        var anyFailure = false
        var lastCode: Int? = null
        // v0.6.2（Batch A）：批次级分类计数（不再用 lastCode 覆盖式代表整批）
        var authCount = 0
        var consentCount = 0
        var retryableCount = 0
        var permanentCount = 0
        var unknownTypeCount = 0
        var decryptCount = 0
        // B1：批内可重试事件的 429 Retry-After 聚合值（取最小值）；无则 null。
        var batchRetryAfterSeconds: Int? = null

        for (event in dao.pendingOutbox()) {
            val path = resolvePath(event.eventType)
            if (path == null) {
                // 未知事件类型：dead-letter + telemetry + 删除，避免毒化队列（v0.6.2 Batch A）
                container.preferences.addDeadLetterEvent("${event.eventType}:${event.eventId}:unknown_type")
                container.preferences.recordUnknownTypeTelemetry(event.eventType)
                dao.deleteOutbox(event.eventId)
                unknownTypeCount++
                anyFailure = true
                continue
            }
            // 速率限制：derived_feature 每分钟最多 20 条；超限不阻塞其他事件
            if (event.eventType == "derived_feature" && !acquireDerivedFeatureSlot(context)) {
                anyRetry = true
                continue
            }
            val payload = runCatching { container.cipher.decrypt(event.payloadCiphertext) }
                .getOrElse {
                    // 解密失败 = 毒丸：直接 dead-letter，不阻塞队列
                    container.preferences.addDeadLetterEvent("${event.eventType}:${event.eventId}:decrypt_failure")
                    dao.deleteOutbox(event.eventId)
                    decryptCount++
                    anyFailure = true
                    continue
                }
            val response = runCatching { client.postFull(path, payload) }.getOrElse {
                dao.incrementAttempts(event.eventId)
                retryableCount++
                anyRetry = true
                anyFailure = true
                continue
            }
            lastCode = response.first
            when (classifySyncOutcome(event.eventType, response.first, event.attempts)) {
                SyncAction.DELETE -> {
                    dao.deleteOutbox(event.eventId)
                    anySuccess = true
                    // v0.6.1（P0-2）：escalation 送达 → 回写 serverEscalationId（解析响应 body）
                    if (event.eventType == "escalation") {
                        val serverId = runCatching {
                            org.json.JSONObject(response.second ?: "").optString("id").takeIf { it.isNotBlank() }
                        }.getOrNull()
                        container.repository.markEscalationDelivered(event.eventId, serverId)
                    }
                    // v0.6.1（P0-3 B）：consent 成功 → 服务端已接受 → 清除"等待授权同步"标记
                    if (event.eventType == "consent") {
                        val granted = runCatching {
                            org.json.JSONObject(payload).optBoolean("granted", false)
                        }.getOrDefault(false)
                        if (granted) container.preferences.consentSyncPending = false
                    }
                }
                SyncAction.DELETE_AND_MIGRATE -> {
                    dao.deleteOutbox(event.eventId)
                    container.preferences.recordMigrationTelemetry(event.eventType)
                    anySuccess = true
                }
                SyncAction.RETRY -> {
                    dao.incrementAttempts(event.eventId)
                    response.third?.let {
                        container.preferences.recordRetryAfter(event.eventType, it)
                        batchRetryAfterSeconds = minRetryAfter(batchRetryAfterSeconds, it)
                    }
                    retryableCount++
                    anyRetry = true
                    anyFailure = true
                }
                SyncAction.DEAD_LETTER -> {
                    container.preferences.addDeadLetterEvent("${event.eventType}:${event.eventId}:http_${response.first}")
                    dao.deleteOutbox(event.eventId)
                    permanentCount++
                    anyFailure = true
                    // v0.6.1（P0-2）：escalation dead-letter → 用户可见 FAILED（需联系机构）
                    if (event.eventType == "escalation") {
                        container.repository.markEscalationFailed(event.eventId)
                    }
                }
                SyncAction.KEEP_PENDING -> {
                    dao.incrementAttempts(event.eventId)
                    // v0.6.2（Batch A）：区分 auth（401/403）与 consent（412/422）
                    if (response.first == 401 || response.first == 403) {
                        authCount++
                        anyAuthBlocked = true
                    } else {
                        consentCount++
                        anyBlockedPending = true
                    }
                }
            }
        }

        // v0.6.1（P1-6）：状态语义写回——只有真正成功才更新 lastSuccessfulSyncAt
        val remaining = dao.pendingOutbox().size
        container.preferences.pendingCountSnapshot = remaining
        container.preferences.lastSyncHttpCode = lastCode
        // v0.6.2（Batch A）：批内遇 auth 错误 → 持久化认证暂停态（供 doWork 开头暂停后台重试）
        if (anyAuthBlocked) container.preferences.lastAuthBlockedAt = System.currentTimeMillis()
        when {
            remaining == 0 && !anyFailure -> {
                container.preferences.lastSuccessfulSyncAt = System.currentTimeMillis()
                container.preferences.lastSyncErrorClass = null
            }
            anySuccess -> {
                container.preferences.lastPartialSyncAt = System.currentTimeMillis()
                container.preferences.lastSyncErrorClass = errorClassFor(anyAuthBlocked, anyBlockedPending, lastCode)
            }
            else -> {
                container.preferences.lastSyncErrorClass = errorClassFor(anyAuthBlocked, anyBlockedPending, lastCode)
            }
        }
        // v0.6.1（P1-7）：Onboarding READY 收敛——服务端 ack 依据
        // READY_OFFLINE 且本批全部清空 → 服务端核对同意链后置 READY
        if (remaining == 0 && !anyFailure && container.preferences.onboardingState == AppPreferences.ONBOARDING_READY_OFFLINE) {
            runCatching { container.repository.confirmServerActivation() }
        }
        // B1：批内 429 的 Retry-After 聚合后持久化，供下一次 enqueue 的退避基准使用。
        // 无 429 时（null）清除历史值，恢复默认 30s 指数退避。
        persistRetryAfter(context, batchRetryAfterSeconds)
        return when {
            // v0.6.2（Batch A）：auth 是暂停不是放弃——不再因 auth 返回 Result.retry()，
            // 避免 WorkManager 高频重试；仅网络类/可重试失败与 consent 阻塞保留重试
            anyRetry || anyBlockedPending -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        /** 单事件最大尝试次数（毒丸保护：超限移到 dead-letter，不再重试）。 */
        const val MAX_ATTEMPTS = 10

        /** 无 Retry-After 时的默认指数退避基准（秒）。 */
        const val DEFAULT_BACKOFF_SECONDS = 30

        /** 服务端 Retry-After 允许参与退避的上限（秒），超过则 clamp，避免异常大值造成长时间静默。 */
        const val MAX_RETRY_AFTER_SECONDS = 3600

        /**
         * 批次结果 → 错误类别（分类而非原始 exception；UI 据此显示可读文案）。
         * category: auth / consent / terminal / retryable / offline
         *
         * v0.6.2（Batch A）：批次级聚合——auth 优先于 consent（认证暂停覆盖其他类别）。
         */
        internal fun errorClassFor(anyAuthBlocked: Boolean, anyBlockedPending: Boolean, lastCode: Int?): String = when {
            anyAuthBlocked -> "auth"
            anyBlockedPending || lastCode == 412 || lastCode == 422 -> "consent"
            lastCode == 410 -> "terminal"
            lastCode != null && (lastCode >= 500 || lastCode == 429) -> "retryable"
            else -> "retryable"
        }

        private const val RATE_LIMIT_PREFS = "echo_mind_sync_ratelimit"
        private const val KEY_DF_WINDOW_START = "df_window_start"
        private const val KEY_DF_COUNT = "df_count"
        private const val DF_WINDOW_MS = 60_000L
        private const val DF_MAX_PER_WINDOW = 20
        private const val KEY_RETRY_AFTER_SECONDS = "retry_after_seconds"

        /**
         * 批内可重试事件的 Retry-After 聚合：取最小值（最保守——按服务端要求的最快退避）。
         * 无 Retry-After（incoming/current 均为 null）返回 null。
         */
        internal fun minRetryAfter(current: Int?, incoming: Int?): Int? = when {
            incoming == null -> current
            current == null -> incoming
            else -> minOf(current, incoming)
        }

        /**
         * 由批内 429 的 Retry-After 计算下一次 enqueue 的退避基准（秒）。
         * - null（本批无 429/无 Retry-After）→ [DEFAULT_BACKOFF_SECONDS]（保持现有 30s 指数退避）；
         * - 有效值 clamp 到 [1, MAX_RETRY_AFTER_SECONDS]（0/负数或超大值不参与退避）。
         */
        internal fun backoffDelaySeconds(retryAfterSeconds: Int?): Int =
            retryAfterSeconds?.coerceIn(1, MAX_RETRY_AFTER_SECONDS) ?: DEFAULT_BACKOFF_SECONDS

        /** 持久化最近一批 429 的 Retry-After 聚合值（无则清除）。 */
        internal fun persistRetryAfter(context: Context, retryAfterSeconds: Int?) {
            val prefs = context.getSharedPreferences(RATE_LIMIT_PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            if (retryAfterSeconds == null) edit.remove(KEY_RETRY_AFTER_SECONDS)
            else edit.putInt(KEY_RETRY_AFTER_SECONDS, retryAfterSeconds)
            edit.apply()
        }

        /** 读取最近一批 429 的 Retry-After 聚合值（无则 null）。 */
        internal fun pendingRetryAfterSeconds(context: Context): Int? {
            val prefs = context.getSharedPreferences(RATE_LIMIT_PREFS, Context.MODE_PRIVATE)
            return if (prefs.contains(KEY_RETRY_AFTER_SECONDS)) prefs.getInt(KEY_RETRY_AFTER_SECONDS, 0) else null
        }

        /** 事件类型 → 后端路径（含 T05 skill_completion）。未知类型返回 null（调用方删除）。 */
        internal fun resolvePath(eventType: String): String? = when {
            eventType == "checkin" -> "/v1/checkins"
            eventType == "escalation" -> "/v1/escalations"
            eventType == "consent" -> "/v1/onboarding/consents"
            eventType == "l0" -> "/v1/onboarding/l0"
            eventType == "emergency_contact" -> "/v1/onboarding/emergency-contact"
            eventType == "journal" -> "/v1/journals"
            eventType.startsWith("questionnaire:") -> "/v1/questionnaires/${eventType.substringAfter(':')}/responses"
            eventType == "practice" -> "/v1/practices/completions"
            eventType == "dsr" -> "/v1/data-subject-requests"
            eventType == "derived_feature" -> "/v1/features/ingest"
            eventType == "skill_completion" -> "/v1/skills/completions"
            // Phase 6.6：画像反馈可靠同步（Outbox → POST /v1/me/portraits/feedback）
            eventType == "portrait_feedback" -> "/v1/me/portraits/feedback"
            else -> null
        }

        /**
         * deprecated 事件类型（02b 共享知识 3）：收到 410 时删除 outbox（terminal）并记录迁移 telemetry。
         */
        internal fun isDeprecatedEventType(eventType: String): Boolean =
            eventType == "checkin" || eventType == "journal" ||
                eventType.startsWith("questionnaire:") || eventType == "practice"

        /**
         * 单事件结果分类（纯状态机，便于单测）：
         *
         * - 2xx / 409 → DELETE（成功）
         * - 410 + deprecated 类型 → DELETE_AND_MIGRATE（terminal，旧版数据不再上传）
         * - 410 + 非 deprecated → DEAD_LETTER（服务端已移除，永久）
         * - 401/403 → KEEP_PENDING（登录失效，保留等待重新登录，不 dead-letter）
         * - 412/422 → KEEP_PENDING，超限 → DEAD_LETTER（consent 撤回/坏 payload）
         * - 其余（5xx/429/未知）→ RETRY；超限 → DEAD_LETTER（毒丸保护）
         */
        internal fun classifySyncOutcome(
            eventType: String,
            httpCode: Int,
            attempts: Int,
            maxAttempts: Int = MAX_ATTEMPTS
        ): SyncAction {
            if (httpCode in 200..299 || httpCode == 409) return SyncAction.DELETE
            if (httpCode == 410) {
                return if (isDeprecatedEventType(eventType)) SyncAction.DELETE_AND_MIGRATE
                else SyncAction.DEAD_LETTER
            }
            if (httpCode == 401 || httpCode == 403) return SyncAction.KEEP_PENDING
            if (httpCode == 412 || httpCode == 422) {
                return if (attempts >= maxAttempts) SyncAction.DEAD_LETTER else SyncAction.KEEP_PENDING
            }
            if (attempts >= maxAttempts) return SyncAction.DEAD_LETTER
            return SyncAction.RETRY
        }

        /**
         * derived_feature 上传速率限制：滑动 1 分钟窗口，最多 [DF_MAX_PER_WINDOW] 条。
         * 超限返回 false，调用方应 Result.retry() 延迟发送。
         */
        @Synchronized
        private fun acquireDerivedFeatureSlot(context: Context): Boolean {
            val prefs = context.getSharedPreferences(RATE_LIMIT_PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val windowStart = prefs.getLong(KEY_DF_WINDOW_START, 0L)
            val count = prefs.getInt(KEY_DF_COUNT, 0)
            if (now - windowStart > DF_WINDOW_MS) {
                // 进入新窗口
                prefs.edit()
                    .putLong(KEY_DF_WINDOW_START, now)
                    .putInt(KEY_DF_COUNT, 1)
                    .apply()
                return true
            }
            if (count >= DF_MAX_PER_WINDOW) return false
            prefs.edit().putInt(KEY_DF_COUNT, count + 1).apply()
            return true
        }

        fun enqueue(context: Context) {
            // B1：429 Retry-After 真正参与退避——下一次 enqueue 用批内聚合的最小 Retry-After
            // 作为指数退避基准；无 Retry-After 时保持 30s 默认。
            val backoffSeconds = backoffDelaySeconds(pendingRetryAfterSeconds(context)).toLong()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, backoffSeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("echo-mind-outbox", ExistingWorkPolicy.KEEP, request)
        }
    }
}
