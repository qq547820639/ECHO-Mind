/**
 * haptic_throttle.js —— 触觉速率限制（ECHO_WRIST_CONTRACT §7 触觉宪法）。
 *
 * 契约原文：显式触觉（tap confirmation / user-started breathing cadence /
 * action completion confirmation）「速率限制 ≥1.5s」——两次振动间隔 < 1500ms 时，
 * 第二次必须 no-op（快速「呼吸→结束」不得连续振动）。
 *
 * 与 surface.hapticsEnabled 硬门正交：硬门先判（SILENT 一切 no-op），
 * 节流后判（频率）；short/long 共用同一扇门（页面模块级单例）。
 *
 * 纯时间逻辑（无 @system 能力依赖），tests/run.js 行为测试锁定。
 */

/** §7 契约值：最小振动间隔（毫秒）。 */
const MIN_INTERVAL_MS = 1500

/**
 * 创建节流门。
 * @param {function(): number} now 时钟注入（测试可控；生产传 () => Date.now()）
 * @returns {{allow: (nowMs?: number) => boolean}} allow=true 放行本次振动；false 必须 no-op
 */
function create(now) {
  let lastAt = null
  return {
    allow(nowMs) {
      const t = typeof nowMs === 'number' ? nowMs : now()
      if (lastAt !== null && t - lastAt < MIN_INTERVAL_MS) return false
      lastAt = t
      return true
    },
  }
}

module.exports = { MIN_INTERVAL_MS, create }
