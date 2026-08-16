/**
 * generate.js —— Band10 静态模拟器（QA mirror，Phase 10 渲染级验收）。
 *
 * 纪律：
 * - 视觉参数全部由**真实生产模块**计算（wear_visual.computeVisual /
 *   presence_cache 降级逻辑 / wear_protocol 解码），本文件只做 HTML 摆位；
 * - 摆位公式逐行镜像 .ux（echo/why/action 页的几何与配色），
 *   是 QA mirror，不是第二套 renderer；
 * - 212 × 520 官方尺寸；每状态每语言一个 HTML；
 * - 产物目录 out/ 不入库（.gitignore 已排除）。
 *
 * 用法：
 *   node tests/simulator/generate.js            # 生成 tests/simulator/out/*.html
 *   然后 headless Chrome 截屏：
 *   for f in tests/simulator/out/zh/*.html; do
 *     "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
 *       --headless --disable-gpu --screenshot="$(echo $f | sed 's/html$/png/')" \
 *       --window-size=212,520 --hide-scrollbars "file://$PWD/$f"
 *   done
 */
const fs = require('fs')
const path = require('path')

const visual = require('../../src/common/visual/wear_visual.js')
const cache = require('../../src/common/presence/presence_cache.js')
const storage = require('../../src/common/cache/storage_wrap.js')

const OUT = path.join(__dirname, 'out')
const I18N = {
  zh: JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'src', 'i18n', 'zh.json'), 'utf8')).message,
  en: JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'src', 'i18n', 'en.json'), 'utf8')).message,
}

// Kotlin WearPresenceProjectorTest 同款 fixture（双端一致）。
const IDENTITY = { topology: 0.5, symmetry: 0.5, orbit: 0.5, motion: 0.5, texture: 1, colorFamily: 2, accent: 0.6 }
const MOMENT = { flow: 0.5, coherence: 0.7, density: 0.4, turbulence: 0.2, brightness: 0.6 }

// 手机 WearablePrivacyProjector.HeadlineAllowlist 同款（PUBLIC_SAFE 白名单）。
const ALLOWLIST = {
  SEED: '初见。',
  DISCOVERING: '我开始看到一些属于你的节奏。',
  KNOWN: '我开始认识通常的你了。',
  QUIET: '今天很安静。',
}
const ALLOWLIST_EN = {
  SEED: 'First sight.',
  DISCOVERING: 'I am beginning to see your rhythms.',
  KNOWN: 'I am getting to know the usual you.',
  QUIET: 'Today is a quiet one.',
}

function presence(maturity, opts) {
  opts = opts || {}
  return {
    type: 'presence',
    schemaVersion: 1,
    messageId: 'sim-' + maturity,
    generatedAt: 1000,
    source: 'PHONE',
    revision: 1,
    expiresAt: 1_000 + 15 * 60 * 1000,
    maturity: maturity,
    identity: Object.assign({}, IDENTITY, opts.identity || {}),
    moment: Object.assign({}, MOMENT, opts.moment || {}),
    surface: Object.assign(
      { motionLevel: 'DEFAULT', lowPower: false, reducedMotion: false, motionSummaryEnabled: false, hapticsEnabled: false },
      opts.surface || {}
    ),
    publicHeadline: opts.headline === undefined ? null : opts.headline,
    availableActions: ['START_BREATHING', 'START_PAUSE'],
  }
}

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// ------------------------------------------------------------------ echo 页镜像（echo/index.ux 同几何）

