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

test('particle budget small (few shapes, no particle engine)', () => {
  const v = visual.computeVisual(makePresence(), {})
  assert.ok(v.particles.length <= 6)
  assert.ok(v.textureSteps <= 6)
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

// ------------------------------------------------------------------ summary

console.log('\n' + passed + ' passed, ' + failed + ' failed')
process.exit(failed === 0 ? 0 : 1)
