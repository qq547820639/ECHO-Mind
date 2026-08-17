package com.yunjue.echo.mind.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.localportrait.LocalPortraitDigest
import com.yunjue.echo.mind.model.MessageDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * 分析消息仓库（v0.7：后台「分析 → 推送回应用」的拉取式过渡实现）。
 *
 * - 订阅模式：GET /v1/me/messages（服务端从近 7 天画像确定性生成周小结）；
 * - 本地模式：端侧引擎（[LocalPortraitDigest]）从本地 7 天画像算同款小结（无网络）；
 * - 去重：message.id 确定性幂等，与 [AppPreferences.lastSeenMessageId] 比对，
 *   仅新小结发本地通知（SyncWorker 批末调用，订阅模式）；
 * - 通知尊重系统开关（POST_NOTIFICATIONS 未授权/关闭 → 只记已读不打扰）。
 */
class MessageRepository(
    private val preferences: AppPreferences,
    private val apiClient: ApiClient,
    private val localDataSource: LocalPortraitDataSource
) {
    private val _message = MutableStateFlow<MessageDisplay?>(null)
    val message: StateFlow<MessageDisplay?> = _message

    /** 刷新小结（Today 打开 / SyncWorker 批末调用）。 */
    suspend fun refresh() {
        val zone = ZoneId.systemDefault()
        val today = Instant.now().atZone(zone).toLocalDate()
        if (preferences.localMode) {
            val list = runCatching {
                localDataSource.computeTimeline(preferences.userId, 7, today, zone)
            }.getOrDefault(emptyList())
            _message.value = LocalPortraitDigest.build(list)
            return
        }
        withContext(Dispatchers.IO) {
            val fetch = try {
                apiClient.getMessages()
            } catch (e: Exception) {
                null
            }
            _message.value = if (fetch != null && fetch.first in 200..299 && !fetch.second.isNullOrBlank()) {
                parseMessages(fetch.second!!)
            } else {
                null
            }
        }
    }

    /** 若有新小结（id 未见过）→ 记录已读并尝试发本地通知（订阅模式）。 */
    @SuppressLint("MissingPermission") // 前置 areNotificationsEnabled() 已覆盖权限/开关检查
    fun postNotificationIfNew(context: Context) {
        val msg = _message.value ?: return
        if (preferences.lastSeenMessageId == msg.id) return
        preferences.lastSeenMessageId = msg.id
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(msg.title)
            .setContentText(msg.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.body))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val CHANNEL_ID = "echo_messages"
        private const val CHANNEL_NAME = "分析与小结"
        private const val NOTIFICATION_ID = 1001

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        /** 解析 GET /v1/me/messages 响应；message 为 null/结构非法 → null（fail-closed）。 */
        internal fun parseMessages(body: String): MessageDisplay? = runCatching {
            val o = JSONObject(body)
            val m = o.optJSONObject("message") ?: return null
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: return null
            val title = m.optString("title").takeIf { it.isNotBlank() } ?: return null
            val text = m.optString("body").takeIf { it.isNotBlank() } ?: return null
            MessageDisplay(id = id, title = title, body = text)
        }.getOrNull()
    }
}
