package com.yunjue.echo.mind.sensing

import com.yunjue.echo.mind.model.DerivedFeatureInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/** 窗口 flush 结果（T02 窗口 ACK 语义）。 */
enum class WindowFlushResult { SUCCESS, FAILURE_RETRYABLE }

/**
 * 5 分钟窗口调度器（T02 P0/P1 + Phase 4 Immutable Window）。
 *
 * - 按固定 5 分钟窗口对齐（基于 epoch 毫秒，可注入 [Clock] 便于测试）；
 * - 每个窗口结束时从 [SensingEventHub] 取**不可变快照**（[SensingEventHub.snapshotAll]），
 *   调用 [FeatureExtractor.extractFromSnapshot] 产出 [DerivedFeatureInput]，
 *   麦克风派生特征经 [MicDerivedFeatureSource.snapshot] 消费并转为 source="mic_opt" 输入；
 * - **ACK 语义（消除静默丢失）**：
 *   1. snapshot（非破坏）→ extract（纯函数，吃快照不读 live hub）→ 调 [onWindowReady] 持久化；
 *   2. 持久化成功（返回 true）→ 才 [SensingEventHub.clearConsumed] + mic clearConsumed
 *      + 窗口进入 flushed 集；
 *   3. 持久化失败（false/异常）→ 快照/缓冲保留、窗口不进 flushed 集、进入 bounded retry
 *      （[MAX_WINDOW_RETRY]），失败可观测（[retryCount]）；
 *   4. Phase 4：retry 重处理**同一个** snapshot（不可变；快照后新到事件只能属于后一个窗口，
 *      绝不重新读 live hub 把新事件混入旧窗口）；success 只清同一批事件。
 * - 服务被系统重启后基于 windowStart 对齐 epoch 恢复窗口（内存缓冲随进程消亡，无脏数据）；
 * - 同一 windowStart 只 flush 一次（去重保护）；flushed 集为有界去重范围（只保留最近 N 个窗口）；
 * - [WINDOW_DURATION_MS] 引用 [FeatureExtractor.WINDOW_DURATION_MS]（5*60*1000）。
 *
 * 纯 Kotlin 可单测：窗口边界 / 重启恢复 / 重复窗口防护 / ACK 失败重试 / 有界去重范围。
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

    /** 已 flush 过的 windowStart epoch ms 集合（有界：只保留最近 [FLUSHED_WINDOW_KEEP] 个窗口）。 */
    private val flushedWindowStarts = LinkedHashSet<Long>()

    /** 失败重试状态（windowStart → 不可变重试载荷）：retry 重放同一 inputs/快照，绝不重读 live hub。 */
    private val pendingRetries = HashMap<Long, PendingWindow>()

    /** 当前失败重试中的窗口数（可观测）。 */
    val pendingRetryCount: Int get() = pendingRetries.size

    /**
     * 启动调度循环。每到一个对齐边界调用 [onWindowReady]（携带该窗口产出的特征列表，
     * 返回 true 表示持久化成功）。[onBoundary]（可选）在每个循环周期开始调用——
     * 供上层刷新活性心跳（空窗也刷新，watchdog 区分「空闲活着」与「假活」）。
     *
     * 每个循环周期先重试失败窗口（bounded retry，最多每周期一次），再等待当前窗口边界。
     * 重复调用幂等：已 running 时直接返回。
     */
    fun start(
        scope: CoroutineScope,
        onBoundary: (() -> Unit)? = null,
        onWindowReady: suspend (List<DerivedFeatureInput>) -> Boolean,
    ) {
        if (running) return
        running = true
        job = scope.launch {
            while (isActive && running) {
                // ERA 32 R26：周期活性心跳（含空窗——心跳 ≠ 数据新鲜度）
                onBoundary?.invoke()
                // 1. 重试失败窗口（bounded retry ≤ MAX_WINDOW_RETRY；重放同一不可变快照）
                val pending = pendingRetries.keys.toList()
                for (startMs in pending) {
                    if (!isActive || !running) break
                    runCatching { retryPendingWindow(startMs, onWindowReady) }
                }
                // 2. 等待当前窗口边界并 flush
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

    /** 停止调度循环（幂等），同时清空失败重试状态（consent revoke / 服务停止）。 */
    fun stop() {
        running = false
        job?.cancel()
        job = null
        pendingRetries.clear()
    }

    /** 丢弃一个失败重试中的窗口（超限后调用，保证不无限重试）。 */
    fun dropPendingWindow(startMs: Long) {
        pendingRetries.remove(startMs)
        // 窗口不再重试；不进入 flushed 集（数据已不可恢复，但失败已被记录/可观测）
    }

    /** 指定 windowStart 的当前失败次数（单测断言）。 */
    fun retryCount(windowStartMs: Long): Int = pendingRetries[windowStartMs]?.attempts ?: 0

    /**
     * ERA 32 R22：重试失败窗口——重放失败时提取的**同一不可变 inputs + 同一快照**，
     * 不再重新 snapshot live hub（否则重试快照会混入下一窗口未 flush 事件，
     * 成功后 clearConsumed 会连带清掉它们 → 下一窗口数据永久丢失）。
     */
    private suspend fun retryPendingWindow(
        startMs: Long,
        onWindowReady: suspend (List<DerivedFeatureInput>) -> Boolean,
    ) {
        val pending = pendingRetries[startMs] ?: return
        val ok = runCatching { onWindowReady(pending.inputs) }.getOrDefault(false)
        if (ok) {
            hub.clearConsumed(pending.hubSnapshot)
            micCollector?.clearConsumed(pending.micSnapshot)
            flushedWindowStarts.add(startMs)
            trimFlushedWindows()
            pendingRetries.remove(startMs)
        } else {
            recordRetry(startMs, pending)
        }
    }

    /**
     * flush 单个窗口（便于单测直接调用）。
     *
     * Phase 4（Immutable Window）：
     * - 非破坏快照 [SensingEventHub.snapshotAll] **一次取定**，传给
     *   [FeatureExtractor.extractFromSnapshot]——绝不在此处再读 live hub；
     * - retry 重处理同一快照（[pendingRetries] 保存快照，保证"重试必须重新处理同一个 snapshot"）；
     * - 成功只清同一批事件（[SensingEventHub.clearConsumed(snapshot)]）；
     * - terminal failure（超限）记录 gap（dropPendingWindow + 可观测计数），不污染下一窗口；
     * - 屏幕 carry-over / App foreground 由 shared 状态提供（Phase 4.3）。
     *
     * - 去重：同一 windowStart 只 flush 一次（成功路径）；
     * - 窗口无任何信号时不调 [onWindowReady]（空窗不产生特征），直接标记 flushed；
     * - [onWindowReady] 返回 true → 清 consumed + 进 flushed 集 + 清 retry；
     *   false/异常 → 保留缓冲，进 bounded retry。
     */
    suspend fun flushWindow(
        windowStart: Instant,
        windowEnd: Instant,
        onWindowReady: suspend (List<DerivedFeatureInput>) -> Boolean
    ): WindowFlushResult {
        val startMs = windowStart.toEpochMilli()
        if (startMs in flushedWindowStarts) return WindowFlushResult.SUCCESS

        // 1. 非破坏快照（本窗口消费项；快照后新到项保留）——Phase 4：不可变，retry 复用同一快照
        val hubSnapshot = hub.snapshotAll()
        // ERA 32 R22：麦克风特征按时间戳归属窗口（timestampMs=0 为无时间戳旧数据，视作本窗口）
        val micSnapshot = micCollector?.snapshot().orEmpty().filter {
            it.timestampMs == 0L || it.timestampMs in startMs until windowEnd.toEpochMilli()
        }

        // 2. 纯函数提取（吃快照，不读 live hub；carry 状态进程级共享）
        val inputs = buildList {
            addAll(featureExtractor.extractFromSnapshot(
                windowStart,
                windowEnd,
                hubSnapshot,
                screenCarryState = ScreenCollector.carryState(),
                appForeground = AppActivityCollector.foregroundState()
            ))
            micSnapshot.forEach { f ->
                add(
                    DerivedFeatureInput(
                        schemaVersion = "mic-feature-v1",
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

        // 3. 空窗口：无特征可持久化，直接标记 flushed（不调回调）
        if (inputs.isEmpty()) {
            flushedWindowStarts.add(startMs)
            trimFlushedWindows()
            pendingRetries.remove(startMs)
            return WindowFlushResult.SUCCESS
        }

        // 4. 持久化（ACK）：成功才清 consumed + 进 flushed 集
        val ok = runCatching { onWindowReady(inputs) }.getOrDefault(false)
        return if (ok) {
            hub.clearConsumed(hubSnapshot)
            micCollector?.clearConsumed(micSnapshot)
            flushedWindowStarts.add(startMs)
            trimFlushedWindows()
            pendingRetries.remove(startMs)
            WindowFlushResult.SUCCESS
        } else {
            recordRetry(startMs, PendingWindow(inputs, hubSnapshot, micSnapshot))
            WindowFlushResult.FAILURE_RETRYABLE
        }
    }

    /** 记录一次失败（携带不可变重试载荷）；超限丢弃窗口（不再重试），失败始终可观测。 */
    private fun recordRetry(startMs: Long, pending: PendingWindow) {
        if (pendingRetries[startMs] == null) {
            pendingRetries[startMs] = pending
        }
        pendingRetries[startMs]?.let { it.attempts += 1 }
        if (pendingRetries[startMs]?.attempts ?: 0 > MAX_WINDOW_RETRY) {
            dropPendingWindow(startMs)
        }
    }

    /** ERA 32 R22：失败窗口的不可变重试载荷（同一 inputs + 同一快照，重放用）。 */
    private class PendingWindow(
        val inputs: List<DerivedFeatureInput>,
        val hubSnapshot: SensingEventHub.HubSnapshot,
        val micSnapshot: List<MicFeatureExtractor.MicDerivedFeature>,
    ) {
        var attempts: Int = 0
    }

    /** 有界去重范围：只保留最近 [FLUSHED_WINDOW_KEEP] 个已 flush 窗口。 */
    private fun trimFlushedWindows() {
        while (flushedWindowStarts.size > FLUSHED_WINDOW_KEEP) {
            val oldest = flushedWindowStarts.firstOrNull() ?: break
            flushedWindowStarts.remove(oldest)
        }
    }

    /** 是否已 flush 过指定 windowStart（单测断言去重）。 */
    fun hasFlushed(windowStartMs: Long): Boolean = windowStartMs in flushedWindowStarts

    /**
     * 对齐到固定窗口边界（epoch 毫秒）。
     *
     * 例如 windowDurationMs=300000 时，任意时刻对齐到最近的 :00/:05/:10 边界。
     */
    fun alignWindowStartMs(nowMs: Long): Long = nowMs - positiveMod(nowMs, windowDurationMs)

    /** 计算下一次窗口开始时间（重启恢复：从当前时刻对齐到下一边界）。 */
    fun nextWindowStartMs(nowMs: Long): Long = alignWindowStartMs(nowMs) + windowDurationMs

    private fun positiveMod(value: Long, mod: Long): Long {
        val r = value % mod
        return if (r < 0) r + mod else r
    }

    companion object {
        /** 单个窗口最大重试次数（bounded retry）。 */
        const val MAX_WINDOW_RETRY = 3

        /** 有界去重范围：最多保留最近 48 个已 flush 窗口（≈4 小时），防止无限增长。 */
        const val FLUSHED_WINDOW_KEEP = 48
    }
}
