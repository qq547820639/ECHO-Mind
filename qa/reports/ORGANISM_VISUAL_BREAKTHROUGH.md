# Organism Visual Breakthrough — Before / After

> 轮次：v0.11 Organism Visual Breakthrough（分支 `agent/organism-visual-breakthrough`）
> 基线提交：`e952383`（改造前）→ 本轮系列提交（改造后）
> 度量工具：`scripts/visual/analyze_organism.py`（机器视觉代理指标；无多模态 Agent 的眼睛）
> 度量对象：`android/feature/qa/visual-review/organism/*.png`（production OrganismFrameComputer
> + production OrganismCanvasRenderer 渲染，非 QA mirror）

## Architecture preserved

唯一语义链完整保留，UI 未绕过、renderer 未解释任何业务语义：

```
EchoPresenceState → EchoVisualMapper → EchoVisualParameters → VisualGenomeCompiler
→ EchoVisualGenome → SurfacePolicy → EchoVisualSpec → EchoSceneCompiler
→ OrganismFrameComputer → OrganismFrame → Renderer(Canash/Compose/AGSL)
```

- Canvas-first + AGSL progressive enhancement 决策不变（ADR-0001/0002）
- 三世界架构 / 模块结构 / SurfacePolicy 隐私裁剪 / wallpaper 不可见停渲 全部未动
- 所有随机源仍来自 identitySeed 稳定盐（新增盐段 6050–6799 与既有盐段两两不相交，
  `OrganismTopologyTest.saltSegmentsDoNotCollideAcrossLayers` 静态断言）
- 无 frame random：lobe/membrane/ground ring 的运动全部是 clock 纯函数

## Renderer changes

- **OrganismTopology**（core:visual，TOPOLOGY_VERSION 5）：+`volumeLobes`（12–26 个
  identity 稳定 nebula 锚点；cyan 33% / primary 32% / secondary 35% 色族）
  +`membraneHarmonics`（orders 2/3/5 有机膜轮廓谐波）
- **OrganismFrame**：+`VolumeLobeV` / `MembraneSpec` / `GroundRing` / `CoreGlow`
  四个体积层（默认空 = 旧帧向后兼容）
- **OrganismFrameComputer**：体积叶三分层求值（back<0.45 / mid / front>0.82 深度带）、
  有机膜（SEED 野生 ±8–14% → MATURE 稳定 ±3–5% 变形）、地面空间环（surface 增益表，
  Wrist=0）、核心辉光、3 个核心发射结（近白 cyan 生命火种）
- **暗腔重平衡**：有效直径从 organism 直径的 60–72% 收敛到 **22–31%**
  （`cavityRadiusFor`: 0.30+0.06e → 0.11+0.05e）——「大黑洞原子模型」根因修复
- **轨道环降权**（0.36+0.28c → 0.20+0.16c）：环是空间提示，不再抢主体
- **丝宽三级**（major 0.0034 / normal 0.0026 / hairline 0.0018）+ major cyan 高光族
- **曝光响应放大**：lobe/membrane/coreGlow/发射结 × (0.45+0.55e)——Dream/Night/Wallpaper
  的「暗」从被压平变为真实可感知
- **调色板**：primary c 42→52 / secondary c 38→48（ColorSpace 二分收缩取最大可达饱和度），
  primary L 带 per-identity 微差（0.72–0.76，修复高彩度下相近 hue 的 8-bit 撞色）
- **glint 色修复**：近白蓝 (0.94,0.97,1.0) 的 luma 只有 0.84——物理上永远到不了
  「真正亮」档；改 (0.97,0.985,1.0)（luma 0.86+）

## New frame primitives

| 原语 | 职责 | 来源 |
|---|---|---|
| `VolumeLobeV` | nebula 体积叶（椭圆、旋转、深度、单层低 alpha） | identity 拓扑 + moment 慢漂移 |
| `MembraneSpec` | 有机生命膜（谐波轮廓 + fill/edge/rim/haze 四档材质） | identity 谐波 + maturity 变形缩放 |
| `GroundRing` | 下方空间能量环（ECHO「存在于空间」） | identity 盐 + surface 增益 |
| `CoreGlow` | 心脏光（把暗腔嵌入云组织） | coreOpenness + exposure |
| 发射结（CoreKnotV 族） | 暗腔 rim 的近白 cyan 火种 | identity 盐 6760–6799 + 曝光熄火 |

