/**
 * Wear Visual —— 腕上渲染参数（确定性纯函数，SAME ECHO）。
 *
 * Vela Renderer 原则（ECHO_WRIST_CONTRACT §Vela Renderer）：
 * - 不复制完整 Phone renderer；
 * - 共享：topology family / symmetry / orbit / motion personality /
 *   texture family / color family / maturity；
 * - 手环专用：few shapes / opacity / scale / translation / simple orbit /
 *   simple gradients / slow organic movement；
 * - 目标：像生命体，不是科技 HUD，不是 music visualizer。
 * - Battery first：idle 明显降低更新；transition 短暂更高刷新。
 *
 * 输出为低维参数（供 .ux 模板绑定），无高频 timer、无大对象分配。
 */
var HEADLINE_NEUTRAL_FALLBACK = 'ECHO'

/** 确定性哈希（identity 浮点 → 伪随机序列；手机不发送 seed，本端不持有 seed）。 */
function hashIdentity(identity) {
  var s =
    (identity.topology || 0) * 1000000 +
    '|' + (identity.symmetry || 0) * 1000000 +
    '|' + (identity.orbit || 0) * 1000000 +
    '|' + (identity.motion || 0) * 1000000 +
    '|' + (identity.texture || 0) +
    '|' + (identity.colorFamily || 0) +
    '|' + (identity.accent || 0) * 1000000
  var h = 2166136261
  for (var i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = (h * 16777619) >>> 0
  }
  return h >>> 0
}

function hsvToRgb(h, s, v) {
  var i = Math.floor(h * 6)
  var f = h * 6 - i
  var p = v * (1 - s)
  var q = v * (1 - f * s)
  var t = v * (1 - (1 - f) * s)
  var r, g, b
  switch (i % 6) {
    case 0: r = v; g = t; b = p; break
    case 1: r = q; g = v; b = p; break
    case 2: r = p; g = v; b = t; break
    case 3: r = p; g = q; b = v; break
    case 4: r = t; g = p; b = v; break
    default: r = v; g = p; b = q; break
  }
  return [Math.round(r * 255), Math.round(g * 255), Math.round(b * 255)]
}

function clamp01(v) {
  if (typeof v !== 'number' || !isFinite(v)) return 0
  return v < 0 ? 0 : v > 1 ? 1 : v
}

/**
 * 核心渲染参数（确定性；同一 envelope 同一时刻输出相同）。
 * 输入：presence envelope + 是否处于 BREATHING 动作。
 */
function computeVisual(envelope, options) {
  options = options || {}
  var identity = envelope.identity
  var moment = envelope.moment
  var surface = envelope.surface
  var hash = hashIdentity(identity)
  var rand1 = ((hash >>> 8) % 1000) / 1000
  var rand2 = ((hash >>> 16) % 1000) / 1000

  var accentRgb = hsvToRgb(clamp01(identity.accent), 0.55, 0.9)

  // topology：核心是否高度对称（对称 → 核心更圆更集中）。
  var symmetry = clamp01(identity.symmetry)
  // orbit：0 = 环状，1 = 弥散。
  var orbit = clamp01(identity.orbit)
  // motion personality → 动画节奏（手环表面：慢速有机运动）。
  var motion = clamp01(identity.motion)

  var coreRadius = 26 + 18 * symmetry // 设计基准 212 宽下的逻辑值（px 按 designWidth 缩放）
  var orbitRadius = 52 + 26 * orbit + 10 * (1 - moment.coherence)
  var orbitAlpha = 0.25 + 0.5 * moment.coherence
  var glowRadius = coreRadius * (1.4 + 0.8 * moment.brightness)
  var textureSteps = 3 + Math.min(3, Math.round(clamp01(identity.texture / 3) * 3)) // 纹理族 → 环层数（3-6，few shapes）
  var turbulenceShift = (moment.turbulence - 0.5) * 10

  var particles = []
  var particleCount = Math.min(6, 2 + Math.round(moment.density * 4))
  for (var i = 0; i < particleCount; i++) {
    var ang = (rand1 + i / particleCount) * Math.PI * 2
    var dist = (0.55 + 0.45 * ((rand2 + i * 0.37) % 1)) * orbitRadius
    particles.push({
      dx: Math.round(Math.cos(ang) * dist),
      dy: Math.round(Math.sin(ang) * dist * 0.92), // 狭长屏：纵向略压缩
      alpha: 0.2 + 0.4 * moment.flow,
      radius: 3 + Math.round(2 * moment.brightness),
    })
  }

  var intervalMs
  if (surface.lowPower || surface.reducedMotion) {
    intervalMs = 2000 // idle：明显降低更新
  } else if (surface.motionLevel === 'LIVELY') {
    intervalMs = 500
  } else if (surface.motionLevel === 'QUIET') {
    intervalMs = 1500
  } else {
    intervalMs = 800
  }

  return {
    phase: options.phase || 'fresh',
    maturity: envelope.maturity,
    motionLevel: surface.motionLevel,
    coreRadius: Math.round(coreRadius),
    orbitRadius: Math.round(orbitRadius),
    glowRadius: Math.round(glowRadius),
    orbitAlpha: Math.round(orbitAlpha * 100) / 100,
    coreAlpha: 0.75 + 0.25 * moment.flow,
    accent: 'rgb(' + accentRgb[0] + ',' + accentRgb[1] + ',' + accentRgb[2] + ')',
    textureSteps: textureSteps,
    turbulenceShift: turbulenceShift,
    particles: particles,
    intervalMs: intervalMs,
    headline: envelope.publicHeadline || null,
    breathing: options.breathing === true,
  }
}

module.exports = {
  computeVisual: computeVisual,
  hashIdentity: hashIdentity,
  hsvToRgb: hsvToRgb,
  clamp01: clamp01,
  HEADLINE_NEUTRAL_FALLBACK: HEADLINE_NEUTRAL_FALLBACK,
}
