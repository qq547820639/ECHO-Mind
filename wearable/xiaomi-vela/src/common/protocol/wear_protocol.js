/**
 * Wear Protocol v1 —— 手环端编解码（与手机 Kotlin WearMessageCodec 逐字段一致）。
 *
 * 协议要求：small / versioned / forward compatible / idempotent /
 * out-of-order safe / duplicate safe。
 *
 * 与手机端约定：
 * - 未知字段忽略（forward compatible）；
 * - schemaVersion > 当前支持 → { ok:false, unsupportedSchema }；
 * - malformed JSON → { ok:false, malformed }；
 * - revision 检查在 presence_cache 中执行（协议层只负责解析）。
 *
 * 环境：CommonJS（Vela require 与 Node 测试均可直接加载）；ES5 风格，无外部依赖。
 */
var WEAR_SCHEMA_V1 = 1
var SOURCE_PHONE = 'PHONE'
var SOURCE_BAND = 'XIAOMI_BAND'

var messageCounter = 0

function makeMessageId(prefix) {
  messageCounter += 1
  return String(prefix) + '-' + Date.now() + '-' + messageCounter
}

function isNumber(v) {
  return typeof v === 'number' && isFinite(v)
}

function isString(v) {
  return typeof v === 'string'
}

/** 组装 envelope 公共头。 */
function envelopeBase(type, extra) {
  var out = {
    type: type,
    schemaVersion: WEAR_SCHEMA_V1,
    messageId: extra.messageId || makeMessageId(type),
    generatedAt: isNumber(extra.generatedAt) ? extra.generatedAt : Date.now(),
    source: SOURCE_BAND,
  }
  return out
}

/** 手环 → 手机：动作请求。 */
function encodeAction(command) {
  var msg = envelopeBase('action', {})
  msg.command = command
  return msg
}

/** 手环 → 手机：ACK。 */
function encodeAck(ackFor, revision, status) {
  var msg = envelopeBase('ack', {})
  msg.ackFor = ackFor
  msg.revision = isNumber(revision) ? revision : 0
  msg.status = status || 'OK'
  return msg
}

/** 手环 → 手机：腕上观察（前台加速度计窗口 summary + 设备状态快照）。 */
function encodeObservation(summary, deviceState) {
  var msg = envelopeBase('observation', {})
  if (summary) {
    msg.motion = {
      movementClass: summary.movementClass || 'UNKNOWN',
      sampleCoverage: isNumber(summary.sampleCoverage) ? summary.sampleCoverage : 0,
      quality: summary.quality || 'UNKNOWN',
      windowStartMs: summary.windowStartMs || 0,
      windowEndMs: summary.windowEndMs || 0,
    }
    if (isNumber(summary.motionEnergy)) {
      msg.motion.motionEnergy = summary.motionEnergy
    }
  }
  if (deviceState) {
    msg.deviceState = {
      wearing: deviceState.wearing || 'UNKNOWN',
      sleep: deviceState.sleep || 'UNKNOWN',
    }
    if (isNumber(deviceState.batteryPercent)) {
      msg.deviceState.batteryPercent = deviceState.batteryPercent
    }
    if (typeof deviceState.charging === 'boolean') {
      msg.deviceState.charging = deviceState.charging
    }
  }
  return msg
}

/** 手环 → 手机：能力上报（vendor 未验证项保持 UNKNOWN）。 */
function encodeCapability(capabilities, screenWidth, screenHeight) {
  var msg = envelopeBase('capability', {})
  msg.protocolVersion = WEAR_SCHEMA_V1
  msg.screenWidth = screenWidth || 0
  msg.screenHeight = screenHeight || 0
  msg.capabilities = capabilities || {}
  return msg
}

function readNumber(obj, name) {
  if (obj[name] === undefined || obj[name] === null) return null
  return isNumber(obj[name]) ? obj[name] : null
}

function readString(obj, name) {
  if (obj[name] === undefined || obj[name] === null) return null
  return isString(obj[name]) ? obj[name] : null
}

/**
 * 解析入站 JSON 文本 → 消息对象。
 * 返回 { ok:true, message } | { ok:false, malformed:true } | { ok:false, unsupportedSchema:true }。
 */
