/**
 * DeclaredVelaFeaturesTest —— MINIMUM CAPABILITY DECLARATION（Phase 4）。
 *
 * 纪律（ECHO_WRIST_CONTRACT §Capability Declaration / XIAOMI_BAND10_CAPABILITY_MATRIX）：
 * - manifest.json 声明的每个 capability（system.X）必须存在真实调用 require('@system.X')；
 * - 必须声明 capability 的官方模块（interconnect/sensor/vibrator/fetch/geolocation/audio/request）
 *   如果被使用而未声明 → FAIL；
 * - 不声明未使用的能力（如项目未用 system.fetch → 不得声明）。
 *
 * 运行：node tests/declared_features_test.js（tests/run.js 一并执行）。
 */
const fs = require('fs')
const path = require('path')

const SRC_ROOT = path.join(__dirname, '..', 'src')

// 官方要求 manifest features 声明的模块（fetch 等网络/传感器/振动/定位/音频/后台请求类）。
// 基础模块（storage/router/prompt/app 等）按官方 manifest 文档不需要 feature 声明。
const MODULES_REQUIRING_FEATURE = [
  'system.interconnect',
  'system.sensor',
  'system.vibrator',
  'system.fetch',
  'system.geolocation',
  'system.audio',
  'system.request',
]

function collectFiles(dir) {
  const out = []
  const entries = fs.readdirSync(dir, { withFileTypes: true })
  for (const e of entries) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      out.push(...collectFiles(p))
    } else if (/\.(js|ux)$/.test(e.name)) {
      out.push(p)
    }
  }
  return out
}

function run(test) {
  const manifestPath = path.join(SRC_ROOT, 'manifest.json')
  const files = collectFiles(SRC_ROOT)
  const sources = files.map((f) => fs.readFileSync(f, 'utf8')).join('\n')

  test('manifest exists and declares only real capabilities', () => {
    if (!fs.existsSync(manifestPath)) throw new Error('manifest.json 缺失')
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
    const features = (manifest.features || []).map((f) => f.name)
    if (features.length === 0) throw new Error('features 为空')
    for (const feature of features) {
      const moduleName = feature.replace(/^system\./, '@system.')
      if (!sources.includes("require('" + moduleName + "')") &&
          !sources.includes('require("' + moduleName + '")')) {
        throw new Error('声明了未使用的 capability: ' + feature + '（MINIMUM CAPABILITY DECLARATION 违规）')
      }
    }
  })

  test('unused capabilities not declared (system.fetch absent)', () => {
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
    const features = (manifest.features || []).map((f) => f.name)
    for (const feature of features) {
      const moduleName = feature.replace(/^system\./, '@system.')
      const used = sources.includes("require('" + moduleName + "')") ||
        sources.includes('require("' + moduleName + '")')
      if (!used) throw new Error('未使用却声明: ' + feature)
    }
    if (features.includes('system.fetch')) {
      throw new Error('system.fetch 无任何真实调用，必须从 manifest 删除')
    }
  })

  test('used feature-modules are all declared', () => {
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
    const declared = new Set((manifest.features || []).map((f) => f.name))
    for (const mod of MODULES_REQUIRING_FEATURE) {
      const used = sources.includes("require('" + mod + "')") ||
        sources.includes('require("' + mod + '")')
      if (used && !declared.has(mod.replace(/^@/, ''))) {
        throw new Error('使用了 ' + mod + ' 但 manifest.features 未声明')
      }
    }
  })

  test('manifest package/router/designWidth integrity (Band10 212×520)', () => {
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
    if (!manifest.package || !/^[a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+$/.test(manifest.package)) {
      throw new Error('package 非法: ' + manifest.package)
    }
    if (manifest.config && manifest.config.designWidth !== 212) {
      throw new Error('designWidth 必须为 212（Band10 官方尺寸）')
    }
    const entry = manifest.router && manifest.router.entry
    const pages = (manifest.router && manifest.router.pages) || {}
    if (entry !== 'echo') throw new Error('第一屏必须是 echo（ECHO first）')
    for (const name of ['echo', 'why', 'action']) {
      if (!pages[name]) throw new Error('路由缺失页面: ' + name)
      const component = pages[name].component
      if (!fs.existsSync(path.join(SRC_ROOT, 'pages', name, component + '.ux'))) {
        throw new Error('页面文件缺失: pages/' + name + '/' + component + '.ux')
      }
    }
    if (manifest.deviceTypeList && manifest.deviceTypeList.indexOf('watch') === -1) {
      throw new Error('deviceTypeList 必须含 watch')
    }
  })
}

module.exports = { run: run }

if (require.main === module) {
  let passed = 0
  let failed = 0
  run((name, fn) => {
    try {
      fn()
      passed += 1
      console.log('  ok  ' + name)
    } catch (e) {
      failed += 1
      console.error('FAIL  ' + name + '\n      ' + e.message)
    }
  })
  console.log('\n' + passed + ' passed, ' + failed + ' failed')
  process.exit(failed === 0 ? 0 : 1)
}
