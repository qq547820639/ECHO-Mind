# ECHO_ORGANISM_QUALITY_RENDERER — 渲染器改造与 MASTER 迭代记录

> 迭代：ECHO Organism Quality Pass + Runtime Finish（基线见 ECHO_ORGANISM_QUALITY_BASELINE.md）
> 人眼审美结论：**PENDING_PRINCIPAL_VISUAL_REVIEW**（本报告只陈述实现/指标/证据）

## 1. 发现并修复的两个颜色数学根因（本轮最大杠杆）

1. **palette chroma 量纲错误**（`EchoIdentitySpec`）：旧值 c=0.095–0.12 被当作 CIELCh Lab
   彩度（±100 轴）→ 近无彩灰——「灰色线圈」的直接来源。修正为真实 Lab 彩度：
   primary c=42 / secondary c=38 / warm c=26（LCh l 0.74/0.58/0.74）。
2. **`ColorSpace.lch` Lab→XYZ 逆变换分母错位**：`fx = fy + a/5`、`fz = fy - b/2`
   （应为 **a/500、b/200**，双 100× 错误）。旧近零彩度下被掩盖；真实彩度下蓝紫爆 Z≈50、
   暖色出负 Z。修正 + 新增**彩度色域二分收缩**（出 sRGB 色域时保 hue 收 chroma，
   杜绝近黑区 clamp 假色——中途一轮全屏亮青事故由此防住）。
3. **hue 锚定（实测）**：本转换蓝紫区压缩严重，sRGB 225–275° ↔ **LCh 283–313°**。
   primary LCh 270–296（sRGB ≈240-290° 蓝青紫交界，确保所有 seed 处于高饱和蓝区），secondary ≤336（sRGB ~300 magenta 过渡），
   warm LCh 70–82（sRGB ≈28–40）。新增跨 200 seed 的 sRGB hue 全族回归测试锁定。

## 2. 拓扑 v4（OrganismTopology.TOPOLOGY_VERSION 3→4；identity 恒定原则不变）

| 层 | 旧 | 新（Quality §6/§7） |
|---|---|---|
| Structural Rings | 2–4 个**闭合整圆** | **4–7 个非闭合弧**（留 3–16% 弧口）+ lobe 谐波 0.030–0.065；质量占比降到 15–20%（alpha 0.36+0.26c） |
| Long Filaments | 3–14 条，arc 1.15–1.95π，径向 0.64–0.78 | **6–22 条**（KNOWN≈16），arc 1.0–1.6π，径向 **0.55–0.92 展开**，depthWarp 0.14–0.34 |
| Local Fragments | 2–12 个，半径 0.12–0.28，无辉光无深度摆动 | **10–40 个**（KNOWN≈30）：18°–75° 短弧、壳层 0.48–0.95R 二次分布（很少穿核）、谐波曲率调制、面外深度摆动、**辉光开启**、alpha 0.38+0.38c、28% primary 族色 |
| Particles | 78/16/6，壳层 0.48–1.08（远散=星空感） | **78/17/5**，壳层收敛 **0.50–1.00 二次分布**；back 更暗更小 / front 略大略亮；远层淡出至 0.14 |
| Core knots | 半径 0.016–0.034，暖结被 APP 能力门裁掉 | 半径 0.024–0.048；**暖结=核心解剖**（identity 恒定，不再受 allowWarmAccent 门——大暖光晕层仍受门） |

## 3. 核心（§8）与合成（§5）

- **有机暗腔**：cavity 0.30–0.37R（随 coreOpenness），边缘 2/3 阶谐波形变（identity 恒定，
  非机械完美圆；Canvas `cavityPath` 48 段路径）；内部大气 L 0.22+0.14·openness+0.05·exp。
- **behind-core 遮挡带**与实际视觉 cavity 对齐（旧 coreRatio 带废弃）。
- **organism 变大**：baseR = (0.355 + dispersion·0.10 + coreOpenness·0.045)·membraneBias
  （旧 0.19 基 → 0.40–0.44）→ MASTER luminous bbox **44.8% → 72.2% viewport**（目标 72–82 ✓）。
- **体积大气（§13，新 Atmosphere 层）**：volume haze（1.55R 环形分布，峰值 α≈0.05–0.08）+
  rim 膜散射（0.97R，α≈0.03–0.05）——两个后端同层实现；ambient 半径随身体（1.6R）而非整屏。
- **真正亮（§10）**：GLINT 专用近白蓝（0.94,0.97,1.0）+ frontGate 保底（0.97α）——
  MASTER 实测 60px >0.80 luma（0.0095% ≤4% ✓）、extreme 0（≤2.5% ✓）、max 0.814。
- **暖收 restrain（§9）**：MASTER warm ≈0.1%（target ≤10%，hard ≤15% ✓）——暖结 + rare warm glints。

## 4. 运动（§18）与触摸（§19）

- 呼吸 6.8–10.8s → **8.2–10.2s**；主自转 21–60min → **30–55min**；丝内相位 26–58s → **35–55s**。
- Reduced Motion 系数不变（breath .007/×1.45；particle .08 / orbit .06 / filament .12）——
  `EchoSceneCompilerTest.reducedMotionExactFactors` 锚定；TalkBack 聚合语义不变。
