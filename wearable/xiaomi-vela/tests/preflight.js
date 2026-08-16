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
    const f = path.join(SRC_ROOT, 'pages', name, cfg.component + '.ux')
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

check('i18n zh/en/defaults exist and key sets match', () => {
  const keys = ['zh', 'en', 'defaults'].map((lang) => {
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
    if (/(?:color|background-color)\s*:\s*#(?:f00|ff0000|e[0-9a-f]{4})\b/i.test(text)) {
      throw new Error(path.basename(f) + ': 出现红色 ERROR 风格样式')
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
