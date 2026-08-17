package com.yunjue.echo.mind

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 分析消息周期检查（v0.7 拉取式推送过渡的定时化）：
 * 每 24 小时（订阅模式）拉取周小结，新小结发本地通知。
 *
 * - 本地模式（未订阅）直接 success（端侧小结仅 Today 页展示，不通知）；
 * - 网络失败静默（下一周期重试）；
 * - 与 SyncWorker 批末拉取互补：即使 outbox 长期为空，周期检查仍让
 *   「后台分析 → 消息回推」保持活跃。
 */
class MessageCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { (applicationContext as EchoMindApplication).container }.getOrNull()
            ?: return Result.retry()
        if (container.preferences.localMode) return Result.success()
        runCatching {
            container.messageRepository.refresh()
            container.messageRepository.postNotificationIfNew(applicationContext)
        }
        return Result.success()
    }
}