function echoPageHtml(env, phase, headlineText, hintText, lang) {
  const t = I18N[lang]
  const timeText = '21:07' // 静态快照（.ux 用真实时钟；动画节奏见代码 timer）
  const orbitRotate = 40 // 静态快照（.ux 每 tick 旋转 3°）
  const glowOpacity = 0.16 // 静态快照（.ux 0.12–0.20 呼吸）
  // EMPTY 分支镜像 echo/index.ux 的中性默认（不调用 computeVisual——与生产 .ux 一致）。
  const v = phase === 'empty'
    ? { coreRadius: 22, orbitRadius: 70, glowRadius: 44, orbitAlpha: 0.4, coreAlpha: 0.85, accent: 'rgb(190,180,160)', particles: [] }
    : visual.computeVisual(env, { phase: phase })
  // 212×520 布局门：任何元素超出 organism 容器即 FAIL（QA mirror 与 .ux 同几何）。
  for (const key of ['coreRadius', 'orbitRadius', 'glowRadius']) {
    if (v[key] * 2 > 212) throw new Error('visual budget overflow: ' + key + ' diameter ' + v[key] * 2 + ' > 212')
  }
  let particles = ''
  for (const p of v.particles) {
    const x = 106 + p.dx - p.radius
    const y = 106 + p.dy - p.radius
    if (x < 0 || y < 0 || x + p.radius * 2 > 212 || y + p.radius * 2 > 212) {
      throw new Error('particle out of 212×212 organism: x=' + x + ' y=' + y + ' r=' + p.radius)
    }
    particles += `<div class="particle" style="left:${x}px;top:${y}px;width:${p.radius * 2}px;height:${p.radius * 2}px;background-color:${v.accent};opacity:${p.alpha};"></div>`
  }
  const headlineHtml = headlineText !== null && headlineText !== undefined
    ? `<div class="headline-text">${esc(headlineText)}</div>`
    : `<div class="hint-text">${esc(hintText)}</div>`
  return `
  <div class="page">
    <div class="time-area">
      <div class="time-text">${timeText}</div>
      ${headlineHtml}
    </div>
    <div class="organism">
      <div class="orbit-ring" style="width:${v.orbitRadius * 2}px;height:${v.orbitRadius * 2}px;opacity:${v.orbitAlpha};border-color:${v.accent};transform:translate(-50%,-50%) rotate(${orbitRotate}deg);"></div>
      <div class="glow" style="width:${v.glowRadius * 2}px;height:${v.glowRadius * 2}px;background-color:${v.accent};opacity:${glowOpacity};"></div>
      <div class="core" style="width:${v.coreRadius * 2}px;height:${v.coreRadius * 2}px;background-color:${v.accent};opacity:${v.coreAlpha.toFixed(2)};"></div>
      ${particles}
    </div>
    <div class="action-entry"><span class="action-entry-text">${esc(t.action_title)}</span></div>
  </div>`
}

// ------------------------------------------------------------------ action / why 页镜像

function actionPageHtml(env, mode, lang) {
  const t = I18N[lang]
  const v = visual.computeVisual(env, {})
  const coreSize = v.coreRadius * 2
  if (mode === 'BREATHING') {
    return `
  <div class="page">
    <div class="title-row"><span class="title-text">${esc(t.action_title)}</span></div>
    <div class="body-area">
      <div class="breathing-field">
        <div class="breath-core" style="width:${coreSize}px;height:${coreSize}px;background-color:${v.accent};"></div>
        <span class="body-text">${esc(t.action_breathing_hint)}</span>
        <div class="stop-button"><span class="stop-text">${esc(t.action_stop)}</span></div>
      </div>
    </div>
  </div>`
  }
  if (mode === 'PAUSE') {
    return `
  <div class="page">
    <div class="title-row"><span class="title-text">${esc(t.action_title)}</span></div>
    <div class="body-area">
      <div class="pause-field">
        <span class="body-text">${esc(t.action_pause_hint)}</span>
        <div class="stop-button"><span class="stop-text">${esc(t.action_stop)}</span></div>
      </div>
    </div>
  </div>`
  }
  // menu（默认态）
  return `
  <div class="page">
    <div class="title-row"><span class="title-text">${esc(t.action_title)}</span></div>
    <div class="body-area">
      <div class="menu-field">
        <div class="menu-button"><span class="menu-text">${esc(t.action_breathing)}</span></div>
        <div class="menu-button"><span class="menu-text">${esc(t.action_pause)}</span></div>
      </div>
    </div>
  </div>`
}

function whyPageHtml(headlineText, degraded, lang) {
  const t = I18N[lang]
  const body = headlineText
    ? `<span class="body-text">${esc(headlineText)}</span>`
    : degraded
      ? `<span class="body-text">${esc(t.why_no_connection)}</span>`
      : `<span class="body-text">${esc(t.why_fallback)}</span>`
  return `
  <div class="page">
    <div class="title-row"><span class="title-text">${esc(t.why_title)}</span></div>
    <div class="body-area">${body}</div>
    <div class="back-hint"><span class="back-hint-text">⌄</span></div>
  </div>`
}

// ------------------------------------------------------------------ 样式（.ux CSS 逐行镜像）

