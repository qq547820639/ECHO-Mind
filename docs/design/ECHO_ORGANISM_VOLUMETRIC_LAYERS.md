# ECHO_ORGANISM_VOLUMETRIC_LAYERS — 体积层系统规格

> 版本：1.0 · 状态：Accepted（Organism Visual Breakthrough 轮，分支 `agent/organism-visual-breakthrough`）
> 本文记录 v0.11 Organism Visual Breakthrough 引入的体积层系统；视觉宪法
> （`ECHO_VISUAL_CONSTITUTION.md`）语义不变——本文只是其「结构与空间深度」条款的运行时实现规格。

## 一、层系统（帧模型 → 渲染顺序）

`OrganismFrame` 在既有层（rings/filaments/fragments/cavity/knots/particles/membrane-ring）
之上新增四个体积原语，渲染顺序（Canvas/Compose/AGSL 三后端一致）：

```
01 ambient field（近黑 deep-navy 径向场，1.6R）
02 atmosphere（volume haze + rim scattering）
03 far halos
04 ground rings + reflection glow（下方空间能量场；Wrist 移除）
05 back volume lobes（depth < .45 的 nebula 云底）
06 core glow（心脏光）
07 structural rings / long filaments / local fragments（3-pass 丝材质）
08 mid volume lobes（.45–.82 主云体——彩色发光面积主体）
09 cavity absorption（22–31% organism 直径的柔边暗腔）
10 front volume lobes（> .82 前景云，部分遮暗腔）
11 core strands + knots（含 3 个发射结=生命火种）
12 particles（BRIGHT/GLINT 带辉光晕）
13 organic membrane（fill + outer haze + edge scattering + cyan rim）
14 ripples / warm accents
```

## 二、确定性纪律（不变式）

1. **lobe/membrane/ground-ring 拓扑 identity 恒定**：位置/半径/色族/谐波来自
   identitySeed 稳定盐（6050–6799 盐段，与既有盐段两两不相交）；数月不换位。
2. **Moment 只做漂移**：呼吸脉动（独立相位）、慢漂移、强度调制——全部是 clock 纯函数；
   禁止 frame random（`Random()`/`Math.random()`/墙钟毫秒）。
3. **同 fixture 恒同帧**：`OrganismDeterminismTest` / `VisualRegressionGoldenTest`
   （42 锚点帧哈希）锁定。
4. **体积层 alpha 单层很低**（~0.12–0.3）：多层叠加成云；「大量中等亮度彩色 volume +
   极少真正耀眼的 glint」是设计语言（宪法 §二 + Breakthrough §7）。

## 三、色族

| 族 | LCh | 用途 | 份额 |
|---|---|---|---|
| primary | L .47–.52 / c 62 / h=identity 270–296 | 主云、丝、膜 | lobe 32% |
| secondary | L .41 / c 56 / h=identity +12–26 | 紫罗兰云层 | lobe 35% |
| cyan accent | L .62–.67 / c 100(裁剪) / h 198–222 | 电光青高光云、膜亮缘、发射结 | lobe 33% |
| glint 白 | (0.97, 0.985, 1.0) luma≈.86 | GLINT 粒子、发射结（唯二「真正亮」） | 极少 |
| warm | identity warm（L .74 / c 26） | 暖结/暖高光/Dream 增量 | <5% 面积（宪法） |

高 chroma 依赖 `ColorSpace.lch` 的二分色域收缩：请求 c 100 时自动取该 L/hue 下
最大可达 sRGB 饱和度——这是中亮度蓝紫族不灰化的关键机制。

## 四、状态表达（同 identity，不同强度）

| 状态 | 机制 |
|---|---|
| SEED | lobe 数 ~12（KNOWN ~20 / MATURE ~22）；膜变形 ×1.6（野生） |
| LOW_DATA | dataClarity × 体积 alpha 与丝可见度（更薄更模糊，色族不变） |
| QUIET | 低湍流/慢运动（genome 层） |
| NIGHT | mapper 昼夜曲线 → luminance → 曝光响应（体积 ×0.45+0.55e） |
| DREAM | surface 亮度上限 0.55 + 曝光熄火（发射结/心光收敛）+ 暖 accent |
| WALLPAPER | 亮度上限 0.7 + LEGACY/CONSERVE（电池敏感面，决策不变） |
| WRIST | lobe 减半 + alpha ×0.75 + 无地面环（SECOND BODY 压缩，identity hue 保留） |

## 五、AGSL 材质（STANDARD/ADVANCED）

- 双 mask：`iVectorMask`（R 冷几何/G 暖/B 深度）+ `iVolumeMask`（A lobe 覆盖/G 膜·地环
  边缘 tag/B lobe 深度）——AGSL 是**材质升级**，与 Canvas 共享同一帧/拓扑/identity。
- FBM：4-octave value noise + domain warp；`cloudDensity = lobeCov ×(0.42+0.85×fbm)`；
  噪声相位 = identityPhase×.61 + dayComposition×.23 + clock×.004（确定性）。
- 失败安全：API<33 / RuntimeShader 编译失败 / 软件 canvas → Canvas 后端（同一 organism）。

## 六、机器艺术门（防 wireframe 回归）

`VisualLabMetrics`（JVM 门，`CanvasFallbackVisualGateTest` 执行）在既有门
（nearBlack≥58% / highlight≤4% / glint≤2.5% / warm≤15% / negSpace≥40% / mass≥82% /
bbox 72–82% / cavity）之上新增：

```
heroMeanLuminance        0.10–0.20
heroBrightRatio          ≥ 0.10
chromaticLuminousRatio   ≥ 0.30
meanChromaticSaturation  ≥ 0.55
centralVolumeCoverage    ≥ 0.30
wireframeDominance       ≤ 0.30   （5×5 网格腐蚀代理；基线线框球 ≈0.46）
```

像素级复核工具：`scripts/visual/analyze_organism.py`（JSON/CSV/Markdown 输出）。
本轮完整 Before/After：`qa/reports/ORGANISM_VISUAL_BREAKTHROUGH.md`。

## 七、性能预算（JVM 实测锚点）

compute 0.77ms / canvas 全体积栈 ~92ms（软件光栅）/ AGSL 双 mask ~17ms /
热路径分配 0KB/100帧。`OrganismQualityPerfTest` 预算：50ms / 2000ms / 800ms / 32MB。
GPU shader 执行与帧率 = 真机项（instrumented / 设备门）。

## 八、已知的未竟事项

- AGSL FBM 材质的最终观感需 Android 13+ 真机复核（JVM 验证到 shader 编译 + mask 真值）。
- 「真正亮」像素（>0.8 luma）当前 ~0.1% hero：SS7 的 2–6% 代理区间与 SS10/SS28
  「真正亮必须极少」冲突，按后者裁决；人工复核若要求更亮核心，优先调大发射结半径。
