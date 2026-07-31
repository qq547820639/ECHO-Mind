package com.yunjue.echo.mind.sensing

import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 前台 App 活跃采集器：
 *
 * - 通过 UsageStatsManager 轮询当前前台 App（需 PACKAGE_USAGE_STATS 权限，
 *   在系统设置"使用情况访问"中引导用户授权）
 * - 仅记录时间戳 + 包名，不记录 App 内任何内容
 * - 仅在前台 App 切换时记录一条事件，避免重复
 */
class AppActivityCollector(context: Context) {
    private val appContext = context.applicationContext
    private val usageStatsManager = appContext
        .getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val buffer = ConcurrentLinkedDeque<AppActivity>()
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
            buffer.offerLast(AppActivity(now, pkg))
            while (buffer.size > MAX_BUFFER_SIZE) buffer.pollFirst()
            lastPackage = pkg
        }
    }

    fun snapshot(): List<AppActivity> = buffer.toList()

    data class AppActivity(val timestamp: Long, val packageName: String)

    companion object {
        internal const val POLL_INTERVAL_MS = 30_000L
        internal const val INTERVAL_WINDOW_MS = 60_000L
        const val MAX_BUFFER_SIZE = 256
    }
}
