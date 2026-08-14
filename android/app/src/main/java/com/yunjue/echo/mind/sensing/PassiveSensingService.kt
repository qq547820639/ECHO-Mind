package com.yunjue.echo.mind.sensing

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yunjue.echo.mind.AppPreferences
import com.yunjue.echo.mind.EchoMindApplication
import com.yunjue.echo.mind.PassiveSensingPrefs
import com.yunjue.echo.mind.enqueueSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 被动采集前台服务：
 * - 拉起各 Collector（传感器 / 屏幕 / 前台 App 活跃 / 麦克风）+ [SensingWindowScheduler]（5 分钟窗口）
 * - 通过 startForeground 持续运行，避免被系统回收
 * - NotificationListenerService（NotificationCollector）由系统独立绑定，事件经 [SensingEventHub] 共享层到达
 * - 原始数据仅在端侧内存缓冲，不落盘不上云；麦克风原始音频即时处理后丢弃
 * - **三重门控**（02b 共享知识 1）：用户 consent granted + 租户 flag enabled + 必要权限符合，任一不满足不启动；
 *   flag 无缓存默认 false（fail-closed）
 * - 停止路径：停止 scheduler + 停止各 Collector + 清空 hub 缓冲
 *
 * 通知构建逻辑（buildNotification）暴露为 internal，便于单测验证。
 */
class PassiveSensingService : Service() {
    private var sensorCollector: SensorCollector? = null
    private var screenCollector: ScreenCollector? = null
    private var appActivityCollector: AppActivityCollector? = null
    private var micCollector: MicCollector? = null
    private var scheduler: SensingWindowScheduler? = null
    private var started = false

    private val hub: SensingEventHub = SensingEventHub.getInstance()

