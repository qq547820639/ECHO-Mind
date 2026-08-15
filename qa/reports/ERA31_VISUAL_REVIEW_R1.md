# ERA 31 Round 1 — Real Render Review 报告（BATCH 1）

> 日期：2026-08-15。原则：不再只看参数和 Markdown——真实渲染 production 帧管线并做机器代理评审。
> 人眼画廊：`qa/visual-review/index.html`（浏览器打开，237 张 PNG + 27 张对比拼图）。
> 本轮是「看真正的 ECHO → 修真正的视觉问题」的第一次闭环。

## 1. 交付物（真实 Render，非报告）

| 工件 | 位置 | 数量 |
|---|---|---|
| 场景帧 PNG | `qa/visual-review/rendered/<PROFILE>/dayNNN/scene_*.png` | 7 profile × 6 天 × 5 表面 = 210 |
| 参数快照 + 状态解释 | 同目录 `snapshot.md` | 42 |
| 对比拼图 | `qa/visual-review/rendered/sheets/*.png` | 27（不同用户×同天 / 同用户连续性 / 成熟度演进） |
| 画廊首页 | `qa/visual-review/index.html` | 1 |
| 真机 Wallpaper 清单 | `qa/visual-review/DEVICE_CHECKLIST.md` | 部署侧执行手册 |

渲染链全部走 production：`QaTimeline`（长程 fixture）→ buildLocalBaseline → AmbientEngine →
computeLifeSeason → LifeSeasonTracker → deriveIdentityGenome → EchoVisualMapper →
`computeEchoSceneFrame` → production `renderEchoFrameToCanvas`。QA 没有重写产品。

## 2. 机器代理评审（人眼必答题的像素级代理）

`VisualReviewRenderTest.c_humanQuestionsHavePixelLevelProxies` 锁定：

- **同一用户跨天**：Day 0 与 Day 180 accent 主色 RGB 恒同（色相属于 Identity）→「同一个 ECHO」✅
- **不同用户**：7 个用户 accent 至少 4 种 + **结构签名（纹理族/环数/流线量）至少 4 种** →「明显不同」✅
- **帧非纯色**：全部帧采样 ≥100 种像素 ✅

⚠️ 机器代理是底线不是人眼。人眼结论仍须打开画廊确认（见 §5 人眼流程）。

## 3. 真实画面审计发现并修复的缺陷（本轮核心）

### 3.1 缺陷一：粒子场从未在视觉上存在（VISUAL_DEFECT）

`sceneRandom(seed, index)` 旧实现 `index.toLong() shl 32 and 0x7FFFFFFF`——`shl 32` 后低 32 位全零，
被 `and 0x7FFFFFFF` 全部抹掉：**所有粒子拿到同一随机数、同一角度、同一位置**，全部堆叠成一个点。
用户看到的 ECHO 实际只有「核心光斑 + 环 + 一个绕行小点」，粒子纹理从未出现过。

- 影响：Wallpaper 生命感（Part 7「看 5 秒是否有生命感」）结构性缺失；不同用户差异只剩颜色与环。
- 修复：`sceneRandom` 改为 `seed xor (index * -7046029254386353131L)`（金角乘法保留全 64 位 index 熵），
  后续 LCG 不变——确定性保持，粒子真实散布。
- 回归：`IdentityStructureVisibilityTest.particleFieldHasRealSpread`（≥90% 粒子位置互异）。

### 3.2 缺陷二：身份差异几乎只靠颜色（VISUAL_DEFECT / Part 17 违反）

帧计算不消费 `textureFamily`（只有 5% 密度微调）、`orbitGeometry`（只存不用）、
`contrast`/`structureComplexity`（死参数）。两个用户的 ECHO 结构完全同构，只差色相——
Part 17 明确要求差异来自 motion personality / topology / orbit / texture / structure，**不是换颜色**。

- 修复（`VisualProfile.kt` / `EchoSceneRenderers.kt` / `EchoIdentity.kt`）：
  - `seedTextureFamily(seed)` / `seedOrbitGeometry(seed)` 提取为纯函数，identity 派生与帧计算同源；
  - 纹理族进入帧模型：0 柔光 / 1 微粒 / 2 流线（轨道切向拖尾）/ 3 环晕（额外远环）；
  - 轨道几何：0 = 环状（粒子紧贴主环）→ 1 = 弥散（径向散布 + 椭圆化）；
  - 结构丰富度：次级同心环 0–3 条；对比度：背景边缘加深。
- 回归：`IdentityStructureVisibilityTest` 5 用例（含「同 seed 颜色恒同但结构可区分」——
  结构差异不得靠颜色冒充）。
- 视觉回归黄金集已随有意变更重新生成（`VisualRegressionGoldenTest`，42 锚点 + 可粘贴重生成输出）。

### 3.3 Scene 信息密度修复（PRODUCT_DEFECT 消解，见 `qa/reports/ECHO_SCENE_AUDIT.md` §3.6）

- Journey 入口、「问 ECHO」入口：OutlinedButton → TextButton（按钮墙 → 安静入口）；
- Why 证据行去卡化（无边框事实行）；EchoStatusOverlay AI 提示去卡化（信任信息保留）；
- 紧急支持保持常驻 OutlinedButton（安全资源不弱化）。

## 4. 本轮其他交付

- `docs/product/ERA31_FELT_PRODUCT_REALITY.md`：ERA 31 产品实施宪章（开发门/架构冻结/QA 原则/指标/批次）。
- `docs/release/RELEASE_BASELINE.md`：DEVELOPMENT_HEAD ≠ LAST_RELEASE_BASELINE 正式区分
  （baseline = `5783036`，v0.9.0 发布终检；HEAD = +10 commits 起 + 本轮）。
- `docs/DEVELOPMENT_STATUS.md`：当前 HEAD / 能力 / QA / Known issues / Next slice。
- `qa/reports/QA_MIRROR_AUDIT.md`：QaPortraitMirror（必要镜像，加跨语言黄金门待办）/
  QaHeadlineEngine（真重复，BATCH 7 下沉）/ QaAskEcho（eval oracle，非 mirror）。
- 构建环境修复：机器 JDK 缺失 → 安装 Temurin 17.0.20（与 CLEAN_ROOM_REPRODUCTION 文档一致）；
  `:feature:qa` 增 Robolectric（dependency lock 已同步重写）。

## 5. 人眼评审流程（每次视觉改动后执行）

1. 打开 `qa/visual-review/index.html`；
2. `users_APP_day180.png`：7 个用户不看名字能否一眼区分？→ 现在差异来自纹理/轨道/结构 + 色相；
3. `continuity_APP_<PROFILE>.png`：Day 0→180 是否同一个 ECHO 在长大？
4. `users_WALLPAPER_day90.png`：5 秒生命感 / 1 小时不烦 / 值得留 7 天？
5. 结论写回本文件 §6。

## 6. 人眼结论记录（待人工确认）

- 本轮机器代理 PASS；**人眼确认待执行**（部署侧 / 用户在本 GUI 打开画廊即可作答）。
- 已知边界：静态帧无法体现 motion character（流线方向/速度）——动画捕获列下一轮（若环境允许），
  真机清单由 `DEVICE_CHECKLIST.md` 承接。
