/**
 * Storage wrap —— 持久化包装（system.storage + 内存兜底）。
 *
 * 手环只允许缓存：current PUBLIC_SAFE Presence（ECHO_WRIST_CONTRACT §Memory Invariant）。
 * 禁止在腕上保存 Memory / SelfModel / Journey / 私密文本 / API Key / 长期个人数据。
 *
 * Node 测试环境：内存实现。
 */
var memoryStore = {}

function getStorage() {
  try {
    return require('@system.storage')
  } catch (e) {
    return null
  }
}

function get(key, fallback) {
  var storage = getStorage()
  if (storage) {
    try {
      var value = storage.getSync ? storage.getSync(key) : null
      if (value !== null && value !== undefined && value !== '') {
        return value
      }
    } catch (e) {
      // 存储失败 → 内存兜底
    }
  }
  if (memoryStore[key] !== undefined) return memoryStore[key]
  return fallback === undefined ? null : fallback
}

function set(key, value) {
  var storage = getStorage()
  if (storage) {
    try {
      if (storage.setSync) storage.setSync(key, value)
    } catch (e) {
      // 忽略（内存兜底）
    }
  }
  memoryStore[key] = value
}

function remove(key) {
  var storage = getStorage()
  if (storage) {
    try {
      if (storage.deleteSync) storage.deleteSync(key)
    } catch (e) {
      // 忽略
    }
  }
  delete memoryStore[key]
}

/** 键：当前 PUBLIC_SAFE Presence 缓存（唯一允许的腕上持久化）。 */
var PRESENCE_CACHE_KEY = 'echo_presence_v1'

module.exports = {
  PRESENCE_CACHE_KEY: PRESENCE_CACHE_KEY,
  get: get,
  set: set,
  remove: remove,
  _resetForTest: function () {
    memoryStore = {}
  },
}
