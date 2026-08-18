/**
 * 手环端协议/缓存/视觉 Node 静态测试（普通 Node 即可运行，无 Vela 工具链依赖）。
 * 与手机 Kotlin 侧 WearMessageCodec / WearablePolicy / WearPresenceProjector 同语义。
 *
 * 运行：node tests/run.js
 */
const assert = require('assert')

const protocol = require('../src/common/protocol/wear_protocol.js')
const storage = require('../src/common/cache/storage_wrap.js')
const cache = require('../src/common/presence/presence_cache.js')
const visual = require('../src/common/visual/wear_visual.js')
const accel = require('../src/common/sensor/accel_summary.js')
const declaredFeatures = require('./declared_features_test.js')

let passed = 0
let failed = 0

function test(name, fn) {
  try {
    fn()
    passed += 1
    console.log('  ok  ' + name)
  } catch (e) {
    failed += 1
    console.error('FAIL  ' + name + '\n      ' + e.message)
  }
}

function makePresence(overrides) {
  const envelope = {
    type: 'presence',
    schemaVersion: 1,
    messageId: 'p-1',
    generatedAt: 1000,
    source: 'PHONE',
    revision: 1,
    expiresAt: 1000 + cache.PRESENCE_TTL_MS,
    maturity: 'KNOWN',
    identity: { topology: 0.5, symmetry: 0.5, orbit: 0.5, motion: 0.5, texture: 2, colorFamily: 3, accent: 0.5 },
    moment: { flow: 0.5, coherence: 0.7, density: 0.4, turbulence: 0.2, brightness: 0.6 },
    surface: { motionLevel: 'DEFAULT', lowPower: false, reducedMotion: false },
  }
  return Object.assign(envelope, overrides || {})
}

// ------------------------------------------------------------------ protocol

console.log('# protocol')
test('decode presence from phone (Kotlin codec parity)', () => {
  const text = JSON.stringify(makePresence())
  const result = protocol.decodeMessage(text)
  assert.strictEqual(result.ok, true)
  const m = result.message
  assert.strictEqual(m.type, 'presence')
  assert.strictEqual(m.revision, 1)
  assert.strictEqual(m.identity.topology, 0.5)
  assert.strictEqual(m.moment.coherence, 0.7)
  assert.strictEqual(m.maturity, 'KNOWN')
})

test('malformed JSON rejected', () => {
  assert.strictEqual(protocol.decodeMessage('not json').ok, false)
  assert.strictEqual(protocol.decodeMessage('{').ok, false)
  assert.strictEqual(protocol.decodeMessage('').ok, false)
})

test('unsupported schema rejected', () => {
  const text = JSON.stringify(makePresence({ schemaVersion: 99 }))
  const result = protocol.decodeMessage(text)
  assert.strictEqual(result.ok, false)
  assert.strictEqual(result.unsupportedSchema, true)
})

test('unknown fields ignored (forward compatible)', () => {
  const env = makePresence()
  env.futureField = 'x'
  env.identity.futureIdentityField = 42
  const result = protocol.decodeMessage(JSON.stringify(env))
  assert.strictEqual(result.ok, true)
  assert.strictEqual(result.message.revision, 1)
})

test('missing required fields rejected', () => {
  const env = makePresence()
  delete env.moment
  assert.strictEqual(protocol.decodeMessage(JSON.stringify(env)).ok, false)
  const env2 = makePresence()
  delete env2.identity.topology
  assert.strictEqual(protocol.decodeMessage(JSON.stringify(env2)).ok, false)
})

test('encodeAction shape matches Kotlin decode expectations', () => {
  const msg = protocol.encodeAction('START_BREATHING')
  assert.strictEqual(msg.type, 'action')
  assert.strictEqual(msg.command, 'START_BREATHING')
  assert.strictEqual(msg.source, 'XIAOMI_BAND')
  assert.strictEqual(msg.schemaVersion, 1)
  assert.ok(typeof msg.messageId === 'string' && msg.messageId.length > 0)
  assert.ok(typeof msg.generatedAt === 'number')
})

