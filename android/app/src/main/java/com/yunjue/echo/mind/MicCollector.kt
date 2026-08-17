package com.yunjue.echo.mind

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.yunjue.echo.mind.data.PassiveSensingPrefs
import com.yunjue.echo.mind.sensing.MicDerivedFeatureSource
import com.yunjue.echo.mind.sensing.MicFeatureExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 麦克风派生特征消费接口（T02 窗口 flush 依赖的最小抽象）。
 *
 * SensingWindowScheduler 使用非破坏 [snapshot] + 成功持久化后 [clearConsumed]，
 * 与 Hub 的 ACK 语义一致（先持久化、后清 consumed，失败不丢）。
 */
/**
 * 麦克风采集器（T03.1）：
 *
 * - 仅当 micEnabled=true 且 RECORD_AUDIO 权限已授予时启动
 * - 使用 AudioRecord（MIC 源，16kHz，单声道，PCM 16bit）读取音频块
 * - 读取后**即时处理**为派生特征（[MicFeatureExtractor.MicDerivedFeature]），
 *   原始音频 buffer 处理后立即清空并丢弃，**绝不落盘、不上云**
 * - start/stop 幂等，重复调用安全
 * - 权限撤回检测：androidx.core 已移除权限变化监听 API（1.18.0 无
 *   registerOnNotificationsPermissionListener），改为在录音循环中周期性检查
 *   RECORD_AUDIO 权限；一旦发现被系统设置撤回，立即 stop() 并通过
 *   [onPermissionRevoked] 回调通知上层写入 voice_features consent（granted=false）
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
) : MicDerivedFeatureSource {
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
     * 启动前置检查：满足以下全部条件才可启动：
     * 1. 当前未运行
     * 2. RECORD_AUDIO 权限已授予
     * 3. micEnabled 开关为 true（异步读取 DataStore 当前值，fail-closed）
     */
    suspend fun canStart(): Boolean {
        if (running) return false
        if (!hasPermission()) return false
        if (!prefs.micEnabled.first()) return false
        return true
    }

    /**
     * 启动采集：
     * - start 前检查权限 + 开关（[canStart]），任一不满足则直接返回（幂等）
     * - 创建 AudioRecord 并在 IO 协程中循环读取音频块
     * - 每个音频块即时提取特征后清空，仅保留派生特征
     * - 循环内周期性检查 RECORD_AUDIO 权限（见 [PERMISSION_CHECK_INTERVAL_MS]），
     *   被系统设置撤回时停止并触发 [onPermissionRevoked]
     */
    suspend fun start() {
        if (running) return
        if (!canStart()) return

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
            var lastPermissionCheckMs = System.currentTimeMillis()
            try {
                // ERA 32 R20：startRecording 在音频服务异常/竞态（及测试环境无音频栈）时会抛
                // IllegalStateException——此前它在 try 之外，异常逃逸出采集协程直达全局异常
                // 处理器（发布版=进程被杀；单测=污染后续用例的 UncaughtExceptionsBeforeTest）。
                // 麦克风是可选模块：失败必须静默跳过，绝不影响核心 sensing。
                record.startRecording()
                while (isActive && running) {
                    // 周期性检查 RECORD_AUDIO 是否被系统设置撤回（androidx.core 已移除权限变化监听 API）
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastPermissionCheckMs >= PERMISSION_CHECK_INTERVAL_MS) {
                        lastPermissionCheckMs = nowMs
                        if (!hasPermission()) {
                            stop()
                            onPermissionRevoked()
                            break
                        }
                        // ERA 32 R21：支持页关闭麦克风开关 → 采集循环自行退出
                        // （不触发权限撤回回调：consent 已由开关路径写入）。
                        if (!prefs.micEnabled.first()) {
                            stop()
                            break
                        }
                    }
                    val readAtMs = System.currentTimeMillis()
                    val read = record.read(chunk, 0, chunkSize)
                    if (read > 0) {
                        // 即时处理：复制有效部分给提取器，原始 chunk 在循环中被覆盖
                        val snapshot = chunk.copyOfRange(0, read)
                        // ERA 32 R22：携带采集时刻（窗口归属精确化，避免整窗错归属）
                        val feature = extractor.extract(snapshot, SAMPLE_RATE, timestampMs = readAtMs)
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
            } catch (_: Exception) {
                // 可选模块韧性：startRecording/read/extract 任一异常都不逃逸（见上）；
                // 失败后 running=false，后续 start()（服务重启等）可再次尝试。
                running = false
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                audioRecord = null
            }
        }
    }

    /** 停止采集并释放 AudioRecord 资源。幂等。（权限撤回检测在录音循环内周期性执行。） */
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
     * 显式释放资源。
     *
     * 在 Service onDestroy / 测试 tearDown 时调用；幂等，重复调用安全。
     * （androidx.core 已移除权限变化监听 API，无需注销监听器。）
     */
    fun release() {
        stop()
    }

    /** 当前是否已授予 RECORD_AUDIO 权限。 */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 派生特征快照（仅端侧，不落盘不上云）。 */
    override fun snapshot(): List<MicFeatureExtractor.MicDerivedFeature> = derivedBuffer.toList()

    /** 只清快照内已消费项（引用相等）；保留快照后新到项（T02 ACK 语义）。 */
    override fun clearConsumed(consumed: List<MicFeatureExtractor.MicDerivedFeature>) {
        consumed.forEach { derivedBuffer.remove(it) }
    }

    /** 清空派生缓冲（测试或回收）。 */
    fun clearBuffer() {
        derivedBuffer.clear()
    }

    private fun trimDerivedBuffer() {
        while (derivedBuffer.size > MAX_BUFFER_SIZE) derivedBuffer.pollFirst()
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /**
         * ERA 32 R22：派生特征缓冲容量覆盖完整 5 分钟窗口。
         * 100ms/块 → 5 分钟 ≈ 3000 条；4096 留余量并覆盖失败重试期间的新数据。
         * （旧值 64 只保留窗口最后约 6.4 秒，其余全部被 trim 丢弃。）
         * 内存开销：4096 × MicDerivedFeature（约 150B）≈ 600KB，可忽略。
         */
        const val MAX_BUFFER_SIZE = 4096

        /** 录音循环中检查 RECORD_AUDIO 权限的间隔（毫秒）。 */
        const val PERMISSION_CHECK_INTERVAL_MS = 1_000L
    }
}