function decodeMessage(text) {
  var root
  if (!isString(text)) {
    return { ok: false, malformed: true }
  }
  try {
    root = JSON.parse(text)
  } catch (e) {
    return { ok: false, malformed: true }
  }
  if (!root || typeof root !== 'object') {
    return { ok: false, malformed: true }
  }
  var schema = readNumber(root, 'schemaVersion')
  if (schema === null) {
    return { ok: false, malformed: true }
  }
  if (schema > WEAR_SCHEMA_V1) {
    return { ok: false, unsupportedSchema: true }
  }
  var type = root.type
  if (type === 'presence') {
    var decoded = decodePresence(root)
    if (!decoded) return { ok: false, malformed: true }
    return { ok: true, message: decoded }
  }
  if (type === 'capability') {
    var cap = decodeCapability(root)
    if (!cap) return { ok: false, malformed: true }
    return { ok: true, message: cap }
  }
  // 手环是 BODY：其余类型（action/ack/observation）本端不消费，忽略（forward compatible）。
  // 不标记 malformed：这表示"非本端消息类型"而非"消息格式错误"，调用方应静默跳过。
  return { ok: false }
}

function decodePresence(root) {
  var messageId = readString(root, 'messageId')
  var generatedAt = readNumber(root, 'generatedAt')
  var revision = readNumber(root, 'revision')
  var expiresAt = readNumber(root, 'expiresAt')
  var maturity = readString(root, 'maturity')
  if (messageId === null || generatedAt === null || revision === null || expiresAt === null || maturity === null) {
    return null
  }
  var identity = root.identity
  var moment = root.moment
  var surface = root.surface
  if (!identity || !moment || !surface) {
    return null
  }
  var identityOut = {
    topology: readNumber(identity, 'topology'),
    symmetry: readNumber(identity, 'symmetry'),
    orbit: readNumber(identity, 'orbit'),
    motion: readNumber(identity, 'motion'),
    texture: readNumber(identity, 'texture'),
    colorFamily: readNumber(identity, 'colorFamily'),
    accent: readNumber(identity, 'accent'),
  }
  for (var k in identityOut) {
    if (identityOut[k] === null) return null
  }
  var momentOut = {
    flow: readNumber(moment, 'flow'),
    coherence: readNumber(moment, 'coherence'),
    density: readNumber(moment, 'density'),
    turbulence: readNumber(moment, 'turbulence'),
    brightness: readNumber(moment, 'brightness'),
  }
  for (var m in momentOut) {
    if (momentOut[m] === null) return null
  }
  var surfaceOut = {
    motionLevel: readString(surface, 'motionLevel') || 'DEFAULT',
    lowPower: surface.lowPower === true,
    reducedMotion: surface.reducedMotion === true,
    motionSummaryEnabled: surface.motionSummaryEnabled === true,
    // 触觉开关：手机 Me → envelope.surface（默认 false = SILENT）。
    // false 时腕上 vibrateShort/vibrateLong 必须全部 no-op。
    hapticsEnabled: surface.hapticsEnabled === true,
  }
  var headline = readString(root, 'publicHeadline')
  var actions = Array.isArray(root.availableActions) ? root.availableActions.filter(isString) : []
  return {
    type: 'presence',
    schemaVersion: WEAR_SCHEMA_V1,
    messageId: messageId,
    generatedAt: generatedAt,
    source: readString(root, 'source') || SOURCE_PHONE,
    revision: revision,
    expiresAt: expiresAt,
    maturity: maturity,
    identity: identityOut,
    moment: momentOut,
    surface: surfaceOut,
    publicHeadline: headline,
    availableActions: actions,
  }
}

function decodeCapability(root) {
  var messageId = readString(root, 'messageId')
  if (messageId === null) return null
  return {
    type: 'capability',
    messageId: messageId,
    protocolVersion: readNumber(root, 'protocolVersion') || WEAR_SCHEMA_V1,
    screenWidth: readNumber(root, 'screenWidth') || 0,
    screenHeight: readNumber(root, 'screenHeight') || 0,
    capabilities: root.capabilities && typeof root.capabilities === 'object' ? root.capabilities : {},
  }
}

module.exports = {
  WEAR_SCHEMA_V1: WEAR_SCHEMA_V1,
  SOURCE_PHONE: SOURCE_PHONE,
  SOURCE_BAND: SOURCE_BAND,
  makeMessageId: makeMessageId,
  encodeAction: encodeAction,
  encodeAck: encodeAck,
  encodeObservation: encodeObservation,
  encodeCapability: encodeCapability,
  decodeMessage: decodeMessage,
}
