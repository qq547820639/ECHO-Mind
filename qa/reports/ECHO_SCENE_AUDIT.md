# ECHO Scene 产品质量审计（ERA 20）

> 目标：「打开手机里的 ECHO」而不是「打开分析 Dashboard」。
> 审计对象：`app/src/main/java/com/yunjue/echo/mind/ui/EchoSceneScreen.kt` 及其子组件。
> 审计日期：2026-08-15（Product Quality Era Round 1）。

## 1. 视觉层级（ERA 20 §7）

| 层级 | 现状 | 判定 |
|---|---|---|
| 第一视觉 ECHO Visual Organism | `item { visualSurface() }` 是 LazyColumn 第一项 ✓ | ✅ 达标 |
| 第二视觉 一句状态表达 | headline 在 Why 层（READY 后可见）；早期为 SeedPortraitBlock/「初见」 | ✅ 达标（本次已把 AI 叙事从「覆盖 headline」改为「增量 AI 层」） |
| Why / Ask ECHO / Action | 按序在下方；入口均为小按钮 | ✅ 达标 |

## 2. Dashboard 感指标盘点（ERA 20 §8）

| 元素 | 数量 | 位置 | 判定 |
|---|---|---|---|
| 显式 `Card` | 1（周小结）+ N 张 Why 证据卡 + EchoStatusOverlay 卡 | Scene 中段 | ⚠️ 周小结已去卡化（本次）；Why 证据卡在「展开」后出现，可接受 |
| `OutlinedButton`（可见边框） | 4（Journey、紧急支持、问 ECHO、查看更多→Journey） | 中下段 | ⚠️ 建议后续改 TextButton（Journey/问 ECHO） |
| `LinearProgressIndicator` | 2（BaselineProgress、CoverageRow） | WARMING_UP/EARLY 分支 | ✅ 保留（Day 0-7 基线形成期有真实产品价值） |
| 工程状态文案 | EchoStatusOverlay（感知状态） | 第 3 项 | ⚠️ 有信任价值（用户主动关闭感知时必须可见）；样式去卡化列入后续 |
| 庆祝仪式 | UnlockBanner（基线解锁卡片） | READY 首日 | ✅ 已改为安静一行字（ERA 20 §10：不弹庆祝） |
| 图标密度 | 无图标堆叠 | — | ✅ 达标 |
| 标签/指标密度 | BaselineProgress「已积累 N/7 天」+ 覆盖率 % | 早期分支 | ✅ 仅在学习期出现，可接受 |

## 3. 本次已修复（Round 1）

1. **AI 叙事不再覆盖确定性 headline**（ERA 20 §9）：`EchoSceneUiState` 改为三层结构——
   - Layer 1 = 确定性 headline（产品真值，永远保留）；
   - Layer 1.5 = `aiLayer`（AI 增量理解，仅在内容确实不同时展示）；
   - Layer 2 = facts 证据。
2. **学习期文案**（ERA 20 §10）：SEED「初见。」/ DISCOVERING·EMERGING「我开始看到一些属于你的节奏。」/ KNOWN「我开始认识通常的你了。」；不再出现「ECHO 还在了解今天。」的冷启动兜底。
3. **基线解锁仪式去庆祝化**：UnlockBanner 从 primaryContainer 卡片改为安静一行字。
4. **周小结消息去卡化**。
5. **成熟度单一真值**：ECHO Scene 的 maturity 改为来自 Presence（日历语义），修复 weekday 分桶下 MATURE 永不可达的问题。

## 4. 后续计划（后续 Round）

- EchoStatusOverlay 去卡化（保留信任信息，去除边框）；
- Journey/问 ECHO 入口改 TextButton；
- Why 证据卡默认收起已达标；展开态卡片改为无边框行；
- 紧急支持入口保持常驻（安全资源，不做视觉弱化）。
