# VISUAL_RUNTIME_V3 — Source Reality（源码事实锚点）

> 本报告在任何 production code 修改之前创建（V3 §5 Source Reality Gate）。
> 所有结论来自 `git` / `grep` / 源码直读，不来自聊天历史或旧 ERA 叙述。

## 0. HEAD

| 字段 | 值 |
|---|---|
| IMPLEMENTATION_HEAD | `c4ff17be9a116884b4a6e46ca5c07b6e0c615401` |
| 描述 | `Merge pull request #44 from qq547820639/visual-runtime`（visual-runtime R1–R8 已合入 main） |
| 工作分支 | `visual-runtime-v3`（自 main 切出） |
| 日期 | 2026-08-16 |

## 1. Gradle Modules（当前真实 main，14 个）

`:app` · `:core:model` · `:core:ports` · `:core:security` · `:core:visual` ·
`:feature:actions` · `:feature:memory` · `:feature:observation` · `:feature:presence` ·
`:feature:presencevisual` · `:feature:intelligence` · `:feature:journey` · `:feature:qa` · `:feature:wearable`

- V3 §4 禁止新增的 `:core:visual` **已存在于当前真实 main**（PR #44 合入）→ 属「已存在」例外，保留。
- `:feature:presencevisual` 同理已存在（Compose/android.graphics adapter 层）。
- V3 不再新增任何 module；renderer-internal 类型（SceneCompiler/RenderPacket/Backend）
  落在 `:core:visual`（纯算法）与 `:feature:presencevisual`（Android adapter）内。

## 2. Toolchain（升级前事实）

| 项 | 当前 | V3 目标 |
|---|---|---|
| AGP | 8.13.2 | 9.3.1 |
| Gradle Wrapper | 8.13 | 9.5 |
| JDK | 17（Temurin 17.0.20，本机实测） | 17（保持） |
| Kotlin | 2.3.20 | 2.3.20（保持） |
| KSP | 2.3.9 | 保持 |
| Compose BOM | 2026.06.00 | 2026.08.00 |
| compileSdk | 36（本机 SDK 仅 android-36 平台） | 37（需下载 android-37 平台） |
| targetSdk / minSdk | 36 / 26 | 保持 36 / 26 |
| detekt | 1.23.8（27 规则，app 模块应用） | 保持 |

**已知环境限制**：工作区路径含空格（`ECHO Workspace`），AGP 8.13.2 的
`DexingNoClasspathTransform` 在 `:app:assembleDebug` 报 "file located outside the root
directory"（ADR-0002 已记录）。本地 assemble 需在无空格路径副本执行；AGP 9.x 是否修复
该 bug 在 Toolchain Slice 实测并记录。

## 3. 业务链（Ground Truth，保持不变）

```text
Observation Core → EchoPresenceState（core:model）→ EchoVisualMapper → EchoVisualParameters
```

- `EchoPresenceState` / `EchoMaturity` / `EchoIdentityGenome` / `EchoLifeSeason` /
  `EchoDailyComposition` / `EchoMomentState` / `RhythmState` / `BehaviorState` /
  `SensingRuntimeStatus`（六态）全部在 `core:model`（com.yunjue.echo.mind.model）。
- `EchoVisualParameters`（flowSpeed/coherence/turbulence/particleDensity/coreOpenness/
  dispersion/pulsePeriodSeconds/depth/brightness/contrast/accentIntensity/structureComplexity）
  与 `EchoVisualMapper` 在 `feature:presence/VisualProfile.kt`（冻结映射链唯一入口）。
- `SurfaceMode { APP, HOME_WALLPAPER, LOCK_SAFE, DREAM, REDUCED_MOTION, LOW_POWER }` 同文件。
- affectiveState 恒 null（AFFECTIVE_CONTRACT 冻结，测试强制）——V3 不触碰。

## 4. 渲染管线现状（两套并存 → V3 收敛目标）

### 4.1 旧管线（superseded，待删除纪律覆盖）

`computeEchoSceneFrame`（feature/presence/VisualProfile.kt:246）→ `EchoSceneFrame`
（单环 + 圆点/流线粒子 + 次级环 + textureFamily 0..3）→ `renderEchoFrameToCanvas`
（feature/presence/EchoSceneRenderers.kt:162）。