const CSS = `
* { margin: 0; padding: 0; box-sizing: border-box; }
body { background: #1a1a1a; }
.frame { width: 212px; height: 520px; background: #000; position: relative; overflow: hidden; font-family: -apple-system, "PingFang SC", sans-serif; }
.page { display: flex; flex-direction: column; align-items: center; background-color: #000; width: 100%; height: 100%; position: relative; }
.time-area { display: flex; flex-direction: column; align-items: center; margin-top: 60px; }
.time-text { color: #f5f5f0; font-size: 44px; font-weight: 300; }
.headline-text { color: #b8b8ae; font-size: 15px; margin-top: 14px; text-align: center; max-width: 196px; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.hint-text { color: #6a6a62; font-size: 13px; margin-top: 14px; }
.organism { position: relative; width: 212px; height: 212px; margin-top: 40px; }
.orbit-ring { position: absolute; left: 50%; top: 50%; border-radius: 50%; border: 1px solid; }
.glow { position: absolute; left: 50%; top: 50%; border-radius: 50%; transform: translate(-50%, -50%); }
.core { position: absolute; left: 50%; top: 50%; border-radius: 50%; transform: translate(-50%, -50%); }
.particle { position: absolute; border-radius: 50%; }
.action-entry { position: absolute; bottom: 40px; left: 50%; transform: translateX(-50%); border: 1px solid #3c3c36; border-radius: 24px; padding: 10px 24px; }
.action-entry-text { color: #8a8a80; font-size: 15px; }
.title-row { margin-top: 56px; }
.title-text { color: #6a6a62; font-size: 13px; letter-spacing: 4px; }
.body-area { flex: 1; display: flex; align-items: center; justify-content: center; flex-direction: column; }
.breathing-field, .pause-field, .menu-field { display: flex; flex-direction: column; align-items: center; }
.breath-core { border-radius: 50%; margin-bottom: 28px; opacity: 0.7; }
.body-text { color: #e8e8e0; font-size: 17px; text-align: center; line-height: 1.6; max-width: 180px; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; }
.why .body-text { font-size: 20px; -webkit-line-clamp: 4; max-width: 156px; }
.body-area { padding-left: 28px; padding-right: 28px; }
.menu-button { width: 180px; border: 1px solid #3c3c36; border-radius: 28px; padding: 18px 0; margin-bottom: 22px; display: flex; align-items: center; justify-content: center; }
.menu-text { color: #e0e0d8; font-size: 18px; }
.stop-button { margin-top: 30px; border: 1px solid #3c3c36; border-radius: 24px; padding: 10px 26px; }
.stop-text { color: #8a8a80; font-size: 15px; }
.back-hint { margin-bottom: 36px; }
.back-hint-text { color: #3c3c36; font-size: 18px; }
.caption { color: #777; font-size: 11px; font-family: monospace; padding: 6px 0 20px; width: 212px; }
`

