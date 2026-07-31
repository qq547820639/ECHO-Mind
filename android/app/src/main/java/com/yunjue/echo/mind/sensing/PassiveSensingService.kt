package com.yunjue.echo.mind.sensing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.EchoMindApplication
import com.yunjue.echo.mind.PassiveSensingPrefs
import com.yunjue.echo.mind.data.SyncWorker
import com.yunjue.echo.mind.security.FieldCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 被动采集前台服务：
 * - 拉起各 Collector（传感器 / 屏幕 / 前台 App 活跃 / 麦克风）
 * - 通过 startForeground 持续运行，避免被系统回收
 * - NotificationListenerService（NotificationCollector）由系统独立绑定，此处不直接管理其生命周期
 * - 原始数据仅在端侧内存缓冲，不落盘不上云；麦克风原始音频即时处理后丢弃
 * - P5 灰度回滚：启动前检查 passive_sensing_enabled flag，关闭则不启动（直接 stopSelf）
 *
 * 通知构建逻辑（buildNotification）暴露为 internal，便于单测验证。
 */
class PassiveSensingService : Service() {
    private var sensorCollector: SensorCollector? = null
    private var screenCollector: ScreenCollector? = null
    private var appActivityCollector: AppActivityCollector? = null
    private var micCollector: MicCollector? = null
    private var started = false

    /** 权限撤回回调协程 scope：SupervisorJob 避免单次回调异常影响后续。 */
    private val revokeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = PassiveSensingPrefs(this)
        sensorCollector = SensorCollector(this)
        screenCollector = ScreenCollector(this)
        appActivityCollector = AppActivityCollector(this)
        // 麦克风采集器：注入权限撤回回调，撤回时写 voice_features consent（granted=false）
        // 并触发 SyncWorker 上传，闭环 P1.2 + P1.3
        val container = (application as? EchoMindApplication)?.container
        micCollector = MicCollector(this, prefs) {
            container?.let { c ->
                revokeScope.launch {
                    runCatching { c.repository.saveVoiceFeaturesConsent(false) }
                    runCatching { SyncWorker.enqueue(this@PassiveSensingService) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSensing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // P5 灰度回滚：passive_sensing_enabled=false 时不启动采集，直接停止服务。
                // flag 缓存由 LocalRepository.fetchFeatureFlags() 拉取后写入 AppPreferences。
                if (!isPassiveSensingEnabled()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startSensing()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSensing()
        micCollector?.release()
        super.onDestroy()
    }

    /**
     * 读取本租户 feature flag 缓存中的 passive_sensing_enabled。
     *
     * 同步读取 SharedPreferences 缓存（由 fetchFeatureFlags 异步写入）；
     * 无缓存时默认 true（保守启用，避免网络问题导致采集不可用）。
     */
    private fun isPassiveSensingEnabled(): Boolean {
        val appPrefs = AppPreferences(this, FieldCipher(this))
        return appPrefs.getFeatureFlagsSnapshot()["passive_sensing_enabled"] ?: true
    }

    private fun startSensing() {
        if (started) return
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        sensorCollector?.start()
        screenCollector?.start()
        appActivityCollector?.start()
        // 麦克风为可选模块：start() 内部检查 micEnabled + RECORD_AUDIO 权限，
        // 不满足时直接返回，不影响其他采集器
        micCollector?.start()
        started = true
    }

    private fun stopSensing() {
        if (!started) return
        sensorCollector?.stop()
        screenCollector?.stop()
        appActivityCollector?.stop()
        micCollector?.stop()
        started = false
    }

    /**
     * 构建被动采集持续通知。internal 便于单测验证渠道与内容。
     */
    internal fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "passive_sensing"
        private const val CHANNEL_NAME = "被动采集"
        internal const val NOTIFICATION_TITLE = "ECHO Mind"
        internal const val NOTIFICATION_TEXT = "被动采集中（端侧处理）"
        private const val NOTIFICATION_ID = 0xA001

        const val ACTION_START = "com.yunjue.echo.mind.action.START_SENSING"
        const val ACTION_STOP = "com.yunjue.echo.mind.action.STOP_SENSING"

        /** 启动被动采集前台服务。 */
        fun start(context: Context) {
            val intent = Intent(context, PassiveSensingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止被动采集前台服务。 */
        fun stop(context: Context) {
            val intent = Intent(context, PassiveSensingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