消费方（切换前必须全部迁移）：`EchoActionOverlay.kt`、`JourneyScreen.kt`（2 处）、
`JourneyMemoryView.kt`、`JourneyVisuals.kt`、`JourneyCanonical.kt`、
`QaTimeline.kt`、`VisualReviewRenderer.kt`（feature:qa）、`VisualRegressionGoldenTest`、
`WallpaperMotionRealityTest`、`IdentityDiversityEvalTest`、`EchoPresenceCodecTest`、
`EchoPresenceSnapshotRecoveryTest`、`VisualProfileTest`、`IdentityPipelineTruthTest`、
`FirstRunVerticalTest`、`PerformanceBaselineTest`、`EchoSceneFrameDeviceBenchmarkInstrumentedTest`。

### 4.2 新管线（visual-runtime R1–R8，V3 扩展基础）

```text
EchoPresenceState → GenomeDeriver → EchoVisualGenome → SurfacePolicy.crop
  → EchoVisualSpec → OrganismFrameComputer → OrganismFrame（9 层纯数据帧）
  → adapter（Compose: EchoOrganism / android.graphics: OrganismCanvasRenderer）
```

- `:core:visual`（纯 Kotlin，仅依赖 core:model）：`DeterministicRandom`（SplitMix64）、
  `OrganicNoise`（多正弦 fbm）、`ColorSpace`（HSV→ARGB）、`EchoVisualGenome`（19 字段 +
  revision）、`GenomeDeriver`、`MotionEngine`/`MotionPhase`/`MotionPolicy`、
  `OrganismFrame`/`OrganismFrameComputer`、`EchoSurface`（6 surface）/
  `SurfaceCapabilities`/`SurfacePolicy`、`EchoPortraitSnapshot`、`WristVisualSpec`/
  `WristVisualProjector`。
- `:feature:presencevisual`：`EchoOrganism`（Compose Canvas）、`OrganismCanvasRenderer`
  （Wallpaper/Dream/离屏 golden 共用）。
- **差距（V3 待实现）**：当前 organism 是 2D 膜 + 弦式 filament + 随机环形粒子 + 实心
  coreGlow；无 3D plane basis / behind-core occlusion / Fibonacci 球粒子 / hollow core
  cavity / Structural Rings-Long Filaments-Local Fragments 三层拓扑 / perceptual LCh
  palette / EchoRenderPacket / Capability Router（LEGACY/STANDARD/ADVANCED/ULTRA）/
  AGSL backend / API36 compositor / WCG-HDR 门控 / soft-knee tone pipeline / Visual Lab。

### 4.3 App render path

`EchoMindApp`（三世界 Tab + 紧急 FAB，非 Me tab 常驻）→ `EchoSceneScreen` →
`EchoSceneContent`（**当前仍为 LazyColumn feed**：visualSurface + 日期 + 状态条 + Why +
对话 + 行动卡…→ V3 §37 必须重构为 Scene）。视觉槽位 `EchoVisualSurface`（380.dp 高，
已切到 9 层 organism；含无限帧动画）。

### 4.4 Journey render path

`JourneyScreen` / `JourneyMemoryView` / `JourneyVisuals` / `JourneyCanonical`；
R7 已部分切到 organism，仍残留 `computeEchoSceneFrame` 调用（见 4.1 清单）。
`EchoPortraitSnapshot` 存参数不存图（`PORTRAIT_CANONICAL_TIME_SECONDS` 固定重建；
`dayNumber` 相位）。Scale = 日/周/月/季/年（`JourneyState.kt`）。

### 4.5 Wallpaper render path

`app/.../presence/EchoWallpaperService.kt`（WallpaperService.Engine + Choreographer）+
`feature/presence/WallpaperRenderController.kt`（可见性门：不可见 → renderActive=false →
移除 FrameCallback，0 连续绘制）。自适应帧间隔现状：过渡期 33ms / 静置期 250ms（4fps）。
V3 §69 调度表（0/8/10/12/18/30fps 分级 + power/thermal/night/touch 输入）待实现。

### 4.6 Dream render path

`app/.../presence/EchoDreamService.kt`（合法 DreamService，143 行；PUBLIC_SAFE；
onDreamingStopped 后 0 残留渲染）。fps 分级（entry 24 / steady 15 / reduced 8）与
burn-in 每分钟 deterministic offset（§71/§72）待核实/实现。

### 4.7 Wrist projection path