    /** 调度协程 scope：SupervisorJob 避免单次回调异常影响后续。 */
    private val sensingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 权限撤回回调协程 scope：SupervisorJob 避免单次回调异常影响后续。 */
    private val revokeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = runCatching { (application as? EchoMindApplication)?.container }.getOrNull()
        // 复用 AppContainer 的 passiveSensingPrefs（与 AppPreferences 共享同一 DataStore 实例，
        // 避免重复新建）；容器不可用（如 Robolectric 单测）时回退新建，保持 fail-closed 不破坏既有行为。
        val prefs = container?.passiveSensingPrefs ?: PassiveSensingPrefs(this)
        sensorCollector = SensorCollector(this, hub)
        screenCollector = ScreenCollector(this, hub)
        appActivityCollector = AppActivityCollector(this, hub)
        // 麦克风采集器：注入权限撤回回调，撤回时写 voice_features consent（granted=false）
        // 并触发 SyncWorker 上传，闭环 P1.2 + P1.3
        micCollector = MicCollector(this, prefs) {
            container?.let { c ->
                revokeScope.launch {
                    try {
                        c.consentRepository.saveVoiceFeaturesConsent(false)
                    } catch (_: Exception) {
                        // voice_features 撤回证据落库失败不阻塞（outbox 尽力；后续可重试）
                    }
                    runCatching { enqueueSync(this@PassiveSensingService) }
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
                // 已运行时不重复启动（幂等）
                if (started) return START_STICKY
                // 三重门控含 DataStore 异步读（consent）：不能在主线程 runBlocking（ANR/死锁风险）。
                // 门控在后台协程执行，结果回主线程处理；门控不通过则 stopSelf（fail-closed），
                // startSensing 仍回主线程执行（startForeground/传感器注册）。
                val mainHandler = Handler(Looper.getMainLooper())
                sensingScope.launch {
                    val allowed = runCatching { canStartSensing() }.getOrDefault(false)
                    mainHandler.post {
                        if (allowed) startSensing() else stopSelf()
                    }
                }
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
     * 核心门控（Phase 6.1 Permission Degraded）：
     * 仅要求 flag + consent + 核心传感器可用；**不要求** Notification Listener /
     * Usage Access / Mic（拒绝任一只是 missing source + coverage 下降 + confidence 降低，
     * 不得整个 sensing 停止）。
     */
    internal suspend fun canStartSensing(): Boolean {
        val flagEnabled = isPassiveSensingEnabled()
        val consentGranted = isUserConsentGranted()
        return coreSensingGatePasses(
            flagEnabled = flagEnabled,
            consentGranted = consentGranted,
            sensorAvailable = hasCoreSensors()
        )
    }

    /** 核心传感器是否可用（加速度计或陀螺仪任一存在）。 */
    private fun hasCoreSensors(): Boolean {
        val sm = getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return false
        return sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null ||
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null
    }

    /**
     * 读取本租户 feature flag 缓存中的 passive_sensing_enabled。
     *
     * 无缓存/缺 key 时默认 false（fail-closed，02b 共享知识 2）：
     * 隐私敏感 flag 在异常场景下停用而非启用。
     * v0.7 本地优先架构：本地模式（未订阅）放行本地采集（数据不离开设备）。
     */
    private fun isPassiveSensingEnabled(): Boolean {
        // 复用 Application 容器的 preferences（避免每次门控都新建 Keystore 字段加密器；
        // 容器不可用——如 Robolectric 单测无 AndroidKeyStore——时 fail-closed 默认 false）。
        val container = runCatching { (application as? EchoMindApplication)?.container }.getOrNull()
        if (container?.preferences?.localMode == true) return true
        return container?.preferences?.getFeatureFlagsSnapshot()?.get("passive_sensing_enabled") ?: false
    }

    /** 用户 consent：异步读取 PassiveSensingPrefs（DataStore）当前值（fail-closed，失败默认 false）。 */
    private suspend fun isUserConsentGranted(): Boolean =
        runCatching { PassiveSensingPrefs(this@PassiveSensingService).passiveSensingEnabled.first() }
            .getOrDefault(false)

    internal fun startSensing() {
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
        // 麦克风为可选模块：start() 内部检查 micEnabled + RECORD_AUDIO 权限（DataStore 异步读），
        // 不满足时直接返回，不影响其他采集器；移入协程避免主线程阻塞。
        micCollector?.let { mic -> sensingScope.launch { mic.start() } }

        // 5 分钟窗口调度：snapshot → transactional 落库+outbox → 成功才 clear consumed + enqueue sync
        val container = runCatching { (application as? EchoMindApplication)?.container }.getOrNull()
        val scheduler = SensingWindowScheduler(hub, micCollector = micCollector)
        this.scheduler = scheduler
        scheduler.start(sensingScope) { inputs ->
            // ACK 语义：持久化成功（true）才返回；失败保留快照/缓冲，由调度器 bounded retry。
            // 失败必须可观测（AppPreferences.consecutivePersistenceFailures / lastPersistenceFailure 由 repository 记录）。
            val c = container ?: return@start false
            val ok = c.sensingRepository.saveDerivedFeatures(inputs)
            if (ok) {
                runCatching { enqueueSync(this@PassiveSensingService) }
            }
            ok
        }
        container?.preferences?.sensingActive = true
        started = true
    }

    internal fun stopSensing() {
        // scheduler.stop() 同时清空失败重试状态（consent revoke / 停止时不补发）
        scheduler?.stop()
        scheduler = null
        sensorCollector?.stop()
        screenCollector?.stop()
        appActivityCollector?.stop()
        micCollector?.stop()
        // 停止路径清空 hub 缓冲（进程内共享层，保证"后续零新特征"）
        hub.clearAll()
        runCatching { (application as? EchoMindApplication)?.container?.preferences?.sensingActive = false }
        started = false
    }

    /** 调度器是否已启动（internal 便于单测断言 5 分钟调度接线）。 */
    internal fun isSensingRunning(): Boolean = started

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
        private const val CHANNEL_NAME = "ECHO 状态"
        // ERA 1 文案（Master Prompt PART 24）：常驻通知只承担透明度责任——
        // 「ECHO · 正在了解今天」，禁止「后台服务正在运行」式工程语言。
        internal const val NOTIFICATION_TITLE = "ECHO"
        internal const val NOTIFICATION_TEXT = "正在了解今天"
        private const val NOTIFICATION_ID = 0xA001

        const val ACTION_START = "com.yunjue.echo.mind.action.START_SENSING"
        const val ACTION_STOP = "com.yunjue.echo.mind.action.STOP_SENSING"

        /**
         * 核心门控纯函数（Phase 6.1 Permission Degraded）：flag + consent + 核心传感器可用。
         *
         * 拒绝 optional 权限（通知使用权 / 使用情况访问 / 麦克风）不应停止核心 sensing，
         * 只会 missing source + coverage 下降 + confidence 降低。
         */
        internal fun coreSensingGatePasses(
            flagEnabled: Boolean,
            consentGranted: Boolean,
            sensorAvailable: Boolean
        ): Boolean = flagEnabled && consentGranted && sensorAvailable

        /** 通知使用权是否已授权（系统设置）。 */
        internal fun hasNotificationAccess(context: Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)

        /** 使用情况访问（App usage access）是否已授权。 */
        internal fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    ) == AppOpsManager.MODE_ALLOWED
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    ) == AppOpsManager.MODE_ALLOWED
                }
            } catch (e: Exception) {
                false
            }
        }

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