## Canvas improvements

15 层绘制顺序（Breakthrough §25）：ambient → atmosphere → halos → **地面环+反射辉光** →
**后层体积叶** → **核心辉光** → 三层丝（3-pass：5.2× 宽辉光 / 2.4× 中间体 / 1× 细亮芯）→
**中层体积叶** → 暗腔（柔边，alpha 235）→ **前层体积叶** → 细缕+结（平坦中心剖面）→
粒子（BRIGHT/GLINT ×5–7.5 辉光晕）→ **有机膜**（fill+外雾+散射带+cyan 亮缘）→ 涟漪 → 暖高光。

- `NebulaTextureCache`：runtime procedural 128px 星云纹理（确定性、缓存复用、
  零每帧分配——§24 允许的 runtime 生成纹理，非设计稿 PNG）
- Compose 渲染器（`drawOrganism`）同步升级——同一帧、同序、同材质语言

## AGSL improvements

- **双 mask 输入**：`iVectorMask`（几何：R 冷/G 暖/B 深度）+ `iVolumeMask`
  （体积：A lobe 覆盖 / G 膜·地环边缘 tag / B lobe 深度）
- **FBM 星云材质**：hash → value noise → 4-octave fbm × domain warp（§20/§21/§22）；
  `cloudDensity = lobeCov × (0.42+0.85×fbm)`；第二噪声场做 blue/violet/cyan 色谱混合；
  深度吸收（深处云更暗）；膜/地环边缘发射
- **确定性**：噪声相位 = identityPhase×0.61 + dayComposition×0.23 + clock×0.004
  （`noisePhaseFor` 单源；同 clock 恒同帧）
- Canvas fallback 无 crash 语义不变（API<33 / 编译失败 / 软件 canvas → Canvas 后端）

## Machine visual metrics（KNOWN_DAY）

Before = 基线 `e952383`；After = 本轮（`scripts/visual/analyze_organism.py`，hero ROI =
视口中央 76% 宽方形）：

| 指标 | Before | After | 目标（§7/§31） |
|---|---|---|---|
| mean_luminance | 0.0665 | **0.1278** | 0.12–0.18 ✅ |
| bright_ratio (luma>0.25) | 0.0545 | **0.1233** | 0.12–0.22 ✅ |
| chromatic_cov（faint：sat>0.18 & luma>0.05） | 0.2350 | **0.8385** | 体内全覆盖（连续体积达成） |
| chromatic_cov_strong（sat>0.25 & luma>0.10） | n/a* | **0.3714** | 0.35–0.60 ✅ |
| chromatic_sat（faint / strong） | 0.4161 | **0.5284 / 0.5346** | ≥0.55 ~✅（差 0.015） |
| very_bright_ratio (luma>0.8) | 0.00001 | **0.0011** | §31 硬门 ≤0.08 ✅ / §7 代理 2–6% ❌（见下） |
| warm_ratio | 0.0046 | **0.0074** | ≤0.05 ✅ |
| hero_bbox_w | 0.782 | **0.815** | 0.68–0.84 ✅ |
| **wireframe_dominance** | **0.4609** | **0.0456** | ≪基线 ✅✅ |
| central_volume | 0.432 | **0.704** | 明显提高 ✅ |
| dark_neg_space（全屏近黑） | 0.880 | **0.655** | ≥0.50 ✅ |

\* 基线 PNG 已被新渲染覆盖，强口径无法回溯；Before 列用与基线表
（`qa/reports/baseline_organism_metrics.json`）同口径的 faint 数字，After 双口径齐给。
基线时期 organism 体内彩色覆盖 23.5% 且 wireframe 主导 46%——「线框球」的量化画像。

**very_bright 偏差说明（诚实声明）**：§7 的 2–6% 代理区间与 §10/§28「真正亮的点必须极少」
「重点提高 glint 对比而非数量」直接冲突。最终状态取「少而真」：3 个发射结 + ~5 个高亮
glint（>0.8 luma 像素 0.11%，全部带 7.5× 辉光晕 + 对比）。§31 硬门（≤0.08）以 70× 余量通过。
最终艺术方向由人类/多模态复核（§74）。

