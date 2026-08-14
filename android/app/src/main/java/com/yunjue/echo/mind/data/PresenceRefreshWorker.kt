package com.yunjue.echo.mind.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yunjue.echo.mind.EchoMindApplication

/**
 * ERA 3 收尾（WORK-ERA3-1）— Presence 后台刷新（15 分钟周期）：
 * 即使用户不打开 App，Wallpaper / Dream 消费的 EchoPresenceState 快照也保持新鲜。
 *
 * 同时执行记忆生命周期维护（自动过期清理——不是什么都值得记，也不是什么都永远在）。
 * 本地只读 + 纯端侧计算，不产生任何上行。
 */
class PresenceRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { (applicationContext as EchoMindApplication).container }.getOrNull()
            ?: return Result.success()
        if (!container.preferences.onboardingCompleted) return Result.success()
        runCatching { container.presenceRepository.refresh() }
        runCatching { container.memoryRepository.purgeExpired() }
        return Result.success()
    }
}