test('surface motionSummaryEnabled parses (consent-first)', () => {
  const text = JSON.stringify(makePresence({ surface: { motionLevel: 'DEFAULT', lowPower: false, reducedMotion: false, motionSummaryEnabled: true } }))
  const result = protocol.decodeMessage(text)
  assert.strictEqual(result.ok, true)
  assert.strictEqual(result.message.surface.motionSummaryEnabled, true)
  // 旧版手机（无该字段）→ 默认 false（默认关）
  const oldText = JSON.stringify(makePresence({ surface: { motionLevel: 'DEFAULT', lowPower: false, reducedMotion: false } }))
  const oldResult = protocol.decodeMessage(oldText)
  assert.strictEqual(oldResult.message.surface.motionSummaryEnabled, false)
})

test('surface hapticsEnabled parses (default SILENT, phone-owned switch)', () => {
  const on = JSON.stringify(makePresence({ surface: { motionLevel: 'DEFAULT', lowPower: false, reducedMotion: false, hapticsEnabled: true } }))
  assert.strictEqual(protocol.decodeMessage(on).message.surface.hapticsEnabled, true)
  // 旧版手机（无该字段）→ 默认 false = SILENT（振动必须 no-op）
  const oldText = JSON.stringify(makePresence({ surface: { motionLevel: 'DEFAULT', lowPower: false, reducedMotion: false } }))
  assert.strictEqual(protocol.decodeMessage(oldText).message.surface.hapticsEnabled, false)
  const explicitOff = JSON.stringify(makePresence({ surface: { motionLevel: 'DEFAULT', lowPower: false, reducedMotion: false, hapticsEnabled: false } }))
  assert.strictEqual(protocol.decodeMessage(explicitOff).message.surface.hapticsEnabled, false)
})

test('encodeObservation preserves UNKNOWN as absent (not 0)', () => {
  const msg = protocol.encodeObservation({
    movementClass: 'UNKNOWN',
    sampleCoverage: 0.4,
    quality: 'POOR',
    windowStartMs: 1,
    windowEndMs: 10001,
  })
  assert.strictEqual(msg.motion.motionEnergy, undefined) // UNKNOWN ≠ 0
  assert.strictEqual(msg.motion.movementClass, 'UNKNOWN')
})

// ------------------------------------------------------------------ presence cache

console.log('# presence cache')

test('revision monotonic: lower ignored, equal display-only, higher full', () => {
  storage._resetForTest()
  const c = cache.create(() => 1000)
  assert.strictEqual(c.apply(makePresence({ revision: 3 }), 1000), 'full')
  assert.strictEqual(c.apply(makePresence({ revision: 2 }), 1000), 'ignored')
  const display = c.apply(makePresence({ revision: 3, publicHeadline: '今天很安静。' }), 1000)
  assert.strictEqual(display, 'display')
  const state = c.state(1000)
  assert.strictEqual(state.envelope.publicHeadline, '今天很安静。')
  const full = c.apply(makePresence({ revision: 4, identity: Object.assign({}, makePresence().identity, { topology: 0.9 }) }), 1000)
  assert.strictEqual(full, 'full')
  assert.strictEqual(c.state(1000).envelope.identity.topology, 0.9)
})

test('display-only refresh keeps identity/moment (same ECHO)', () => {
  storage._resetForTest()
  const c = cache.create(() => 1000)
  c.apply(makePresence({ revision: 1, identity: Object.assign({}, makePresence().identity, { accent: 0.7 }) }), 1000)
  const refreshed = makePresence({
    revision: 1,
    publicHeadline: '今天很安静。',
    identity: Object.assign({}, makePresence().identity, { accent: 0.1 }), // 显示刷新不应覆盖 identity
  })
  c.apply(refreshed, 2000)
  const state = c.state(2000)
  assert.strictEqual(state.envelope.identity.accent, 0.7)
  assert.strictEqual(state.envelope.publicHeadline, '今天很安静。')
})

