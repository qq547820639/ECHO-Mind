package com.yunjue.echo.mind.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yunjue.echo.mind.PassiveSensingPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 麦克风采集器（T03.1）：
 *
 * - 仅当 micEnabled=true 且 RECORD_AUDIO 权限已授予时启动
 * - 使用 AudioRecord（MIC 源，16kHz，单声道，PCM 16bit）读取音频块
 * - 读取后**即时处理**为派生特征（[MicFeatureExtractor.MicDerivedFeature]），
 *   原始音频 buffer 处理后立即清空并丢弃，**绝不落盘、不上云**
 * - start/stop 幂等，重复调用安全
 * - 通过 [androidx.core.app.NotificationManagerCompat.OnPermissionsChangedListener]
 *   监听权限撤回事件，撤回时立即 stop() 并通过 [onPermissionRevoked] 回调通知上层
 *   写入 voice_features consent（granted=false）
 *
 * 与 [SensorCollector] 的内存缓冲模式不同：本采集器不保留任何原始音频，
 * 仅保留提取后的派生特征（summary + vector），且派生缓冲容量极小。
 *
 * @param context   任意 Context，内部取 applicationContext
 * @param prefs     被动采集偏好（读取 micEnabled 开关）
 * @param extractor 特征提取器（可注入便于测试）
 * @param onPermissionRevoked 权限被撤回时的回调（上层负责写 consent + 同步）
 */
class MicCollector(
    context: Context,
    private val prefs: PassiveSensingPrefs,
    private val extractor: MicFeatureExtractor = MicFeatureExtractor(),
    private val onPermissionRevoked: () -> Unit = {}
) {
    private val appContext = context.applicationContext

    /** 派生特征内存缓冲（仅端侧，不落盘）。 */
    private val derivedBuffer = ConcurrentLinkedDeque<MicFeatureExtractor.MicDerivedFeature>()

    /** 是否已启动 AudioRecord。 */
    @Volatile
    var running: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    /**
     * 权限撤回监听器：当 RECORD_AUDIO 被系统设置撤回时触发。
     *
     * 实现 [NotificationManagerCompat.OnPermissionsChangedListener]，
     * 该回调在任意权限变化时触发；过滤出 RECORD_AUDIO 撤回且当前正在采集时
     * 才执行 stop + 通知。
     */
    private val permissionsChangedListener =
        NotificationManagerCompat.OnPermissionsChangedListener { _ ->
            // 只在 running 中检查；非 running 时权限变化无意义
            if (running && !hasPermission()) {
                stop()
                onPermissionRevoked()
            }
        }

    /** 是否已注册权限监听器。 */
    @Volatile
    private var listenerRegistered: Boolean = false

    /**
     * 启动前置检查：满足以下全部条件才可启动：
     * 1. 当前未运行
     * 2. RECORD_AUDIO 权限已授予
     * 3. micEnabled 开关为 true（同步读取 DataStore 当前值）
     */
    fun canStart(): Boolean {
        if (running) return false
        if (!hasPermission()) return false
        if (!isMicEnabledBlocking()) return false
        return true
    }

    /**
     * 启动采集：
     * - start 前检查权限 + 开关（[canStart]），任一不满足则直接返回（幂等）
     * - 注册权限撤回监听器（仅在首次 start 时注册，避免重复）
     * - 创建 AudioRecord 并在 IO 协程中循环读取音频块
     * - 每个音频块即时提取特征后清空，仅保留派生特征
     */
    fun start() {
        if (running) return
        if (!canStart()) return

        ensurePermissionListenerRegistered()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        val bufferSize = minBuf * 2
        val record = runCatching {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }.getOrNull() ?: return
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return
        }
        audioRecord = record
        running = true
        recordJob = scope.launch {
            val chunkSize = SAMPLE_RATE / 10 // 100ms = 1600 samples @ 16kHz
            val chunk = ShortArray(chunkSize)
            record.startRecording()
            try {
                while (isActive && running) {
                    val read = record.read(chunk, 0, chunkSize)
                    if (read > 0) {
                        // 即时处理：复制有效部分给提取器，原始 chunk 在循环中被覆盖
                        val snapshot = chunk.copyOfRange(0, read)
                        val feature = extractor.extract(snapshot, SAMPLE_RATE)
                        // 显式清空 snapshot 引用内容，确保原始音频不可被后续访问
                        for (i in snapshot.indices) snapshot[i] = 0
                        // 仅保留派生特征，限制缓冲容量
                        trimDerivedBuffer()
                        derivedBuffer.offerLast(feature)
                        // 清空 chunk，下一轮覆盖前不残留原始数据
                        for (i in chunk.indices) chunk[i] = 0
                    } else {
                        // read 返回 0 或错误（真实 AudioRecord 会阻塞；测试环境需让出 CPU）
                        Thread.sleep(5)
                    }
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                audioRecord = null
            }
        }
    }

    /** 停止采集并释放 AudioRecord 资源。幂等。不注销权限监听器（保留以便后续撤回仍可感知）。 */
    fun stop() {
        running = false
        recordJob?.cancel()
        recordJob = null
        audioRecord?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        audioRecord = null
    }

    /**
     * 显式释放资源并注销权限监听器。
     *
     * 在 Service onDestroy / 测试 tearDown 时调用，避免监听器泄漏。
     */
    fun release() {
        stop()
        if (listenerRegistered) {
            runCatching {
                NotificationManagerCompat.from(appContext)
                    .removeOnPermissionsChangedListener(permissionsChangedListener)
            }
            listenerRegistered = false
        }
    }

    /** 当前是否已授予 RECORD_AUDIO 权限。 */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 同步读取 micEnabled（仅 [canStart] 时调用一次）。
     * 使用 runBlocking 读取 DataStore 当前值，在 Service 主线程上耗时极短。
     */
    private fun isMicEnabledBlocking(): Boolean = runBlocking {
        prefs.micEnabled.first()
    }

    /** 注册权限撤回监听器（幂等，已注册时跳过）。 */
    private fun ensurePermissionListenerRegistered() {
        if (listenerRegistered) return
        runCatching {
            NotificationManagerCompat.from(appContext)
                .addOnPermissionsChangedListener(permissionsChangedListener)
        }
        listenerRegistered = true
    }

    /** 派生特征快照（仅端侧，不落盘不上云）。 */
    fun snapshot(): List<MicFeatureExtractor.MicDerivedFeature> = derivedBuffer.toList()

    /** 清空派生缓冲（测试或回收）。 */
    fun clearBuffer() {
        derivedBuffer.clear()
    }

    private fun trimDerivedBuffer() {
        while (derivedBuffer.size > MAX_BUFFER_SIZE) derivedBuffer.pollFirst()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_BUFFER_SIZE = 64
    }
}
