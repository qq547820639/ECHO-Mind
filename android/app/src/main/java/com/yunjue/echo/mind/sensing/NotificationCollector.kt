package com.yunjue.echo.mind.sensing

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 通知监听采集器：继承 NotificationListenerService。
 *
 * - 仅记录时间戳 + 包名 + category，绝不记录通知标题/正文/图标等内容
 * - 需用户在系统设置中授权"通知使用权"
 * - 由系统独立绑定，PassiveSensingService 不直接管理其生命周期
 */
class NotificationCollector : NotificationListenerService() {
    private val buffer = ConcurrentLinkedDeque<NotificationMeta>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val category = sbn.notification?.category ?: Notification.CATEGORY_UNKNOWN
        buffer.offerLast(NotificationMeta(System.currentTimeMillis(), pkg, category))
        while (buffer.size > MAX_BUFFER_SIZE) buffer.pollFirst()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    fun snapshot(): List<NotificationMeta> = buffer.toList()

    data class NotificationMeta(
        val timestamp: Long,
        val packageName: String,
        val category: String
    )

    companion object {
        const val MAX_BUFFER_SIZE = 512
    }
}
