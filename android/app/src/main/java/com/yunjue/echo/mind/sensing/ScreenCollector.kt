package com.yunjue.echo.mind.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * 屏幕状态采集器：监听 ACTION_SCREEN_ON / ACTION_SCREEN_OFF，
 * 记录时间戳 + 状态。事件只写入 [SensingEventHub]（单一数据源，Batch A v0.6.2）；
 * [snapshot] 委托 hub，本采集器不保留本地缓冲。
 *
 * Phase 4.3（跨窗口状态）：维护**进程内屏幕 carry-over 状态**
 * （屏幕在窗口开始前已 ON 时，该窗口必须知道此状态，才能正确计算
 * 跨窗口的 on-duration）。状态保存在本采集器实例（内存），
 * [carryState] 供 FeatureExtractor 在窗口 flush 时读取。
 */
class ScreenCollector(context: Context, private val hub: SensingEventHub) {
    private val appContext = context.applicationContext
    @Volatile
    var running: Boolean = false
        private set

    /** Phase 4.3：当前屏幕是否 ON（carry-over 基础）。 */
    @Volatile
    private var screenOn: Boolean = false

    /** Phase 4.3：本次 ON 开始的时刻（epoch ms；screenOn=false 时无意义）。 */
    @Volatile
    private var screenOnSinceMs: Long = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    screenOnSinceMs = now
                    // 同步到进程级共享 carry（供 scheduler / extractor 跨实例读取）
                    sharedCarry = ScreenStateCarry(now)
                    hub.onScreenEvent(ScreenEvent(now, ScreenState.ON))
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    sharedCarry = null
                    hub.onScreenEvent(ScreenEvent(now, ScreenState.OFF))
                }
                else -> return
            }
        }
    }

    fun start() {
        if (running) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // 系统广播使用 RECEIVER_NOT_EXPORTED（API 33+ 强制要求显式 flag）
        ContextCompat.registerReceiver(
            appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        running = true
    }

    fun stop() {
        if (!running) return
        runCatching { appContext.unregisterReceiver(receiver) }
        running = false
        // 停止时清 carry 状态（重启后重新从广播学习当前状态）
        screenOn = false
        screenOnSinceMs = 0L
        sharedCarry = null
    }

    /** 屏幕事件快照（委托 hub，单一数据源）。 */
    fun snapshot(): List<ScreenEvent> = hub.snapshotScreen()

    /**
     * Phase 4.3：当前屏幕 carry-over 状态（供窗口 flush 计算跨窗口 duration）。
     * 屏幕当前 ON 且存在 ON 起始时刻 → 返回状态；否则 null。
     */
    fun carryState(): ScreenStateCarry? =
        if (screenOn && screenOnSinceMs > 0L) ScreenStateCarry(screenOnSinceMs) else null

    enum class ScreenState { ON, OFF }
    data class ScreenEvent(val timestamp: Long, val state: ScreenState)

    /** Phase 4.3：跨窗口屏幕状态（窗口开始前已 ON 的起始时刻）。 */
    data class ScreenStateCarry(val screenOnSinceMs: Long)

    companion object {
        /** hub 对屏幕事件缓冲的容量上限。 */
        const val MAX_BUFFER_SIZE = 512

        /** Phase 4.3：进程级共享 carry 状态（由 receiver 更新，供 scheduler/extractor 跨实例读取）。 */
        @Volatile
        private var sharedCarry: ScreenStateCarry? = null

        /** 供 FeatureExtractor 读取全局 carry 状态。 */
        internal fun carryState(): ScreenStateCarry? = sharedCarry

        /** 仅测试使用：重置 carry 状态。 */
        internal fun resetCarryForTest() {
            sharedCarry = null
        }
    }
}

