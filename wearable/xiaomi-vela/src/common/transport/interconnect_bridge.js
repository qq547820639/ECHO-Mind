/**
 * Interconnect Bridge —— system.interconnect 封装（官方公开能力）。
 *
 * 官方事实（XIAOMI_BAND10_CAPABILITY_MATRIX §1.3）：
 * - 连接由系统自动建立/维持，应用内不管理连接生命周期；
 * - connect.send({ data: Object }) 发对象（手机端收到其 JSON 文本）；
 * - connect.onmessage 收到手机端文本（data.data 为字符串）；
 * - getReadyState: 1=连接成功 2=断开；onopen(isReconnected) / onclose / onerror。
 *
 * 环境：CommonJS；在 Node 测试中注入 mock 的 interconnect 对象（不 require 系统模块）。
 */
var protocol = require('../protocol/wear_protocol.js')

var instance = null

function getInterconnect() {
  if (instance) return instance
  try {
    // Vela 运行时
    var interconnect = require('@system.interconnect')
    instance = interconnect.instance()
  } catch (e) {
    // 非 Vela 环境（Node 测试）：mock 由 tests 注入
    instance = null
  }
  return instance
}

var handlers = null

/** 发送 JSON 对象（interconnect 官方 send(Object) 约定）。 */
function sendObject(obj) {
  var connect = getInterconnect()
  if (!connect) return false
  try {
    connect.send({
      data: obj,
      success: function () {},
      fail: function (data, code) {
        if (handlers && handlers.onTransportError) {
          handlers.onTransportError('send fail code=' + code)
        }
      },
    })
    return true
  } catch (e) {
    if (handlers && handlers.onTransportError) handlers.onTransportError('send exception')
    return false
  }
}

function sendAction(command) {
  return sendObject(protocol.encodeAction(command))
}

function sendAck(ackFor, revision, status) {
  return sendObject(protocol.encodeAck(ackFor, revision, status))
}

function sendObservation(summary, deviceState) {
  return sendObject(protocol.encodeObservation(summary, deviceState))
}

function sendCapability(capabilities, screenWidth, screenHeight) {
  return sendObject(protocol.encodeCapability(capabilities, screenWidth, screenHeight))
}

function getReadyState() {
  var connect = getInterconnect()
  if (!connect) return 2 // 未连接（无法确认）
  var ready = 2
  try {
    connect.getReadyState({
      success: function (data) {
        ready = data && data.status === 1 ? 1 : 2
      },
      fail: function () {
        ready = 2
      },
    })
  } catch (e) {
    ready = 2
  }
  return ready
}

/**
 * 初始化：注册回调。
 * handlers: { onMessage(text), onConnectionChanged(isReconnected), onTransportError(msg) }
 */
function init(h) {
  handlers = h
  var connect = getInterconnect()
  if (!connect) return
  connect.onmessage = function (data) {
    var text = null
    if (typeof data === 'string') {
      text = data
    } else if (data && typeof data.data === 'string') {
      text = data.data
    }
    if (text !== null && handlers && handlers.onMessage) {
      handlers.onMessage(text)
    }
  }
  connect.onopen = function (data) {
    if (handlers && handlers.onConnectionChanged) {
      handlers.onConnectionChanged(data && data.isReconnected === true)
    }
  }
  connect.onclose = function () {
    if (handlers && handlers.onConnectionChanged) {
      handlers.onConnectionChanged(false)
    }
  }
}

module.exports = {
  init: init,
  sendAction: sendAction,
  sendAck: sendAck,
  sendObservation: sendObservation,
  sendCapability: sendCapability,
  getReadyState: getReadyState,
  _setInterconnectForTest: function (mock) {
    instance = mock
  },
}
