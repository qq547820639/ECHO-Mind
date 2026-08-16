package com.yunjue.echo.mind.wearable

import com.yunjue.echo.mind.model.EchoPresenceState

/**
 * WearablePolicy —— 更新触发 / 频率 / TTL / revision / 触觉宪法。
 *
 * 手机 → 手环更新纪律（禁止按动画帧同步）：
 * 只在 Band app opened / initial connect / reconnect / Presence 语义 revision 改变 /
 * Action changed / privacy state changed / explicit refresh 时发送；Moment 更新 rate limited。
 */
object WearablePolicy {

    /** Presence 有效期：15 分钟。断连且过期 → 手环保留 Identity，Moment 降级。 */
    const val PRESENCE_TTL_MS: Long = 15 * 60 * 1000L

    /** 显式刷新/请求的最小间隔（防抖；语义触发不受此限）。 */
    const val PUSH_MIN_INTERVAL_MS: Long = 10 * 1000L

    /** 手机侧语义 revision 判定阈值（防浮点抖动导致的无效推送）。 */
    const val SEMANTIC_EPSILON: Float = 0.001f

    /** 手环侧可容忍的时钟偏移（±1 分钟）。 */
    const val CLOCK_SKEW_TOLERANCE_MS: Long = 60 * 1000L

    enum class PushTrigger {
        INITIAL_CONNECT,
        RECONNECT,
        PRESENCE_REVISION_CHANGED,
        ACTION_CHANGED,
        PRIVACY_CHANGED,
        EXPLICIT_REFRESH,
        BAND_REQUEST,
    }

    /** 语义触发（状态真的变了）不受最小间隔限制；用户请求类触发受防抖限制。 */
    fun shouldPushPresence(
        trigger: PushTrigger,
        lastPushedAtMs: Long?,
        nowMs: Long,
    ): Boolean = when (trigger) {
        PushTrigger.INITIAL_CONNECT,
        PushTrigger.RECONNECT,
        PushTrigger.PRESENCE_REVISION_CHANGED,
        PushTrigger.ACTION_CHANGED,
        PushTrigger.PRIVACY_CHANGED,
        -> true
        PushTrigger.EXPLICIT_REFRESH,
        PushTrigger.BAND_REQUEST,
        -> lastPushedAtMs == null || nowMs - lastPushedAtMs >= PUSH_MIN_INTERVAL_MS
    }

    /** Presence revision 单调：手环收到 revision <= 缓存 revision 必须 ignore。 */
    fun shouldAcceptRevision(incoming: Long, cached: Long): Boolean = incoming > cached

    /** 过期判定（含时钟偏移容差：允许对端时钟慢/快 1 分钟）。 */
    fun isExpired(expiresAtMs: Long, nowMs: Long): Boolean =
        nowMs > expiresAtMs + CLOCK_SKEW_TOLERANCE_MS

    /**
     * 语义比较：判断 Presence 是否发生需要推送给手环的语义变化。
     * 只比较投影到腕上的字段（Identity / Moment / maturity / surface / actions / headline）。
     * 纯时间戳变化不触发推送。
     */
    fun hasSemanticChange(previous: WearProjection?, current: WearProjection?): Boolean {
        if (previous == null || current == null) return previous != current
        if (previous.maturity != current.maturity) return true
        if (!closeEnough(previous.identity, current.identity)) return true
        if (!closeEnough(previous.moment, current.moment)) return true
        return previous.surface != current.surface
    }

    private fun closeEnough(a: WearIdentityProjection, b: WearIdentityProjection): Boolean =
        feq(a.topology, b.topology) && feq(a.symmetry, b.symmetry) && feq(a.orbit, b.orbit) &&
            feq(a.motion, b.motion) && a.texture == b.texture && a.colorFamily == b.colorFamily &&
            feq(a.accent, b.accent)

    private fun closeEnough(a: WearMomentProjection, b: WearMomentProjection): Boolean =
        feq(a.flow, b.flow) && feq(a.coherence, b.coherence) && feq(a.density, b.density) &&
            feq(a.turbulence, b.turbulence) && feq(a.brightness, b.brightness)

