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
 */
class ScreenCollector(context: Context, private val hub: SensingEventHub) {
    private val appContext = context.applicationContext
    @Volatile
    var running: Boolean = false
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val state = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> ScreenState.ON
                Intent.ACTION_SCREEN_OFF -> ScreenState.OFF
                else -> return
            }
            hub.onScreenEvent(ScreenEvent(System.currentTimeMillis(), state))
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
    }

    /** 屏幕事件快照（委托 hub，单一数据源）。 */
    fun snapshot(): List<ScreenEvent> = hub.snapshotScreen()

    enum class ScreenState { ON, OFF }
    data class ScreenEvent(val timestamp: Long, val state: ScreenState)

    companion object {
        /** hub 对屏幕事件缓冲的容量上限。 */
        const val MAX_BUFFER_SIZE = 512
    }
}
