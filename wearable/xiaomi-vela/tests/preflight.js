/**
 * preflight.js —— AIoT-IDE 打包前静态预检（Phase 5 packaging preflight）。
 *
 * 不伪造 RPK 构建：本工具只做一切可在无 proprietary 工具链环境完成的静态验证。
 * 只有 AIoT-IDE 真实打包产生的 dist/*.debug.rpk 才能宣称 RPK_BUILD=PASS。
 *
 * 检查项：
 * 1. manifest.json 可解析 + 必填字段（package/icon/version/features/router/deviceTypeList）；
 * 2. router 页面 ↔ pages/<name>/<component>.ux 文件存在；entry 指向 echo（ECHO first）；
 * 3. icon 与 manifest 引用的资源路径存在；
 * 4. 所有 .js 通过 node --check；所有 .ux 的 <script> 块通过语法检查；
 * 5. i18n：zh/en/defaults 三份存在且 key 集合一致；页面引用的 $t('message.x') 全部存在；
 * 6. 能力声明纪律（交由 declared_features_test.js 验证，本文件只复核调用）；
 * 7. Band10 设计基准：designWidth=212；无绝对宽度溢出硬编码（静态扫描 >212px 的 width 字面量）。
 *
 * 运行：node tests/preflight.js [--src <path>]
 * 退出码：0=预检通过（不代表 RPK 已构建）；1=预检失败。
 */
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')
const os = require('os')

const SRC_ROOT = path.join(__dirname, '..', 'src')
const failures = []

function check(name, fn) {
  try {
    fn()
    console.log('  ok  ' + name)
  } catch (e) {
    failures.push(name + ': ' + e.message)
    console.error('FAIL  ' + name + '\n      ' + e.message)
  }
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'))
}

function collectFiles(dir, extRe) {
  const out = []
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) out.push(...collectFiles(p, extRe))
    else if (extRe.test(e.name)) out.push(p)
  }
  return out
}

function syntaxCheckJs(file) {
  execFileSync(process.execPath, ['--check', file], { stdio: 'pipe' })
}

function syntaxCheckUx(file) {
  // .ux 的 <script> 块：export default → exports.default（CommonJS 语法检查等价体）
  const text = fs.readFileSync(file, 'utf8')
  const blocks = [...text.matchAll(/<script>([\s\S]*?)<\/script>/g)]
  for (const m of blocks) {
    const script = m[1].replace(/export\s+default/g, 'exports.default =')
    const tmp = path.join(os.tmpdir(), 'echo_vela_ux_check_' + path.basename(file) + '.js')
    fs.writeFileSync(tmp, script)
    try {
      execFileSync(process.execPath, ['--check', tmp], { stdio: 'pipe' })
    } finally {
      fs.unlinkSync(tmp)
    }
  }
}

// ------------------------------------------------------------------ 1. manifest

check('manifest parses with required fields', () => {
  const manifest = readJson(path.join(SRC_ROOT, 'manifest.json'))
  for (const key of ['package', 'name', 'icon', 'versionName', 'versionCode', 'features', 'router']) {
    if (manifest[key] === undefined) throw new Error('缺失字段: ' + key)
  }
  if (!/^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+$/.test(manifest.package)) {
    throw new Error('package 非法: ' + manifest.package)
  }
  if (manifest.config.designWidth !== 212) throw new Error('designWidth 必须为 212（Band10 官方）')
  if (!(manifest.deviceTypeList || []).includes('watch')) throw new Error('deviceTypeList 缺 watch')
})

check('router pages map to real .ux files, entry = echo (ECHO first)', () => {
  const manifest = readJson(path.join(SRC_ROOT, 'manifest.json'))
  if (manifest.router.entry !== 'echo') throw new Error('entry 必须为 echo')
  for (const [name, cfg] of Object.entries(manifest.router.pages)) {
    // 官方布局（aiot-toolkit 约定）：页面直接位于 src/<page>/<component>.ux
    const f = path.join(SRC_ROOT, name, cfg.component + '.ux')
    if (!fs.existsSync(f)) throw new Error('页面缺失: ' + f)
  }
})

