/**
 * Accel Summary —— 前台加速度计窗口聚合（官方 background 模型：foreground only）。
 *
 * 纪律（ECHO_WRIST_CONTRACT §Band Accelerometer）：
 * - 只在 App foreground 订阅（onShow 订阅 / onHide 退订），不 hack 保活；
 * - 不全天发送 raw；本地 5–15s 窗口（默认 10s）；
 * - 只发 summary：motionEnergy / movementClass / sampleCoverage / quality / window timestamps；
 * - raw samples：仅 Debug + explicit diagnostic consent（本模块不发 raw）。
 *
 * 官方 system.sensor.subscribeAccelerometer：interval game≈20ms / ui≈60ms / normal≈200ms。
 * 使用 normal（≈200ms），Battery first。
 */
var WINDOW_MS = 10 * 1000
var EXPECTED_INTERVAL_MS = 200
var G = 9.81

function classifyMotion(energy) {
  if (energy === null) return 'UNKNOWN'
  if (energy < 0.3) return 'STATIONARY'
  if (energy < 0.8) return 'LOW'
  if (energy < 1.5) return 'WALKING'
  return 'VIGOROUS'
}

function create(nowFn) {
  var now = nowFn || Date.now
  var sensor = null
  var currentSensor = null
  var windowStart = null
  var sumSq = 0
  var sampleCount = 0
  var onWindow = null
  var active = false

  function trySensor() {
    if (sensor) return sensor
    try {
      sensor = require('@system.sensor')
    } catch (e) {
      sensor = null
    }
    return sensor
  }

  function onSample(ret) {
    if (!active) return
    var x = typeof ret.x === 'number' ? ret.x : 0
    var y = typeof ret.y === 'number' ? ret.y : 0
    var z = typeof ret.z === 'number' ? ret.z : 0
    var magnitude = Math.sqrt(x * x + y * y + z * z)
    var energy = Math.abs(magnitude - G) // 去重力（近似）
    // 契约 §8：5–15s 窗口——窗口满（WINDOW_MS 上限）即 flush 本窗口并开新窗；
    // onHide/onDestroy 的 stop() 负责余窗。
    if (windowStart !== null && now() - windowStart >= WINDOW_MS) {
      finishWindow()
      windowStart = now()
    }
    sumSq += energy * energy
    sampleCount += 1
  }

  function finishWindow() {
    if (windowStart === null || !onWindow) return
    var end = now()
    var coverage = sampleCount * EXPECTED_INTERVAL_MS / Math.max(1, end - windowStart)
    var energy = sampleCount > 0 ? Math.sqrt(sumSq / sampleCount) : null
    var quality = coverage >= 0.8 ? 'GOOD' : coverage >= 0.4 ? 'POOR' : 'UNKNOWN'
    onWindow({
      motionEnergy: energy === null ? null : Math.round(energy * 1000) / 1000,
      movementClass: classifyMotion(energy),
      sampleCoverage: Math.round(Math.min(1, coverage) * 1000) / 1000,
      quality: quality,
      windowStartMs: windowStart,
      windowEndMs: end,
    })
    windowStart = null
    sumSq = 0
    sampleCount = 0
  }

  function start(opts) {
    var s = (opts && opts.sensor) || trySensor() // 测试可注入假 sensor；生产走 @system.sensor
    onWindow = (opts && opts.onWindow) || null
    if (!s || !onWindow) return false
    active = true
    currentSensor = s
    windowStart = now()
    sumSq = 0
    sampleCount = 0
    try {
      s.subscribeAccelerometer({
        interval: 'normal',
        callback: onSample,
        fail: function () {
          active = false
        },
      })
    } catch (e) {
      active = false
    }
    return active
  }

  function stop() {
    active = false
    finishWindow()
    var s = currentSensor || trySensor()
    if (s) {
      try {
        s.unsubscribeAccelerometer()
      } catch (e) {
        // 忽略
      }
    }
    currentSensor = null
    windowStart = null
  }

  return { start: start, stop: stop, isActive: function () { return active } }
}

// 页面级共享实例（echo/index.ux 直接 start/stop；测试用 create(nowFn) 注入时钟与 sensor）。
var shared = create()

module.exports = {
  create: create,
  classifyMotion: classifyMotion,
  WINDOW_MS: WINDOW_MS,
  start: shared.start,
  stop: shared.stop,
}