test('stale presence degrades moment but keeps identity', () => {
  storage._resetForTest()
  const c = cache.create(() => 0)
  c.apply(makePresence({ revision: 5 }), 0)
  const state = c.state(0 + cache.PRESENCE_TTL_MS + 60 * 1000)
  assert.strictEqual(state.phase, 'degraded')
  assert.strictEqual(state.envelope.identity.topology, 0.5) // Identity 保留
  assert.strictEqual(state.envelope.surface.motionLevel, 'QUIET') // Moment → QUIET
  assert.strictEqual(state.envelope.publicHeadline, null)
  assert.ok(state.envelope.moment.coherence < 0.7)
})

test('degraded surface keeps consent switches (haptics/motion) — 降级不重置用户开关', () => {
  storage._resetForTest()
  const c = cache.create(() => 0)
  c.apply(makePresence({ revision: 6, surface: { motionLevel: 'DEFAULT', lowPower: false, reducedMotion: false, motionSummaryEnabled: true, hapticsEnabled: true } }), 0)
  const state = c.state(0 + cache.PRESENCE_TTL_MS + 60 * 1000)
  assert.strictEqual(state.phase, 'degraded')
  assert.strictEqual(state.envelope.surface.hapticsEnabled, true)
  assert.strictEqual(state.envelope.surface.motionSummaryEnabled, true)
  // 默认（未开启）→ 降级后仍 false（SILENT）
  const c2 = cache.create(() => 0)
  c2.apply(makePresence({ revision: 6 }), 0)
  const state2 = c2.state(0 + cache.PRESENCE_TTL_MS + 60 * 1000)
  assert.strictEqual(state2.envelope.surface.hapticsEnabled, false)
})

test('band process death: cache persists and identity continues', () => {
  storage._resetForTest()
  const c1 = cache.create(() => 0)
  c1.apply(makePresence({ revision: 7 }), 0)
  // 模拟进程死亡 → 新实例从 storage 恢复
  const c2 = cache.create(() => 5000)
  c2.load()
  assert.strictEqual(c2.cachedRevision(), 7)
  const state = c2.state(5000)
  assert.strictEqual(state.envelope.identity.topology, 0.5)
})

test('no cache → empty phase (never fabricate a new ECHO)', () => {
  storage._resetForTest()
  const c = cache.create(() => 0)
  assert.strictEqual(c.state(0).phase, 'empty')
})

// ------------------------------------------------------------------ visual

console.log('# visual')

test('computeVisual deterministic for same envelope', () => {
  const env = makePresence()
  const a = visual.computeVisual(env, {})
  const b = visual.computeVisual(env, {})
  assert.deepStrictEqual(a, b)
})

test('visual keeps identity family: accent hue deterministic', () => {
  const envA = makePresence({ identity: Object.assign({}, makePresence().identity, { accent: 0.2 }) })
  const envB = makePresence({ identity: Object.assign({}, makePresence().identity, { accent: 0.9 }) })
  const a = visual.computeVisual(envA, {})
  const b = visual.computeVisual(envB, {})
  assert.notStrictEqual(a.accent, b.accent)
})

test('low power / reduced motion → slowest update (battery first)', () => {
  const quiet = visual.computeVisual(makePresence({ surface: { motionLevel: 'DEFAULT', lowPower: true, reducedMotion: true } }), {})
  const lively = visual.computeVisual(makePresence({ surface: { motionLevel: 'LIVELY', lowPower: false, reducedMotion: false } }), {})
  assert.ok(quiet.intervalMs >= lively.intervalMs)
  assert.strictEqual(quiet.intervalMs, 2000)
})

test('V3 §73 wrist visual budget (2-4 loops, 8-18 particles)', () => {
  const v = visual.computeVisual(makePresence(), {})
  assert.ok(v.particles.length >= 8 && v.particles.length <= 18)
  assert.ok(v.textureSteps >= 2 && v.textureSteps <= 4)
  assert.ok(v.chirality === 1 || v.chirality === -1)
})

test('V3 §74 stale → QUIET（particles × .10 / pulse ×1.35；无红色 ERROR）', () => {
  const fresh = visual.computeVisual(makePresence(), { phase: 'fresh' })
  const stale = visual.computeVisual(makePresence(), { phase: 'degraded' })
  assert.ok(stale.stale === true)
  assert.ok(stale.particles[0].alpha < fresh.particles[0].alpha)
  assert.ok(stale.intervalMs >= 2000)
})

