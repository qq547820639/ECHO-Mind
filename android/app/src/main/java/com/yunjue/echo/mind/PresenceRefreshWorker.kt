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
        PresenceMaintenanceScript(
            refresh = { container.presenceRepository.refresh() },
            // ERA 16 §83：Presence 组装后落盘今日 Canonical Daily State（Journey 长期记忆）
            snapshotToday = { container.journeyRepository.snapshotToday() },
            purgeExpired = { container.memoryRepository.purgeExpired() },
            // ERA 15.5 §76/§77：生命周期维护（decay/expiry + 派生模式记忆）真正运行
            derivePatterns = { container.memoryRepository.derivePatterns() },
        ).run()
        return Result.success()
    }
}

/**
 * ERA 46 §76 复核：维护序列测试锚点（纯 JVM 可测）。
 *
 * 语义：四步各自 fail-closed（任一步异常不阻断后续步骤与 worker 成功返回）——
 * 与 worker 内 runCatching 语义一致；顺序固定（refresh → snapshotToday → purgeExpired → derivePatterns）。
 */
internal class PresenceMaintenanceScript(
    private val refresh: suspend () -> Unit,
    private val snapshotToday: suspend () -> Unit,
    private val purgeExpired: suspend () -> Unit,
    private val derivePatterns: suspend () -> Unit,
) {
    suspend fun run() {
        runCatching { refresh() }
        runCatching { snapshotToday() }
        runCatching { purgeExpired() }
        runCatching { derivePatterns() }
    }
}
