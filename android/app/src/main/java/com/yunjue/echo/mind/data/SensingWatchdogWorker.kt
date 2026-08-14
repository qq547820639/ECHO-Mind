package com.yunjue.echo.mind.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yunjue.echo.mind.EchoMindApplication
import com.yunjue.echo.mind.sensing.PassiveSensingService
import com.yunjue.echo.mind.sensing.SensingWatchdog
import com.yunjue.echo.mind.sensing.hasCoreSensorHardware
import kotlinx.coroutines.flow.first

/**
 * ERA 1 收尾 — Sensing Watchdog（15 分钟周期）：
 * 检测服务假活（系统杀进程 / Doze 心跳停滞）并尝试自愈重启。
 *
 * - consent 关 → 绝不重启（用户选择优先）；
 * - 服务门控（flag + consent + 核心传感器）由 PassiveSensingService 自身执行，
 *   本 Worker 只做「是否值得尝试」的决策（[SensingWatchdog.shouldAttemptRestart] 纯函数）；
 * - 失败无害：服务自身的门控 fail-closed，重启是幂等的。
 */
class SensingWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = runCatching { (applicationContext as EchoMindApplication).container }.getOrNull()
            ?: return Result.success()
        val consentOn = runCatching {
            container.passiveSensingPrefs.passiveSensingEnabled.first()
        }.getOrDefault(false)
        val now = System.currentTimeMillis()
        val should = SensingWatchdog.shouldAttemptRestart(
            consentOn = consentOn,
            serviceActive = container.preferences.sensingActive,
            lastCollectionAt = container.preferences.lastCollectionTimestamp,
            now = now,
        )
        if (should && hasCoreSensorHardware(applicationContext)) {
            PassiveSensingService.start(applicationContext)
        }
        return Result.success()
    }
}
