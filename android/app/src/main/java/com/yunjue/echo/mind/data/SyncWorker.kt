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
 * - 410 Gone：deprecated 事件类型（checkin/journal/questionnaire:*/practice）→ 删除 outbox（terminal）+ 迁移 telemetry；
 * - max attempts（[MAX_ATTEMPTS]）：超限 → dead-letter（本地 SharedPreferences 记录 event_id），不再重试；
 * - 429 Retry-After：读取 Retry-After 头并记录，返回 Result.retry()（WorkManager 指数退避已有）；
 * - 401/403/412/422：保留现有 failure 语义（不删除）但不中断队列，continue 处理下一事件。
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as EchoMindApplication).container
        val dao = container.database.dao()
        val client = ApiClient { container.preferences.accessToken }
        val context = applicationContext
        if (container.preferences.accessToken.isNullOrBlank()) return Result.success()

        // 每批开始时清空上次状态，结束前写回本次批次结果（UI SyncState 依据）
        container.preferences.lastSyncHttpCode = null
        var anyRetry = false
        var anyBlockedPending = false
        var lastCode: Int? = null

        for (event in dao.pendingOutbox()) {
            val path = resolvePath(event.eventType)
            if (path == null) {
                // 未知事件类型：删除，避免毒化队列
                dao.deleteOutbox(event.eventId)
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
                    continue
                }
            val response = runCatching { client.post(path, payload) }.getOrElse {
                dao.incrementAttempts(event.eventId)
                anyRetry = true
                continue
            }
            lastCode = response.code
            when (classifySyncOutcome(event.eventType, response.code, event.attempts)) {
                SyncAction.DELETE -> dao.deleteOutbox(event.eventId)
                SyncAction.DELETE_AND_MIGRATE -> {
                    dao.deleteOutbox(event.eventId)
                    container.preferences.recordMigrationTelemetry(event.eventType)
                }
                SyncAction.RETRY -> {
                    dao.incrementAttempts(event.eventId)
                    response.retryAfterSeconds?.let { container.preferences.recordRetryAfter(event.eventType, it) }
                    anyRetry = true
                }
                SyncAction.DEAD_LETTER -> {
                    container.preferences.addDeadLetterEvent("${event.eventType}:${event.eventId}:http_${response.code}")
                    dao.deleteOutbox(event.eventId)
                }
                SyncAction.KEEP_PENDING -> {
                    dao.incrementAttempts(event.eventId)
                    anyBlockedPending = true
                }
            }
        }

        container.preferences.lastSyncHttpCode = lastCode
        container.preferences.lastSyncTimestamp = System.currentTimeMillis()
        return when {
            anyRetry || anyBlockedPending -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        /** 单事件最大尝试次数（毒丸保护：超限移到 dead-letter，不再重试）。 */
        const val MAX_ATTEMPTS = 10

        private const val RATE_LIMIT_PREFS = "echo_mind_sync_ratelimit"
        private const val KEY_DF_WINDOW_START = "df_window_start"
        private const val KEY_DF_COUNT = "df_count"
        private const val DF_WINDOW_MS = 60_000L
        private const val DF_MAX_PER_WINDOW = 20

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
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("echo-mind-outbox", ExistingWorkPolicy.KEEP, request)
        }
    }
}