    private fun feq(a: Float, b: Float): Boolean = kotlin.math.abs(a - b) <= SEMANTIC_EPSILON

    /** 手环 Moment 在无新 Presence 时的本地降级参数（QUIET / LOW_CERTAINTY）。 */
    const val STALE_MOMENT_MIN_MS: Long = 5 * 60 * 1000L
    const val FULL_DEGRADE_AFTER_MS: Long = 60 * 60 * 1000L

    // ------------------------------------------------------------------ 触觉宪法

    /**
     * 默认 # SILENT。
     * 绝对禁止：inferred stress → vibration / anomaly → vibration /
     * AI suggestion → vibration / engagement → vibration。
     *
     * v1 只允许显式触觉：
     * explicit tap confirmation / user-started breathing cadence / action completion confirmation。
     * 只使用官方确认存在的 Band10 振动能力：vibrate({mode: short|long})。
     */
    enum class HapticEvent(val allowed: Boolean) {
        TAP_CONFIRMATION(true),
        BREATHING_CADENCE(true),
        ACTION_COMPLETION(true),
    }

    enum class HapticMode { SHORT, LONG }

    /** 显式触觉 → 允许；未知事件名 → 拒绝（默认 SILENT，白名单外禁止）。 */
    fun hapticAllowed(event: HapticEvent): Boolean = event.allowed

    /** Band10 只支持 short/long（官方 vibrator 矩阵）；pattern 一律拒绝。 */
    fun hapticModeFor(event: HapticEvent): HapticMode = when (event) {
        HapticEvent.TAP_CONFIRMATION -> HapticMode.SHORT
        HapticEvent.BREATHING_CADENCE -> HapticMode.SHORT
        HapticEvent.ACTION_COMPLETION -> HapticMode.LONG
    }

    /**
     * 手环观察 → 手机 Memory 的写入禁令：传感器数据属于 Observation，
     * 绝不允许 5 秒 Wrist observation → Memory。本函数永远返回 false
     * （结构上禁止；未来若有用户显式腕上反馈，走用户动作事件路径）。
     */
    fun wristObservationWritesMemory(observationEnvelope: WearObservationEnvelope): Boolean = false
}

/**
 * 触觉速率限制：显式触觉也有最小间隔，防止连续 tap 刷振动（低打扰）。
 */
class HapticRateLimiter(
    private val minIntervalMs: Long = 1500L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastAtMs: Long? = null

    fun tryAllow(nowMs: Long = clock()): Boolean {
        val last = lastAtMs
        return if (last == null || nowMs - last >= minIntervalMs) {
            lastAtMs = nowMs
            true
        } else {
            false
        }
    }
}

/**
 * Presence 语义 revision 计数器：手机侧唯一事实源，单调增长。
 * 由 [WearableRuntime] 持有；语义变化 → revision+1。
 */
class PresenceRevisionCounter(private var value: Long = 0L) {
    val current: Long get() = value
    fun next(): Long = ++value

    /** 语义变化才推进；未变化保持原 revision（禁止无意义推送）。 */
    fun advanceIfChanged(changed: Boolean): Long = if (changed) next() else value

    companion object {
        /** 状态没有 revision 字段时的兜底语义指纹（不泄露私密内容，只含投影字段）。 */
        fun fingerprint(state: EchoPresenceState): String = buildString {
            append(state.maturity.name).append('|')
            append(state.identityGenome.seed).append('|')
            append(state.identityGenome.accentHue).append('|')
            append(state.identityGenome.colorFamily).append('|')
            append(state.identityGenome.textureFamily).append('|')
            append(state.identityGenome.coreTopology).append('|')
            append(state.identityGenome.symmetryTendency).append('|')
            append(state.identityGenome.orbitGeometry).append('|')
            append(state.identityGenome.motionPersonality).append('|')
            append(state.dailyComposition.flowSpeed).append('|')
            append(state.dailyComposition.coherence).append('|')
            append(state.dailyComposition.turbulence).append('|')
            append(state.dailyComposition.particleDensity).append('|')
            append(state.dailyComposition.brightness)
        }
    }
}
