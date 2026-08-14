package com.yunjue.echo.mind.sensing

/**
 * ERA 1 收尾 — Sensing Watchdog 决策（纯函数，JVM 可测）。
 *
 * 六态闭环的最后一环：服务假活检测与自愈决策。
 * 「ECHO 暂时休息了（SYSTEM_PAUSED）」必须能被自动恢复，而不是永远靠用户手动重开。
 */
object SensingWatchdog {

    /** 运行时心跳过期阈值：60 分钟无新采集 = 视为服务假活/停滞。 */
    const val STALE_MS = 60L * 60L * 1000L

    /**
     * 是否应尝试重启感知服务：
     * - 用户 consent 开启（用户选择必须尊重；consent 关 → 绝不重启）；
     * - 服务未运行（系统杀进程），或服务在运行但采集心跳过期（Doze/后台限制假活）。
     */
    fun shouldAttemptRestart(
        consentOn: Boolean,
        serviceActive: Boolean,
        lastCollectionAt: Long,
        now: Long,
        staleMs: Long = STALE_MS,
    ): Boolean {
        if (!consentOn) return false
        if (!serviceActive) return true
        return lastCollectionAt > 0L && now - lastCollectionAt > staleMs
    }
}
