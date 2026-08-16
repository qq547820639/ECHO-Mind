/**
 * Presence Cache —— 腕上唯一状态面（PUBLIC_SAFE Presence 投影缓存）。
 *
 * Revision / TTL 纪律（ECHO_WRIST_CONTRACT §Revision / TTL）：
 * - 收到 revision < cached → ignore（乱序安全）；
 * - 收到 revision == cached → 只更新显示字段（headline/actions/surface），
 *   Identity 与 Moment 保持缓存值，并续期（WHY 刷新语义）；
 * - 收到 revision > cached → 全量更新；
 * - 断连且过期：保留 Identity，Moment 缓慢降级 QUIET / LOW_CERTAINTY；
 * - 不显示红色 ERROR，不随机生成新的 ECHO，不主动震动提醒"手机断开"。
 */
var storage = require('../cache/storage_wrap.js')
var protocol = require('../protocol/wear_protocol.js')

var PRESENCE_TTL_MS = 15 * 60 * 1000
var CLOCK_SKEW_TOLERANCE_MS = 60 * 1000
var STALE_MOMENT_MIN_MS = 5 * 60 * 1000

function create(nowFn) {
  var now = nowFn || Date.now
  var cached = null // { envelope, receivedAt }

  function load() {
    try {
      var raw = storage.get(storage.PRESENCE_CACHE_KEY, null)
      if (raw) {
        var envelope = JSON.parse(raw)
        if (envelope && envelope.type === 'presence') {
          cached = { envelope: envelope, receivedAt: now() }
        }
      }
    } catch (e) {
      cached = null
    }
    return cached
  }

  function persist() {
    if (cached) {
      storage.set(storage.PRESENCE_CACHE_KEY, JSON.stringify(cached.envelope))
    }
  }

  /**
   * 应用新 Presence。
   * 返回 'full' | 'display' | 'ignored'。
   */
  function apply(envelope, nowMs) {
    var time = nowMs === undefined ? now() : nowMs
    if (!envelope || envelope.type !== 'presence') return 'ignored'
    if (!cached) {
      cached = { envelope: envelope, receivedAt: time }
      persist()
      return 'full'
    }
    if (envelope.revision < cached.envelope.revision) {
      return 'ignored'
    }
    if (envelope.revision === cached.envelope.revision) {
      // 显示级刷新：只取 headline/actions/surface，Identity/Moment 不变（同一个 ECHO）。
      cached.envelope.publicHeadline = envelope.publicHeadline
      cached.envelope.availableActions = envelope.availableActions
      cached.envelope.surface = envelope.surface
      cached.envelope.expiresAt = envelope.expiresAt
      cached.receivedAt = time
      persist()
      return 'display'
    }
    cached = { envelope: envelope, receivedAt: time }
    persist()
    return 'full'
  }

  /** 当前渲染状态：fresh / degraded / empty。 */
  function state(nowMs) {
    var time = nowMs === undefined ? now() : nowMs
    if (!cached) {
      return { phase: 'empty' }
    }
    var age = time - cached.receivedAt
    var expired = time > cached.envelope.expiresAt + CLOCK_SKEW_TOLERANCE_MS
    if (!expired && age < STALE_MOMENT_MIN_MS) {
      return { phase: 'fresh', envelope: cached.envelope, ageMs: age }
    }
    // 降级：Identity 保留，Moment → QUIET / LOW_CERTAINTY；headline 隐去。
    var degradedMoment = {
      flow: scale(cached.envelope.moment.flow, 0.2),
      coherence: scale(cached.envelope.moment.coherence, 0.4),
      density: scale(cached.envelope.moment.density, 0.35),
      turbulence: 0.05,
      brightness: scale(cached.envelope.moment.brightness, 0.45),
    }
    var degraded = JSON.parse(JSON.stringify(cached.envelope))
    degraded.moment = degradedMoment
    degraded.surface = {
      motionLevel: 'QUIET',
      lowPower: true,
      reducedMotion: true,
    }
    degraded.publicHeadline = null
    return { phase: 'degraded', envelope: degraded, ageMs: age, expired: expired }
  }

  function scale(value, factor) {
    if (typeof value !== 'number') return 0
    var scaled = value * factor
    return scaled < 0 ? 0 : scaled > 1 ? 1 : scaled
  }

  function clear() {
    cached = null
    storage.remove(storage.PRESENCE_CACHE_KEY)
  }

  /** 进程重启（Band process death）后恢复缓存：Identity 连续性的持久化锚点。 */
  function cachedRevision() {
    return cached ? cached.envelope.revision : 0
  }

  return {
    load: load,
    apply: apply,
    state: state,
    clear: clear,
    cachedRevision: cachedRevision,
    TTL_MS: PRESENCE_TTL_MS,
  }
}

module.exports = {
  create: create,
  PRESENCE_TTL_MS: PRESENCE_TTL_MS,
  CLOCK_SKEW_TOLERANCE_MS: CLOCK_SKEW_TOLERANCE_MS,
  PROTOCOL_VERSION: protocol.WEAR_SCHEMA_V1,
}