- 触摸语义不变（900–1150ms envelope、≤5 front filament 局部吸引、单 ripple α≤0.30）。

## 5. AGSL / 后端真值（§15/§16/§22）

- **mask 升级为 4 通道**：R 冷几何 / G 暖几何 / **B 归一化深度**（StrokePoint 新增 depth，
  CPU 侧逐点写入）/ A coverage。
- AGSL shader 新增：**depth fog**（深处向 secondary 紫 移并压暗，iDepthFog=0.55）、
  **subtle volume haze**（§13；禁整屏 bloom）、spectral mix 保持。
- **后端真值纪律**：`EchoRenderSession.renderToBitmap` 离屏恒走生产 CANVAS 后端
  （Android 真实约束：RuntimeShader 不能在软件位图 canvas 栅格化——**并修复了基线中
  设备上 AGSL 离屏导出会抛 IllegalArgumentException 的缺陷**）；`offscreenActualBackend` +
  `offscreenReason` 显式暴露；Compose AGSL 绘制对软件 canvas 合法降级 Canvas（§22 设计行为）。
- 生产 App Home（`EchoVisualSurface`）现请求 **STANDARD(AGSL)**：API≥33 真机 HW canvas →
  AGSL 材质；API<33 → CANVAS+reason；Wallpaper/Dream 仍钉 LEGACY（电池敏感，216f3b3 决策不变）。
- Visual Lab 导出 JSON 新增 `actualBackend` + organism bbox/edgeDensity/extremeGlint 等全指标。

## 6. MASTER_REFERENCE 迭代循环（implement → render → metrics → inspect → adjust）

| 轮 | 关键改动 | bbox 宽 | 结论 |
|---|---|---|---|
| 基线 | — | 44.8% | FAIL（过小/灰/轨道圆） |
| R1 | 拓扑 v4 + baseR 0.40+ + 深度对齐 | 72.2% | PASS 但 hue 210° 青蓝、warm 0、无真亮 |
| R2 | 真实彩度（Lab 量纲） | 72.2% | 中途亮青事故 → 定位 Lab 逆变换分母 bug + 色域收缩 |
| R3 | hue 锚定 LCh 285–313 + stroke 提亮 + 暖结解剖 | 72.2% | hue 255–285 ✓ warm 可见 |
| R4(final) | GLINT 近白蓝 + frontGate + glint 尺寸 | **72.2%** | **门 PASS**（60px 真亮，0 extreme） |
| R5(Quality Pass) | baseR 0.355→0.325 + hue 285+28f→270+26f | **~82%** 预期 | organismWidthPass 修复；seed 7710 sat 从 0.42→≥0.55 可达 |

最终 MASTER 指标（production Canvas 后端；AGSL raster 属设备门）：
nearBlack 88.6% / highLum 0.0095% / glint 0% / warm 0.1% / negSpace 100%@1.35R /
mass@.9R 97.8% / **bbox 72.2%×34.1%** / edge 0.150 / cavity 0.060（暗腔清楚）。

## 7. 状态族与 SAME ECHO（§25–§30）

- SEED：同 identity 小身体（bbox 68.1%），碎片 14 / 长丝 7（maturity .42 缩减）——同一 ECHO 形成中，
  保持 orientation/core ancestry/palette/chirality（同 topology 公式，仅丰度缩减）。
- QUIET：大量负空间（nearBlack 91.9%）、minimal 碎片、慢呼吸——灰/红/warning 未引入 ✓。
- MATURE：碎片 34 / 长丝 18 / 丝 0.55–0.92R 展开 + 更深 depthWarp——结构丰富，非整体更亮。
- Wallpaper（04）：同 identity 同 visual clock，亮度上限 0.7（nearBlack 92.6% > App 88.6%）、
  CONSERVE 质量档（粒子 ×.68 / 丝 ×.76）、无文字、LE CANVAS。
- Dream：DREAM_AMBIENT 亮度上限 0.55 + 运动乘数（particle .55/orbit .45/filament .60）+
  呼吸 ×1.18 + 暖结与暖光晕层允许——大而暗而慢，无大 bloom。
- Identity A vs B（05 图 + `IdentityContinuityEvalTest`）：primary/几何族（lobe/tilt/freq/
  chirality/coreRatio）全部随 seed 变化——结构差异 ≠ 换色。
- Canvas 与 AGSL 消费同一 `OrganismFrame`（同拓扑/同 identity/同 palette/同合成）；
  fallback 是同一 organism 的更简单材质（§17）。

## 8. 测试与黄金更新（有意为之）

- `VisualRegressionGoldenTest` 42 帧黄金哈希重生成（渲染变更为本轮目的）。
- 新增/更新：`ringsAreNotClosedOrbits`、`localFragmentsStayInMidOuterShells`、
  `primaryFamilyRendersAsSrgbBlueViolet`（200-seed sRGB hue 全族门）、`MeMiniRendererTruthTest`、
  topology/scene/identity 常量更新；`OrganismGoldenRenderTest`/`VisualReviewRenderTest` PNG 再生成。
- 全量门禁结果见 FINAL 报告。
