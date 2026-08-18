# ECHO_ORGANISM_QUALITY_BASELINE — 迭代前视觉基线

> 迭代：ECHO Organism Quality Pass + Runtime Finish
> 基线 HEAD：`216f3b3cccf6553bdcc30ae7d6256b6c15862114`（main，clean）
> 基线采集时间：2026-08-18（渲染在本机 JVM · Robolectric 4.14.1 NATIVE graphics · JDK 17）

## 1. Actual renderer paths（真实源码审计，非历史 Prompt 路径）

| 层 | 文件 | 角色 |
|---|---|---|
| 语义映射 | `android/feature/presence/.../VisualProfile.kt`（EchoVisualMapper） | EchoPresenceState → EchoVisualParameters（唯一语义链） |
| 机械编译 | `android/core/visual/.../VisualGenomeCompiler.kt` | params + identity → EchoVisualGenome |
| Surface 裁剪 | `android/core/visual/.../EchoVisualSpec.kt`（SurfacePolicy.crop） | 隐私/亮度/质量裁剪 |
| 场景编译 | `android/core/visual/.../EchoSceneCompiler.kt` | 呼吸/轨道/丝相位/质量档编译 |
| 稳定拓扑 | `android/core/visual/.../OrganismTopology.kt`（TOPOLOGY_VERSION=3） | rings/longs/fragments/particles/knots（identity 级缓存） |
| 帧求值 | `android/core/visual/.../OrganismFrameComputer.kt` | 纯函数 12 层帧（确定性） |
| Canvas 后端 | `android/feature/presencevisual/.../OrganismCanvasRenderer.kt` | LEGACY 正式后端（Wallpaper/Dream/离屏） |
| AGSL 后端 | `android/feature/presencevisual/.../AgslEchoBackend.kt` | STANDARD/ADVANCED 材质（mask R/G 两通道，无深度） |
| 统一门面 | `android/feature/presencevisual/.../EchoRendererFacade.kt` | Capability Router + session（requested/resolved/backend+reason） |

## 2. Actual backend resolution（基线行为）

- 生产 App Home（`EchoVisualSurface`）请求 **LEGACY**（未显式请求 AGSL）→ 全设备 CANVAS。
- Wallpaper / Dream 显式钉死 LEGACY/CANVAS（216f3b3：AGSL 未做真机功耗 benchmark 前不默认启用）。
- AGSL 只能经 Visual Lab 显式选择；`AdvancedBackendVisualGate`（instrumented）设备门存在但无真机执行。
- **JVM 事实（本轮实测探针 AgslJvmProbeTest）**：Robolectric NATIVE 可**编译** RuntimeShader
  （`isAvailable()=true`，sdk35；ADVANCED RuntimeColorFilter 需 API36 → false），但
  RuntimeShader **无法在软件 canvas 栅格化**（`BaseCanvas.throwIfHasHwFeaturesInSwMode`——Android 真实约束）。
  因此离屏 `renderToBitmap` 对 AGSL 后端在设备上同样会抛（基线缺陷，本轮修复）。

## 3. Visual Lab 当前行为

- 5 preset（SEED/KNOWN_DAY28/QUIET/LOW_DATA/MATURE）× 3 surface（APP/WALLPAPER/DREAM）×
  4 tier chip（CANVAS/AGSL/ADVANCED/ULTRA）+ 11 滑杆 + identity seed + Reduced Motion；
- 预览经 `EchoRendererFacade.Organism`，导出经 `createSession().renderToBitmap`（后端真值
  requestedBackend/resolvedBackend/backendName/reason 落 JSON）；
- 基线指标 7 项（nearBlack/highlight/warm/negSpace/visualMass/centerLuma/outerLuma），
  无 organismBoundingBox / edgeDensity / extremeGlint。

## 4. MASTER_REFERENCE 输入（本轮冻结）

`PROFILE_A_STABLE identity（seed=7710，QaProfiles）× KNOWN Day28 reference genome
（VisualLabFixtures §35）· APP_PRIVATE · 1080×2340（=412×915dp @2.625）· NORMAL motion ·
canonical 12s · 请求 ADVANCED tier`。渲染经 production facade session（离屏 raster 后端真值见 §2）。

## 5. 基线输出与自动指标（production renderer 实测）

| Shot | 后端（请求→离屏实际） | nearBlack | highLum | glint | warm | negSpace | mass@.9R | bbox W×H | edge | 门 |
|---|---|---|---|---|---|---|---|---|---|---|
| 01 KNOWN Day28 APP | ADVANCED→CANVAS | 87.9% | 0.0% | 0.0% | 0.0% | 99.8% | 88.7% | **44.8%×20.5%** | 0.101 | FAIL |
| 02 SEED APP | ADVANCED→CANVAS | 92.2% | 0.0% | 0.0% | 0.0% | 99.9% | 91.7% | 38.1%×17.6% | 0.056 | FAIL |
| 03 QUIET APP | ADVANCED→CANVAS | 92.5% | 0.0% | 0.0% | 0.0% | 99.9% | 97.3% | 42.2%×18.0% | 0.099 | FAIL |
| 04 KNOWN Day28 Wallpaper | LEGACY→CANVAS | 92.7% | 0.0% | 0.0% | 0.0% | 99.8% | 97.2% | 44.3%×20.2% | 0.088 | FAIL |

（基线 PNG/JSON：`qa/visual-review/organism-quality/`·首轮；门阈值与最终轮一致：bbox 宽 72–82%。）

## 6. 基线像素级检视（54×60 luma 图 + hue 直方图）

- 结构读感：3–4 个**闭合整圆环**（轨道感主导）+ 少量淡长弧 + 极弱局部碎片（2–12 个，半径 0.12–0.28，
  无辉光）；中心暗盘核 + 1 个微弱结；粒子散布 0.48–1.08R（远层散点 → 星空感）。
- **主色测量：hue 210° 占 1849/2220 发光像素（青蓝），暖色 0%，真正亮（>0.80 luma）0 个。**
- 根因（本轮代码审计确认，两处生产缺陷）：
  1. `EchoIdentitySpec` palette chroma=0.095–0.12 被当作 **Lab 彩度单位**（±100 轴）→ 近无彩灰
     （「灰色线圈」的直接来源）；
  2. `ColorSpace.lch` Lab→XYZ 分母错位（a/5、b/2，应为 a/500、b/200）——在旧近零彩度下被掩盖。
- organism luminous bbox 44.8% viewport（目标 72–82%）——身体过小，外负空间近 100%。
- Wallpaper/Dream：同一 Canvas 后端（CONSERVE），亮度上限 0.7/0.55 生效。

## 7. 基线性能（可测部分）

- JVM 侧无 GPU/帧时间可测；静态帧 CPU：MASTER 1080×2340 单帧 compute+Canvas raster
  ≈ 40–80ms（Robolectric NATIVE，含 PNG 编码误差；仅作量级参考）。
- 真机 GPU/电池/帧率：BLOCKED_EXTERNAL_DEVICE（本环境无设备，见 FINAL 报告）。

## 8. 结论（基线）

基线 ECHO 与 Art Direction 目标的差距是**实质性的**：过小、灰、轨道圆、无暖、无真亮、无深度层次。
两处颜色数学缺陷（chroma 量纲 + Lab 逆变换分母）是「灰色线圈」根因；结构性问题（闭合整圆环、
碎片过弱、粒子远散）由拓扑常量决定。→ 进入 MASTER_REFERENCE 迭代（见 RENDERER 报告）。
