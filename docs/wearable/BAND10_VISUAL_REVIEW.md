# BAND10 SIMULATOR / 静态视觉验收（Phase 10）

> 事实源纪律：本环境无 AIoT-IDE 模拟器、无真机（BLOCKED_EXTERNAL_BAND10_DEVICE）。
> 本文件 = 以官方尺寸 **212 × 520** 为基准的**静态逐项验收**（代码级布局预算 + 状态矩阵）
> + **渲染级验收**（`wearable/xiaomi-vela/tests/simulator/` QA mirror，ERA 33 R2 实测），
> 并锁定设计约束；真实渲染/触感验收在真机执行 `qa/` 设备清单时完成，不伪造 PASS。

## 0. 渲染级验收（ERA 33 R2 实测，headless Chrome 212×520）

26 态（13 态 × zh/en）由真实生产模块（computeVisual / presence_cache / i18n）生成并渲染：

- **0 边缘裁剪**：全部状态内容 bbox 严格位于 212×520 内（像素级检查，四边均无内容触边）；
- **0 文本截断**：headline / WHY / Action 提示 zh+en 全部 `clamp=false`（LAYOUT 探针实测）；
- **identity 连续性**：同一 identity 的 accent（rgb(103,154,230)）在 SEED→DISCOVERING→KNOWN→
  MATURE→QUIET 全部呈现且居中（质心 x≈105.4）；LOW_CONFIDENCE / DISCONNECTED 降光后
  **仍保持同一色相**（质心 x≈105.7/105.6）——降级不换身份、不随机换色；
  EMPTY（无缓存）用中性色 rgb(190,180,160)——不编造新身份；
- 生成器内置 212×520 布局预算断言（元素直径/粒子越界 → 生成 FAIL），并进入 android-ci。
- 触觉开关透传（HAPTICS_ON 态）正常渲染；振动硬门在代码层（action 页 vibrateShort/Long）
  由 Node 测试锁定。

工具：`node wearable/xiaomi-vela/tests/simulator/generate.js`
（详见 `wearable/xiaomi-vela/tests/simulator/README.md`）。

## 1. 设计基准（官方事实）

| 项 | 值 | 来源 |
|---|---|---|
| 分辨率 | 212 × 520 px（1.72" AMOLED） | XIAOMI_BAND10_CAPABILITY_MATRIX §0 |
| designWidth | 212（manifest.config.designWidth） | wearable/xiaomi-vela/src/manifest.json |
| 布局原则 | 窄带纵向布局；禁止把 Android UI 缩小复制 | 能力矩阵 §0 |

## 2. 状态矩阵（静态验收）

| 状态 | 腕上表现 | 验收结论 |
|---|---|---|
| SEED | 手机推送 SEED 投影 + 允许清单 headline（"初见"系）；organism 以 identity/moment 渲染 | PASS（静态）：headline 短文本 ≤2 行 |
| DISCOVERING / EMERGING | 同上（"认识中"系 headline） | PASS（静态） |
| KNOWN / MATURE | 同上（"认识你 / 同行"系 headline） | PASS（静态） |
| QUIET | surface.motionLevel=QUIET → intervalMs=1500；organism 慢速 | PASS（静态）：无裁剪（预算见 §3） |
| LOW_CONFIDENCE | 不伪造：无置信度文案；moment 低 flow/coherence → 更淡、更少粒子（particleCount 2..6） | PASS（静态）：克制 |
| DISCONNECTED / 无缓存 | `empty` phase：中性 organism（core 44px）+ "ECHO 还在了解今天。"（**不是**红色 ERROR、不随机换色换身份） | PASS（静态）：无红色样式（preflight 锁定） |
| BREATHING | action 页呼吸核 44px 正弦节奏（8s 循环 / 60s 自动结束）；同手机 EchoActionRuntime | PASS（静态） |
| PAUSE | action 页提示文案 + 结束按钮 | PASS（静态） |

## 3. 裁剪 / 溢出预算（212 × 520）

echo 页（纵向堆叠）：
- time-area：margin-top 60 + 时间 44px + headline/hint ~20px ≈ 130px；
- organism：212×212，margin-top 40 → 底部 ≈ 380px；
- 内部元素极值：orbitRing 直径 ≤ 2×(52+26×1+10×(1-coherence)) ≤ 176px；
  glow 直径 ≤ 2×(44×(1.4+0.8×1)) ≈ 194px；particle 最远 x = 106+88−5 = 189px、y ≈ 187px —— **全部 < 212**；
- action-entry：absolute bottom 40 → 顶部 ≈ 440px，与 organism 底部（≈380px）不重叠。

why 页：左右 padding 28 → 文本宽 156px；headline 20px、max-lines 4；
允许清单 headline（手机 PUBLIC_SAFE 白名单，见 WearablePrivacyProjector）均 ≤ 2 行 —— 无溢出。

action 页：菜单按钮 180×56px（触摸目标可用）；呼吸核 44px；结束按钮 ≥ 40px 高。

中文/英文：i18n zh/en/defaults key 集合一致（preflight 锁定）；页面引用的全部 key 存在（preflight 锁定）。
en 文案最长串 "Follow the rhythm, breathe slowly."（31 字符）在 156px 宽 / 17px 字号下 ≈ 2 行 —— 无溢出。

## 4. 第一屏纪律（ECHO first）

- 打开即是：TIME + **ECHO organism**（身份/节奏/色彩来自手机同一 ECHO）+ 一行公开表达或轻触提示；
- 没有 button-first / dashboard-first / 列表优先；
- 空缓存也显示 "ECHO 还在了解今天。" 与中性 organism —— 绝不显示 "ECHO Wrist App" 风格启动页。

## 5. 结论

| 项 | 结论 |
|---|---|
| 静态视觉验收（212×520 预算 + 状态矩阵 + 双语 + 第一屏纪律） | PASS（本文件 + tests/preflight.js 锁定） |
| 渲染级验收（QA mirror + headless Chrome 212×520：0 裁剪 / 0 截断 / identity 连续） | **PASS（ERA 33 R2 实测，§0）** |
| AIoT-IDE 模拟器实渲染 / 真机视觉 | BLOCKED_EXTERNAL_AIOT_IDE_PACKAGING / BLOCKED_EXTERNAL_BAND10_DEVICE |