### 其余状态（After）

| 状态 | mean_lum | bright | strong_chromatic | 状态表达 |
|---|---|---|---|---|
| seed_day | 0.093 | 0.053 | 0.115 | 更简单稀疏（lobe 少、膜野生）但不退回线框 |
| low_data | 0.100 | 0.065 | 0.153 | 体积更薄、清晰度降，identity 色族仍在 |
| quiet | 0.133 | 0.133 | 0.395 | 体积仍存在（静态帧；低湍流/慢运动在 genome） |
| night | 0.128 | 0.123 | 0.372 | ≈day（golden fixture 不编码昼夜；生产昼夜经 mapper→luminance→曝光响应） |
| dream | 0.126 | 0.132 | **0.214** | 彩色体积显著收敛 + 暖比 2.1× + 发射结熄火 |
| wallpaper | 0.122 | 0.120 | 0.318 | 比 App 安静（surface 亮度上限生效） |
| wrist | 0.116 | 0.116 | 0.306 | SECOND BODY：lobe 减半、无地面环、identity hue 保留 |

## Cross-surface results

- 同 identity 跨 surface 连续性：`IdentityContinuityEvalTest` / `OrganismGoldenRenderTest.identityIsStableAcrossSurfaces` / `QaPortraitMirrorGoldenTest` 全绿
- 7 profile 互异：`IdentityDiversityEvalTest` / `QaTimelineE2ETest` 全绿（accent/背景/几何维度）
- 42 锚点帧黄金（7 profile × 6 日）按 §61 流程重生成（语义/确定性/identity/暖色门全过后更新）

## Performance

| 项 | 实测（JVM Robolectric NATIVE） | 预算 | 基线 |
|---|---|---|---|
| CPU 帧求值 | 0.77 ms/帧 | <50 ms | 0.79 ms |
| Canvas raster（全体积栈） | ~92 ms/帧 | <2000 ms | 72 ms |
| AGSL 双 mask raster | ~17 ms/帧 | <800 ms | 22 ms |
| 热路径分配 | **0 KB/100帧** | <32 MB | ≈0 KB |

（设备 GPU shader 执行 / 帧率 / 电池 = 真机项，JVM 不可测——见 Remaining。）

## Tests

最终门禁（分支 `agent/organism-visual-breakthrough`，全部实测通过）：

- `./gradlew clean` → `testDebugUnitTest`：**1377 tests / 0 failures**（clean 后全量）
- `./gradlew lintDebug detekt`：**通过**（修复 4 个 NewApi——mask 光栅化拆为纯 Canvas 的
  `AgslMaskRasterizer`，顺带清掉 main 上既有的同类潜在 lint 债 + 3 个 detekt 违规）
- `./gradlew :app:assembleDebug`：**APK 产出**（app-debug.apk 22.8MB）
- `make android`（CI 同构门）：**BUILD SUCCESSFUL**
- `:core:visual` 65 tests ✅（新增体积拓扑/膜谐波/盐分段断言）
- `:feature:presencevisual` 30 tests ✅（含旧门 + **6 个新艺术门**）
- `:feature:qa` 113 tests ✅（黄金重生成；diversity 背景度量改 ambient 三元组）

## Commits

```
20ad607 feat(visual): add deterministic organism volume topology
ac197d1 feat(renderer): give canvas echo volumetric body and membrane
ff23e5e feat(renderer): add volumetric agsl organism material + state propagation
4bef09a test(visual): lock volumetric echo quality regression gates
```

## Remaining visual uncertainty

- **AGSL 实机材质仍需 Android 13+ 硬件设备复核**（JVM 只验证 shader 编译 + mask 真值；
  FBM 云场/色谱混合/边缘发射的最终观感未经 GPU 真值渲染确认）
- **HDR grading 未经 HDR 真机验证**（§T 恒 BLOCKED 不变）
- **最终艺术方向仍需人工视觉复核**（§74：本轮结论是「生产 renderer 已从线框主导结构
  升级为 volume+membrane+filament 分层结构；机器代理指标全面改善；审美裁决交还人类」）
- very_bright 代理区间（§7 2–6%）与「真正亮必须极少」（§10/§28）的冲突按后者裁决，
  若人工复核要求更亮的核心，优先调大发射结半径而非数量