`WristVisualProjector.downsample( EchoVisualGenome ) → WristVisualSpec`（core:visual）+
`:feature:wearable`（Wear Protocol v1 / WearPresenceEnvelope，presence 只发
identity{topology,symmetry,orbit,motion,texture,colorFamily,accent}+moment+maturity+
surface）+ `wearable/xiaomi-vela` 快应用。断连 → 保留 Identity、Moment → QUIET
（`quietFallback`）。V3 §73 的 2–4 loops/8–18 particles 规格需在 Vela renderer 侧核对。

## 5. Onboarding 状态机（冻结，不重新设计）

`WELCOME → PRIVACY_PLEDGE → CORE_SENSING →（AWAKENING 过渡 2200ms）→ 自动进 ECHO Scene`；
`enum class OnboardingStep { WELCOME, PRIVACY_PLEDGE, CORE_SENSING }`（**无 DONE**）；
`AWAKENING_DURATION_MS = 2200L`（OnboardingScreen.kt:414）。增强权限（Usage Access 等）
已移出首启。V3 只改呈现层（Seed ECHO 视觉），不动状态机。

## 6. Crisis path

`EchoMindApp`：非 Me tab 常驻红色紧急 FAB（`shouldShowEmergencyFab`，error 语义色，
contentDescription「紧急支持」）→ 直达 Me 支持区块（`SafetyScreen` / `MeSupportHelpers`）。
V3 不降低一键可达性、不增点击次数。

## 7. Reduced Motion 现状

- 偏好：`presenceReduceMotion` → `resolveSurfaceConfig` → `SurfaceMode.REDUCED_MOTION` →
  `MotionPolicy.REDUCED_MOTION`（moveAmp×0.08，呼吸幅度×0.5，保留低频呼吸+亮度漂移）。
- V3 §30 精确系数（particle×.35 / velocity×.08 / orbit×.06 / filament phase×.12 /
  breath .007 / touch×.35 / 180ms crossfade）待落到 MotionEngine/SceneCompiler。

## 8. 现有视觉测试 / Benchmark / QA 镜像

- Golden：`VisualRegressionGoldenTest`（7 profile × 6 锚点日 = 42 帧 FNV-1a 哈希）、
  `OrganismGoldenRenderTest`（presencevisual）、`QaPortraitMirrorGoldenTest`（跨语言黄金门）。
- 画廊：`VisualReviewRenderTest` → `qa/visual-review/rendered/`（生产管线 PNG 人眼评审）。
- 一致性：`IdentityContinuityEvalTest` / `IdentityDiversityEvalTest` / `IdentityPipelineTruthTest` /
  `OrganismDeterminismTest` / `MotionEngineTest` / `WristVisualSpecTest`。
- Benchmark：`EchoSceneFrameDeviceBenchmarkInstrumentedTest`（androidTest，旧帧模型首帧锚点）、
  `QaPerformanceBudgetTest`（JVM 预算）。
- QA 镜像：`QaPortraitMirror`（跨语言黄金门锁定，属既有冻结 QA，不是 V3 禁止的
  「第二套 mirror renderer」新建物；V3 视觉 QA 必须调 production renderer）。
- **没有**：Visual Lab（debug 调参工具）、自动视觉指标（near-black/highlight/warm/
  negative-space/visual-mass）、AGSL/Advanced 后端测试。

## 9. Real-device / 外部状态

- `adb` 不存在于本环境 → Android 真机验证 = **BLOCKED_EXTERNAL_ANDROID_DEVICE**
  （只能 EMULATOR_PASS，若后续模拟器可用）。
- Band10 真机 / Xiaomi SDK / 生产签名 / 长跑机时：沿用 STATUS §4 的
  `BLOCKED_EXTERNAL_*` 清单（不新增、不伪造）。
- 本机 SDK 仅 android-36 平台；compileSdk 37 需下载 android-37（Toolchain Slice 实测）。

## 10. 冻结契约冲突检查

无冲突。V3 全部要求（3D 拓扑/hollow core/AGSL/Visual Lab/Scene 重构）落在既有冻结契约
允许范围内；`:core:visual` 与 `:feature:presencevisual` 已在真实 main 存在，不违反
「保持当前 module 体系」。旧 renderer（4.1）按 §93 删除纪律在新管线稳定后逐 Surface 移除。