test('V3 §73 wake 12fps / steady 8fps equivalent cadence', () => {
  const v = visual.computeVisual(makePresence(), {})
  assert.strictEqual(v.intervalMs, 125) // steady ≈ 8fps；wake 前 4s 由页面 83ms 驱动
})

// ------------------------------------------------------------------ accel summary

console.log('# accel summary')

test('movementClass thresholds mirror neutral classification', () => {
  assert.strictEqual(accel.classifyMotion(null), 'UNKNOWN')
  assert.strictEqual(accel.classifyMotion(0.1), 'STATIONARY')
  assert.strictEqual(accel.classifyMotion(0.5), 'LOW')
  assert.strictEqual(accel.classifyMotion(1.0), 'WALKING')
  assert.strictEqual(accel.classifyMotion(2.0), 'VIGOROUS')
})

test('foreground window flush at WINDOW_MS (contract \u00a78: 5-15s)', () => {
  let t = 0
  const windows = []
  let callback = null
  const fakeSensor = {
    subscribeAccelerometer(opts) { callback = opts.callback },
    unsubscribeAccelerometer() { callback = null },
  }
  const a = accel.create(() => t)
  assert.strictEqual(a.start({ sensor: fakeSensor, onWindow: (w) => windows.push(w) }), true)
  const sample = () => callback({ x: 0, y: 0, z: 10.81 }) // |magnitude - G| = 1.0
  for (let i = 0; i < 49; i++) { t += 200; sample() } // t=200..9800
  assert.strictEqual(windows.length, 0) // \u7a97\u53e3\u672a\u6ee1\u4e0d\u4e0a\u62a5
  t += 200
  sample() // t=10000 \u2192 \u7a97\u53e3\u6ee1 10s \u2192 flush \u5e76\u5f00\u65b0\u7a97
  assert.strictEqual(windows.length, 1)
  const w0 = windows[0]
  assert.strictEqual(w0.windowStartMs, 0)
  assert.strictEqual(w0.windowEndMs, 10000)
  const span0 = w0.windowEndMs - w0.windowStartMs
  assert.ok(span0 >= 5000 && span0 <= 15000) // \u5951\u7ea6 5-15s \u533a\u95f4
  assert.ok(w0.motionEnergy !== null && w0.motionEnergy > 0) // observation \u975e\u96f6
  assert.strictEqual(w0.movementClass, 'WALKING')
  assert.strictEqual(w0.quality, 'GOOD') // coverage = 49*200/10000 = 0.98
  for (let i = 0; i < 49; i++) { t += 200; sample() } // t=10200..19800
  assert.strictEqual(windows.length, 1) // \u7b2c\u4e8c\u7a97\u672a\u6ee1\u4e0d\u4e0a\u62a5
  t += 200
  sample() // t=20000 \u2192 \u7b2c\u4e8c\u7a97 flush
  assert.strictEqual(windows.length, 2)
  assert.strictEqual(windows[1].windowStartMs, 10000)
  assert.ok(windows[1].motionEnergy > 0)
  for (let i = 0; i < 5; i++) { t += 200; sample() } // t=20200..21000
  a.stop() // onHide \u2192 \u4f59\u7a97 flush
  assert.strictEqual(windows.length, 3)
  assert.strictEqual(windows[2].windowStartMs, 20000)
  assert.strictEqual(windows[2].windowEndMs, 21000)
  assert.ok(windows[2].motionEnergy > 0)
  assert.strictEqual(callback, null) // \u9000\u8ba2\u4f20\u611f\u5668
})

test('module exports page-level start/stop (echo/index.ux call shape)', () => {
  assert.strictEqual(typeof accel.start, 'function')
  assert.strictEqual(typeof accel.stop, 'function')
  assert.strictEqual(typeof accel.create, 'function')
})

// ------------------------------------------------------------------ manifest capability closure (Phase 4)

console.log('# declared vela features (MINIMUM CAPABILITY DECLARATION)')

declaredFeatures.run(test)

// ------------------------------------------------------------------ summary

console.log('\n' + passed + ' passed, ' + failed + ' failed')
process.exit(failed === 0 ? 0 : 1)
