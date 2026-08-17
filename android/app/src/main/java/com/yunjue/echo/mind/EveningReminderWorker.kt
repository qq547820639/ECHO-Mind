package com.yunjue.echo.mind.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yunjue.echo.mind.EchoMindApplication
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 每晚小结提醒（v0.7.4 UX，冷启动留存）：
 * 每天 21:00 一次（自续期一次性任务），本地/订阅模式都发——
 * 「今天的数据已记录完毕。打开看看今天的你。」不打扰、不评判，每天最多一条。
 *
 * - 开关（AppPreferences.eveningReminderEnabled）关闭 → 只续期不通知；
 * - 感知关闭 → 不通知（不打扰未授权的用户）；
 * - 无网络约束：本地模式离线可用（WorkManager 延迟任务不依赖网络）。
 */
class EveningReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @SuppressLint("MissingPermission") // 前置 areNotificationsEnabled() 已覆盖 POST_NOTIFICATIONS 权限/开关检查
    override suspend fun doWork(): Result {
        // 先排下一次（自续期），无论本次是否发通知
        scheduleNext(applicationContext)
        val container = runCatching { (applicationContext as EchoMindApplication).container }.getOrNull()
            ?: return Result.success()
        if (!container.preferences.eveningReminderEnabled) return Result.success()
        val sensingEnabled = runCatching {
            container.preferences.passiveSensingPrefs.passiveSensingEnabled.first()
        }.getOrDefault(false)
        if (!sensingEnabled) return Result.success()

        val nm = NotificationManagerCompat.from(applicationContext)
        if (!nm.areNotificationsEnabled()) return Result.success()
        ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("ECHO Mind")
            .setContentText("今天的数据已记录完毕。打开看看今天的你。")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "echo-evening-reminder"
        const val CHANNEL_ID = "echo_evening"
        private const val NOTIFICATION_ID = 2001

        /** 每晚提醒时刻（24h 制）。 */
        const val REMINDER_HOUR = 21

        /** 排下一次提醒（REPLACE 幂等；进程重启后 Application.onCreate 会重排）。 */
        fun scheduleNext(context: Context, now: LocalDateTime = LocalDateTime.now()) {
            val delayMs = delayToNextEveningMs(now)
            val request = OneTimeWorkRequestBuilder<EveningReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** 关闭提醒（开关关闭时取消已排任务）。 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        /** 距离下一个 21:00 的毫秒数（纯函数可单测；已过 21:00 → 次日）。 */
        internal fun delayToNextEveningMs(now: LocalDateTime, hour: Int = REMINDER_HOUR): Long {
            val todayEvening = now.toLocalDate().atTime(hour, 0)
            val target = if (now >= todayEvening) todayEvening.plusDays(1) else todayEvening
            return Duration.between(now, target).toMillis()
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "每晚小结", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
