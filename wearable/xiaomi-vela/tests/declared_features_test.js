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

// 官方事实（aiot-toolkit 官方 demo 的 manifest + 官方文档）：
// interface 类模块（router/fetch/audio/request/app/device/configuration/storage/file/
// interconnect/vibrator/brightness/alarm/network/sensor）使用即需声明。
// 本项目 MINIMUM CAPABILITY DECLARATION：只用 interconnect/sensor/vibrator/storage/router。
const MODULES_REQUIRING_FEATURE = [
  'system.interconnect',
  'system.sensor',
  'system.vibrator',
  'system.storage',
  'system.router',
  'system.fetch',
  'system.geolocation',
  'system.audio',
  'system.request',
  'system.app',
  'system.device',
  'system.configuration',
  'system.file',
  'system.brightness',
  'system.alarm',
  'system.network',
]

// 使用即声明：@system.X 或 $router（路由）→ 对应 feature 必须在 manifest。
function moduleUsage(sourceText, moduleName) {
  return sourceText.includes("require('" + moduleName + "')") ||
    sourceText.includes('require("' + moduleName + '")')
}

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

  /** feature 的 usage 证据：@system.X require 或 system.router ↔ 官方 router 用法
   *  （import router from '@system.router' / router.push / router.back）。 */
  function isUsed(feature) {
    if (feature === 'system.router') {
      return /(import\s+router\s+from\s+['"]@system\.router['"]|\.\$router\.(push|back|replace)|router\.(push|back|replace)\s*\()/.test(sources)
    }
    return moduleUsage(sources, feature.replace(/^system\./, '@system.'))
  }

  test('manifest exists and declares only real capabilities', () => {
    if (!fs.existsSync(manifestPath)) throw new Error('manifest.json 缺失')
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
    const features = (manifest.features || []).map((f) => f.name)
    if (features.length === 0) throw new Error('features 为空')
    for (const feature of features) {
      if (!isUsed(feature)) {
        throw new Error('声明了未使用的 capability: ' + feature + '（MINIMUM CAPABILITY DECLARATION 违规）')
      }
    }
  })

  test('unused capabilities not declared (system.fetch absent)', () => {
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
    const features = (manifest.features || []).map((f) => f.name)
    for (const feature of features) {
      if (!isUsed(feature)) throw new Error('未使用却声明: ' + feature)
    }
    if (features.includes('system.fetch')) {
      throw new Error('system.fetch 无任何真实调用，必须从 manifest 删除')
    }
  })

  test('used feature-modules are all declared', () => {
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
    const declared = new Set((manifest.features || []).map((f) => f.name))
    for (const mod of MODULES_REQUIRING_FEATURE) {
      const used = moduleUsage(sources, mod)
      if (used && !declared.has(mod.replace(/^@/, ''))) {
        throw new Error('使用了 ' + mod + ' 但 manifest.features 未声明')
      }
    }
    // 路由使用（官方 import router 模式）→ 必须声明 system.router（官方 demo 同规则）
    const routerUsed = /(import\s+router\s+from\s+['"]@system\.router['"]|\.\$router\.(push|back|replace)|router\.(push|back|replace)\s*\()/.test(sources)
    if (routerUsed && !declared.has('system.router')) {
      throw new Error('使用了 router 但 manifest.features 未声明 system.router')
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
      // 官方布局：页面直接位于 src/<page>/<component>.ux（aiot-toolkit 官方约定）
      if (!fs.existsSync(path.join(SRC_ROOT, name, component + '.ux'))) {
        throw new Error('页面文件缺失: ' + name + '/' + component + '.ux（官方布局 src/<page>/<component>.ux）')
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
