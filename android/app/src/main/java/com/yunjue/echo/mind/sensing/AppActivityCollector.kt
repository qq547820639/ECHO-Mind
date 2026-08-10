package com.yunjue.echo.mind.sensing

import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台 App 活跃采集器：
 *
 * - 通过 UsageStatsManager 轮询当前前台 App（需 PACKAGE_USAGE_STATS 权限，
 *   在系统设置"使用情况访问"中引导用户授权）
 * - 仅记录时间戳 + 包名，不记录 App 内任何内容
 * - 仅在前台 App 切换时记录一条事件，避免重复
 * - 事件只写入 [SensingEventHub]（单一数据源，Batch A v0.6.2）；
 *   [snapshot] 委托 hub，本采集器不保留本地缓冲
 */
class AppActivityCollector(context: Context, private val hub: SensingEventHub) {
    private val appContext = context.applicationContext
    private val usageStatsManager = appContext
        .getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private var lastPackage: String? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null

    @Volatile
    var running: Boolean = false
        private set

    fun start() {
        if (pollJob?.isActive == true) return
        running = true
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        running = false
    }

    /** 单次轮询逻辑（internal 便于单测调用）。 */
    internal fun pollOnce() {
        val usm = usageStatsManager ?: return
        val now = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - INTERVAL_WINDOW_MS, now)
        }.getOrNull() ?: return
        val current = stats.maxByOrNull { it.lastTimeUsed } ?: return
        val pkg = current.packageName
        if (pkg != lastPackage) {
            hub.onAppActivity(AppActivity(now, pkg))
            lastPackage = pkg
        }
    }

    /** App 活跃事件快照（委托 hub，单一数据源）。 */
    fun snapshot(): List<AppActivity> = hub.snapshotAppActivity()

    data class AppActivity(val timestamp: Long, val packageName: String)

    companion object {
        internal const val POLL_INTERVAL_MS = 30_000L
        internal const val INTERVAL_WINDOW_MS = 60_000L
        /** hub 对 App 活跃事件缓冲的容量上限。 */
        const val MAX_BUFFER_SIZE = 256
    }
}
