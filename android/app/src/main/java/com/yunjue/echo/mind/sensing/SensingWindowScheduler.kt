package com.yunjue.echo.mind.sensing

import com.yunjue.echo.mind.model.DerivedFeatureInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/**
 * 5 分钟窗口调度器（T02 P0）。
 *
 * - 按固定 5 分钟窗口对齐（基于 epoch 毫秒，可注入 [Clock] 便于测试）；
 * - 每个窗口结束时从 [SensingEventHub] 取各 modality 缓冲，
 *   调用 [FeatureExtractor.extractFromHub] 产出 [DerivedFeatureInput]，
 *   麦克风派生特征经 [MicDerivedFeatureSource.snapshotAndClear] 消费并转为 source="mic_opt" 输入；
 * - 窗口 flush 后清空对应原始缓冲（不无限增长）；
 * - 服务被系统重启后基于 windowStart 对齐 epoch 恢复窗口（内存缓冲随进程消亡，无脏数据）；
 * - 同一 windowStart 只 flush 一次（去重保护）；
 * - [WINDOW_DURATION_MS] 引用 [FeatureExtractor.WINDOW_DURATION_MS]（5*60*1000）。
 *
 * 纯 Kotlin 可单测：窗口边界 / 重启恢复 / 重复窗口防护 / buffer 清空。
 */
class SensingWindowScheduler(
    private val hub: SensingEventHub,
    private val featureExtractor: FeatureExtractor = FeatureExtractor(),
    private val clock: Clock = Clock.systemUTC(),
    private val windowDurationMs: Long = FeatureExtractor.WINDOW_DURATION_MS,
    private val micCollector: MicDerivedFeatureSource? = null
) {
    @Volatile
    var running: Boolean = false
        private set

    private var job: Job? = null

    /** 已 flush 过的 windowStart epoch ms 集合（同一窗口只 flush 一次）。 */
    private val flushedWindowStarts = LinkedHashSet<Long>()

    /**
     * 启动调度循环。每到一个对齐边界调用 [onWindowReady]（携带该窗口产出的特征列表）。
     *
     * 重复调用幂等：已 running 时直接返回。
     */
    fun start(scope: CoroutineScope, onWindowReady: suspend (List<DerivedFeatureInput>) -> Unit) {
        if (running) return
        running = true
        job = scope.launch {
            while (isActive && running) {
                val nowMs = clock.millis()
                val windowStart = Instant.ofEpochMilli(alignWindowStartMs(nowMs))
                val windowEnd = Instant.ofEpochMilli(windowStart.toEpochMilli() + windowDurationMs)
                val waitMs = windowEnd.toEpochMilli() - nowMs
                if (waitMs > 0) delay(waitMs)
                if (!isActive || !running) break
                runCatching { flushWindow(windowStart, windowEnd, onWindowReady) }
            }
        }
    }

    /** 停止调度循环（幂等）。 */
    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    /**
     * flush 单个窗口（internal 便于单测直接调用）。
     *
     * - 去重：同一 windowStart 只 flush 一次；
     * - 主模态特征从 [SensingEventHub] 快照提取；
     * - 麦克风派生特征（source="mic_opt"）经 micCollector 消费并清空；
     * - flush 后清空 hub 缓冲；
     * - 窗口无任何信号时不调用 [onWindowReady]（空窗不产生特征）。
     */
    internal suspend fun flushWindow(
        windowStart: Instant,
        windowEnd: Instant,
        onWindowReady: suspend (List<DerivedFeatureInput>) -> Unit
    ): List<DerivedFeatureInput> {
        val startMs = windowStart.toEpochMilli()
        if (!flushedWindowStarts.add(startMs)) return emptyList()

        val inputs = buildList {
            addAll(featureExtractor.extractFromHub(windowStart, windowEnd, hub))
            micCollector?.let { mic ->
                mic.snapshotAndClear().forEach { f ->
                    add(
                        DerivedFeatureInput(
                            schemaVersion = "feat-v1",
                            source = "mic_opt",
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            summary = f.summary,
                            vector = f.vector.toList(),
                            sourcesPresent = listOf("mic_opt")
                        )
                    )
                }
            }
        }
        // 窗口 flush 后清空原始缓冲（不无限增长）
        hub.clearAll()
        if (inputs.isNotEmpty()) {
            onWindowReady(inputs)
        }
        return inputs
    }

    /** 是否已 flush 过指定 windowStart（单测断言去重）。 */
    internal fun hasFlushed(windowStartMs: Long): Boolean = windowStartMs in flushedWindowStarts

    /**
     * 对齐到固定窗口边界（epoch 毫秒）。
     *
     * 例如 windowDurationMs=300000 时，任意时刻对齐到最近的 :00/:05/:10 边界。
     */
    internal fun alignWindowStartMs(nowMs: Long): Long = nowMs - positiveMod(nowMs, windowDurationMs)

    /** 计算下一次窗口开始时间（重启恢复：从当前时刻对齐到下一边界）。 */
    internal fun nextWindowStartMs(nowMs: Long): Long = alignWindowStartMs(nowMs) + windowDurationMs

    private fun positiveMod(value: Long, mod: Long): Long {
        val r = value % mod
        return if (r < 0) r + mod else r
    }
}
