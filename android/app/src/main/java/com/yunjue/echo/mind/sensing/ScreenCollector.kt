package com.yunjue.echo.mind.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 屏幕状态采集器：监听 ACTION_SCREEN_ON / ACTION_SCREEN_OFF，
 * 记录时间戳 + 状态。原始数据仅端侧内存缓冲。
 * 事件同时写入 [SensingEventHub]（T02 共享层）；hub 为空时保持纯本地缓冲。
 */
class ScreenCollector(context: Context, private val hub: SensingEventHub? = null) {
    private val appContext = context.applicationContext
    private val buffer = ConcurrentLinkedDeque<ScreenEvent>()
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
            val event = ScreenEvent(System.currentTimeMillis(), state)
            buffer.offerLast(event)
            while (buffer.size > MAX_BUFFER_SIZE) buffer.pollFirst()
            hub?.onScreenEvent(event)
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

    fun snapshot(): List<ScreenEvent> = buffer.toList()

    enum class ScreenState { ON, OFF }
    data class ScreenEvent(val timestamp: Long, val state: ScreenState)

    companion object {
        const val MAX_BUFFER_SIZE = 512
    }
}
