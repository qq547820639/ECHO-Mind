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
            val payload = runCatching { container.cipher.decrypt(event.payloadCiphertext) }
                .getOrElse { return Result.failure() }
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
                else -> { dao.deleteOutbox(event.eventId); continue }
            }
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
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("echo-mind-outbox", ExistingWorkPolicy.KEEP, request)
        }
    }
}
