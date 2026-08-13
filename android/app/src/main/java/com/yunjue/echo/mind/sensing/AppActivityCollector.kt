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
 *
 * Phase 4.3（跨窗口状态）：维护**进程内 foreground_since**——
 * 当前前台 package 的开始时刻（[foregroundSinceMs]），供 FeatureExtractor
 * 跨窗口计算真实 top-app duration（不再把 `windowEnd - lastEvent.timestamp`
 * 错误称为 top-app duration）。
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

    /** Phase 4.3：当前前台 package（进程内存，仅用于去重与 duration；不上云）。 */
    @Volatile
    private var foregroundPackage: String? = null

    /** Phase 4.3：当前前台 package 的开始时刻（epoch ms）。 */
    @Volatile
    private var foregroundSinceMs: Long = 0L

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
        foregroundPackage = null
        foregroundSinceMs = 0L
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
            // Phase 4.3：维护 foreground_since（切换时刻 = 新 package 开始前台），
            // 并同步到进程级共享（供 scheduler/extractor 跨实例读取）。
            foregroundPackage = pkg
            foregroundSinceMs = now
            sharedForeground = AppForegroundState(pkg, now)
            hub.onAppActivity(AppActivity(now, pkg))
            lastPackage = pkg
        }
    }

    /** App 活跃事件快照（委托 hub，单一数据源）。 */
    fun snapshot(): List<AppActivity> = hub.snapshotAppActivity()

    /**
     * Phase 4.3：当前前台状态（供窗口 flush 计算跨窗口 top-app duration）。
     * 进程重启后无状态（null），由 pollOnce 重新学习。
     */
    fun foregroundState(): AppForegroundState? =
        foregroundPackage?.let { AppForegroundState(it, foregroundSinceMs) }

    data class AppActivity(val timestamp: Long, val packageName: String)

    /** Phase 4.3：跨窗口前台状态（当前 package + 开始时刻）。 */
    data class AppForegroundState(val packageName: String, val foregroundSinceMs: Long)

    companion object {
        internal const val POLL_INTERVAL_MS = 30_000L
        internal const val INTERVAL_WINDOW_MS = 60_000L
        /** hub 对 App 活跃事件缓冲的容量上限。 */
        const val MAX_BUFFER_SIZE = 256

        /** Phase 4.3：进程级共享 foreground 状态（由 collector 更新，供 extractor 跨实例读取）。 */
        @Volatile
        private var sharedForeground: AppForegroundState? = null

        /** 供 FeatureExtractor 读取全局 foreground 状态。 */
        internal fun foregroundState(): AppForegroundState? = sharedForeground

        /** 仅测试使用：重置 foreground 状态。 */
        internal fun resetForegroundForTest() {
            sharedForeground = null
        }
    }
}