check('icon and manifest resource paths exist', () => {
  const manifest = readJson(path.join(SRC_ROOT, 'manifest.json'))
  const icon = path.join(SRC_ROOT, manifest.icon.replace(/^\//, ''))
  if (!fs.existsSync(icon)) throw new Error('icon 缺失: ' + icon)
})

// ------------------------------------------------------------------ 2. syntax

check('all .js files pass node --check', () => {
  for (const f of collectFiles(SRC_ROOT, /\.js$/)) syntaxCheckJs(f)
})

check('all .ux <script> blocks pass syntax check', () => {
  for (const f of collectFiles(SRC_ROOT, /\.ux$/)) syntaxCheckUx(f)
})

// ------------------------------------------------------------------ 3. i18n

check('i18n zh-CN/en/defaults exist and key sets match', () => {
  const keys = ['zh-CN', 'en', 'defaults'].map((lang) => {
    const f = path.join(SRC_ROOT, 'i18n', lang + '.json')
    if (!fs.existsSync(f)) throw new Error('缺失 i18n/' + lang + '.json')
    return readJson(f).message
  })
  const [zh, en, defaults] = keys
  const zhKeys = Object.keys(zh).sort().join(',')
  const enKeys = Object.keys(en).sort().join(',')
  const defKeys = Object.keys(defaults).sort().join(',')
  if (zhKeys !== enKeys) throw new Error('zh/en key 集合不一致')
  if (zhKeys !== defKeys) throw new Error('defaults key 集合不一致')
})

check('page-referenced i18n keys all exist in defaults', () => {
  const defaults = readJson(path.join(SRC_ROOT, 'i18n', 'defaults.json')).message
  const refs = new Set()
  for (const f of collectFiles(SRC_ROOT, /\.ux$/)) {
    const text = fs.readFileSync(f, 'utf8')
    for (const m of text.matchAll(/\$t\('message\.([a-zA-Z0-9_]+)'\)/g)) refs.add(m[1])
  }
  const missing = [...refs].filter((k) => !(k in defaults))
  if (missing.length) throw new Error('i18n 缺失 key: ' + missing.join(','))
})

// ------------------------------------------------------------------ 4. Band10 layout static guards

check('no hard width literals > 212px (Band10 212×520)', () => {
  const re = /(?:width|left|right|padding-left|padding-right)\s*:\s*(\d{3,})px/g
  for (const f of collectFiles(SRC_ROOT, /\.ux$/)) {
    const text = fs.readFileSync(f, 'utf8')
    for (const m of text.matchAll(re)) {
      if (parseInt(m[1], 10) > 212) {
        throw new Error(`${path.basename(f)}: ${m[0]} 超过 212px 屏宽`)
      }
    }
  }
})

check('no forbidden always-on patterns (red error dashboard / bg color)', () => {
  // 隐私/连续性纪律：断连时不得出现红色 ERROR dashboard。
  for (const f of collectFiles(SRC_ROOT, /\.ux$/)) {
    const text = fs.readFileSync(f, 'utf8')
    if (/(?:color|background-color)\s*:\s*#(?:f00|ff0000|e[0-9a-f]{4}|d00|d00000|c00|cc0000)\b/i.test(text)) {
      throw new Error(path.basename(f) + ': 出现红色 ERROR 风格样式')
    }
  }
})

check('no toFixed-accumulator type bug (string pollution regression guard)', () => {
  // ERA 33 R3 官方模拟器实测崩溃：self.x = (self.x + v).toFixed(2) →
  // 下一 tick 字符串拼接 → ".toFixed is not a function"。此处静态锁定同类模式。
  const re = /(?:self|this)\.(\w+)\s*=\s*\((?:self|this)\.\1\s*\+.*?\)\s*\.toFixed\s*\(/g
  for (const f of collectFiles(SRC_ROOT, /\.ux$/)) {
    const text = fs.readFileSync(f, 'utf8')
    if (re.test(text)) {
      throw new Error(path.basename(f) + ': toFixed 结果直接赋回累加器（会字符串污染，模拟器实测崩溃类）')
    }
  }
})

check('page reactive state uses private: (Vela 官方约定；data: 不建立响应式绑定)', () => {
  // ERA 33 R3 官方模拟器实测：页面 JS 生命周期运行但 data: 绑定不渲染。
  // 官方 demo 全部使用 private:。
  for (const f of collectFiles(SRC_ROOT, /\.ux$/)) {
    if (path.basename(f) === 'app.ux') continue
    const text = fs.readFileSync(f, 'utf8')
    const script = (text.match(/<script>([\s\S]*?)<\/script>/) || [])[1] || ''
    if (/export\s+default\s*\{[^}]*?\bdata\s*:/.test(script)) {
      throw new Error(path.basename(f) + ': 使用 data: 声明页面状态（Vela 需要 private:，模拟器实测不渲染）')
    }
    if (!/private\s*:/.test(script)) {
      throw new Error(path.basename(f) + ': 缺少 private: 状态声明')
    }
  }
})

check('app.ux lifecycle uses onCreate (Vela 官方约定；onInit 不会被调用)', () => {
  // ERA 33 R3 官方模拟器实测：app 级 onInit 静默跳过 → 应用 UI 永不显示。
  const app = path.join(SRC_ROOT, 'app.ux')
  const text = fs.readFileSync(app, 'utf8')
  const script = (text.match(/<script>([\s\S]*?)<\/script>/) || [])[1] || ''
  if (/onInit\s*\(/.test(script)) {
    throw new Error('app.ux 使用 onInit（Vela app 生命周期钩子为 onCreate，模拟器实测 UI 不显示）')
  }
  if (!/onCreate\s*\(/.test(script)) {
    throw new Error('app.ux 缺少 onCreate')
  }
  // app 上下文无模块 require（官方 demo 同款约束）：
  // 模块级 require 会让 app 脚本求值失败 → 所有 app 钩子 call failed → 应用永不显示。
  if (/\brequire\s*\(/.test(script)) {
    throw new Error('app.ux 使用了 require（app 上下文无模块系统，模拟器实测应用不显示；业务放页面）')
  }
})

check('no bound styles in style attribute (Vela 运行时限制，模拟器实测整页空白)', () => {
  // ERA 33 R3 官方模拟器实测：div 上的绑定 style 属性（多属性/整串绑定）
  // 导致整页不渲染。本项目已全面迁移到 class 绑定 + CSS 动画；
  // 此处静态锁定：style 属性内禁止出现任何 {{ }}。
  for (const f of collectFiles(SRC_ROOT, /\.ux$/)) {
    const text = fs.readFileSync(f, 'utf8')
    const re = /style="[^"]*\{\{[^"]*"/g
    const m = text.match(re)
    if (m && m.length) {
      throw new Error(path.basename(f) + ': style 属性含绑定（' + m[0].slice(0, 40) + '…）——Vela 实测不渲染，请用 class 绑定')
    }
  }
})

console.log('')
if (failures.length) {
  console.error('PREFLIGHT FAILED: ' + failures.length + ' 项')
  process.exit(1)
}
console.log('PREFLIGHT PASS（静态验证全绿；RPK 实际构建仍需 AIoT-IDE）')
process.exit(0)
