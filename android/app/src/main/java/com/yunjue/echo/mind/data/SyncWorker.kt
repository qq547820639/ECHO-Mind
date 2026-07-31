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

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as EchoMindApplication).container
        val dao = container.database.dao()
        val client = ApiClient { container.preferences.accessToken }
        if (container.preferences.accessToken.isNullOrBlank()) return Result.success()
        for (event in dao.pendingOutbox()) {
            val path = when {
                event.eventType == "checkin" -> "/v1/checkins"
                event.eventType == "escalation" -> "/v1/escalations"
                event.eventType == "consent" -> "/v1/onboarding/consents"
                event.eventType == "l0" -> "/v1/onboarding/l0"
                event.eventType == "emergency_contact" -> "/v1/onboarding/emergency-contact"
                event.eventType == "journal" -> "/v1/journals"
                event.eventType.startsWith("questionnaire:") -> "/v1/questionnaires/${event.eventType.substringAfter(':')}/responses"
                event.eventType == "practice" -> "/v1/practices/completions"
                event.eventType == "dsr" -> "/v1/data-subject-requests"
                event.eventType == "derived_feature" -> "/v1/features/ingest"
                else -> { dao.deleteOutbox(event.eventId); continue }
            }
            // 速率限制：derived_feature 每分钟最多 20 条
            if (event.eventType == "derived_feature" && !acquireDerivedFeatureSlot(applicationContext)) {
                return Result.retry()
            }
            val payload = runCatching { container.cipher.decrypt(event.payloadCiphertext) }
                .getOrElse { return Result.failure() }
            val code = runCatching { client.post(path, payload) }.getOrElse {
                dao.incrementAttempts(event.eventId)
                return Result.retry()
            }
            when {
                code in 200..299 || code == 409 -> dao.deleteOutbox(event.eventId)
                code == 401 || code == 403 || code == 412 || code == 422 -> {
                    dao.incrementAttempts(event.eventId)
                    return Result.failure()
                }
                code >= 500 -> {
                    dao.incrementAttempts(event.eventId)
                    return Result.retry()
                }
                else -> {
                    dao.incrementAttempts(event.eventId)
                    return Result.failure()
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val RATE_LIMIT_PREFS = "echo_mind_sync_ratelimit"
        private const val KEY_DF_WINDOW_START = "df_window_start"
        private const val KEY_DF_COUNT = "df_count"
        private const val DF_WINDOW_MS = 60_000L
        private const val DF_MAX_PER_WINDOW = 20

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