function page(inner, caption) {
  // LAYOUT 探针：headless Chrome --dump-dom 时输出文本是否被 clamp/溢出
  // （scrollHeight > clientHeight ⇒ 文本被截断）。QA 验收工具，非腕上运行时逻辑。
  const probe = `<script>
window.addEventListener('DOMContentLoaded', function () {
  var out = [];
  document.querySelectorAll('.headline-text, .body-text, .time-text').forEach(function (el) {
    var r = el.getBoundingClientRect();
    out.push({ c: el.className, w: Math.round(r.width), ch: el.clientHeight, sh: el.scrollHeight, clamp: el.scrollHeight > el.clientHeight, x2: Math.round(r.right) });
  });
  document.title = 'LAYOUT:' + JSON.stringify(out);
});
</script>`
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><style>${CSS}</style>${probe}</head>
<body><div class="frame">${inner}</div><div class="caption">${esc(caption)}</div></body></html>`
}

// ------------------------------------------------------------------ 状态构建

function buildStates() {
  const states = []
  const push = (id, name, html, caption) => states.push({ id, name, html, caption })

  for (const lang of ['zh', 'en']) {
    const t = I18N[lang]
    const H = lang === 'zh' ? ALLOWLIST : ALLOWLIST_EN
    const base = {
      SEED: () => presence('SEED', { headline: H.SEED }),
      DISCOVERING: () => presence('DISCOVERING', { headline: H.DISCOVERING }),
      KNOWN: () => presence('KNOWN', { headline: H.KNOWN }),
      MATURE: () => presence('MATURE', { headline: H.KNOWN }),
      QUIET: () => presence('KNOWN', { identity: { motion: 0.1 }, headline: H.QUIET }),
      LOW_CONFIDENCE: () => presence('KNOWN', { moment: { flow: 0.1, coherence: 0.2, density: 0.15, turbulence: 0.3, brightness: 0.25 } }),
    }
    for (const key of Object.keys(base)) {
      const env = base[key]()
      push(`${lang}_${key}`, key, echoPageHtml(env, 'fresh', env.publicHeadline, t.echo_tap_hint, lang),
        `${lang} · ${key} · motion=${env.surface.motionLevel} · haptics=${env.surface.hapticsEnabled ? 'ON' : 'SILENT'} · ${visual.computeVisual(env, {}).accent}`)
    }
    // DISCONNECTED：degraded（真实 presence_cache 降级逻辑）
    storage._resetForTest()
    const c = cache.create(() => 0)
    c.apply(presence('KNOWN', { headline: H.KNOWN }), 0)
    const degraded = c.state(0 + cache.PRESENCE_TTL_MS + 60 * 1000)
    push(`${lang}_DISCONNECTED`, 'DISCONNECTED',
      echoPageHtml(degraded.envelope, 'degraded', null, t.echo_tap_hint, lang),
      `${lang} · DISCONNECTED(degraded) · Identity 保留 / Moment→QUIET / 无 headline / haptics=${degraded.envelope.surface.hapticsEnabled ? 'ON' : 'SILENT'}`)
    // EMPTY（无缓存，首装）
    const emptyEnv = presence('KNOWN')
    push(`${lang}_EMPTY`, 'EMPTY',
      echoPageHtml(emptyEnv, 'empty', t.why_fallback, null, lang),
      `${lang} · EMPTY(no cache) · 中性 organism + 降级文案（无红色 ERROR）`)
    // BREATHING / PAUSE / ACTION menu
    push(`${lang}_BREATHING`, 'BREATHING', actionPageHtml(presence('KNOWN'), 'BREATHING', lang),
      `${lang} · BREATHING（同一手机 Action；振动受 surface.hapticsEnabled 硬门）`)
    push(`${lang}_PAUSE`, 'PAUSE', actionPageHtml(presence('KNOWN'), 'PAUSE', lang),
      `${lang} · PAUSE（同一手机 Action）`)
    push(`${lang}_ACTION_MENU`, 'ACTION_MENU', actionPageHtml(presence('KNOWN'), 'MENU', lang),
      `${lang} · ACTION menu（180×56px 触摸目标）`)
    // WHY
    push(`${lang}_WHY`, 'WHY', whyPageHtml(H.KNOWN, false, lang),
      `${lang} · WHY（headline 来自手机 PUBLIC_SAFE 白名单；20px ≤4 行）`)
    // haptics ON 诊断态（QA harness：验证表面开关透传）
    const hapticEnv = presence('KNOWN', { surface: { hapticsEnabled: true } })
    push(`${lang}_HAPTICS_ON`, 'HAPTICS_ON', echoPageHtml(hapticEnv, 'fresh', null, t.echo_tap_hint, lang),
      `${lang} · surface.hapticsEnabled=true（Me 开启后；腕上 vibrate 才放行）`)
  }
  return states
}

function main() {
  fs.rmSync(OUT, { recursive: true, force: true })
  const states = buildStates()
  for (const s of states) {
    const dir = path.join(OUT, s.id.split('_')[0])
    fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(path.join(dir, s.id.split('_').slice(1).join('_') + '.html'), page(s.html, s.caption))
  }
  fs.writeFileSync(path.join(OUT, 'index.html'), `<!DOCTYPE html><html><head><meta charset="utf-8"><title>ECHO Wrist Band10 Simulator</title></head>
<body style="background:#111;color:#ccc;font-family:monospace;padding:20px"><h1>ECHO Wrist — Band10 Simulator（QA mirror）</h1>
<ul>${states.map((s) => `<li><a href="${s.id.split('_')[0]}/${s.id.split('_').slice(1).join('_')}.html">${s.id} — ${s.name}</a></li>`).join('')}</ul>
</body></html>`)
  console.log('generated %d states → %s', states.length, OUT)
}

main()
