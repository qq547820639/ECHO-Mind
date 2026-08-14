package com.yunjue.echo.mind.sensing

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知监听采集器：继承 NotificationListenerService。
 *
 * - 仅记录时间戳 + 包名 + category，绝不记录通知标题/正文/图标等内容
 * - 需用户在系统设置中授权"通知使用权"
 * - 由系统独立绑定，PassiveSensingService 不直接管理其生命周期
 * - 事件写入 [SensingEventHub] 进程内共享层（T02），供 FeatureExtractor 按窗口消费；
 *   snapshot() 亦委托 hub，保证单一数据源
 */
class NotificationCollector : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val category = sbn.notification?.category ?: CATEGORY_UNKNOWN
        SensingEventHub.getInstance()
            .onNotificationPosted(NotificationMeta(System.currentTimeMillis(), pkg, category))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    fun snapshot(): List<NotificationMeta> = SensingEventHub.getInstance().snapshotNotifications()

    data class NotificationMeta(
        val timestamp: Long,
        val packageName: String,
        val category: String
    )

    companion object {
        const val MAX_BUFFER_SIZE = 512

        /**
         * Android 通知 category 无 CATEGORY_UNKNOWN 常量；category 为 String 契约，
         * 缺省时以本地常量 "unknown" 兜底（后端按字符串消费）。
         */
        const val CATEGORY_UNKNOWN = "unknown"
    }
}
