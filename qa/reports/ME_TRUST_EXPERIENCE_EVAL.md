# Me / Trust Experience Eval（ERA 25 / Batch 5）

> Me 的目标不是 Settings，而是 Personal Intelligence Control Center：
> 用户必须真的知道 ECHO 知道什么、使用什么，且能纠正/确认/忘记/删除。

## 1. 首页信息架构（§44）

Me 根页面重新排序（`MeScreenContent` 槽位顺序 + smoke 顺序锚定）：
危机入口（安全常驻，契约冻结）→ **ECHO Presence** → **What ECHO Knows** →
**AI Intelligence** → **Data & Sensing** → Subscription → Support → About。
用户的 ECHO 与它知道什么排在最前，工程配置沉底。

## 2. What ECHO Knows（§45）

- 接入 Batch 3 的 `buildSelfModel` + `echoKnowsLines`：七层计数上方新增
  「用一句话说」自然语言摘要（「我观察到：…」「你告诉过我：…」）；
- **无工程标识符泄漏**：knowsLines 不得出现 baseline_activation_start /
  active_start_minute / z-score 等（状态层 eval 锚定）；
- fixture 驱动：B（夜猫+漂移）→ 「我观察到：活跃起点通常在中午前后（已持续 4 次观察）」
  +「你告诉过我：工作紧张期（…起）」；
- 敏感纠正原文（SENSITIVE）不进公开摘要（信任边界锚定）。

## 3. AI Data Usage（§48）

会话层每条回答已有「依据」双清单（✓ 参考了：今天的活动节律 / 个人基线 / 你纠正过我的…
○ 没有使用：麦克风、通知正文、精确位置）——§48 的 used/unused 透明已落地
（`ConversationTurn.sources` 自 ERA 7 起随回答流转）。

## 4. Provider Transparency（§47）

- 主视图只保留 Provider / Model / Status / 测试连接 / 断开连接；
- **Base URL 移入「更换 Provider」展开区**，加「Advanced（高级）」标题——
  Base URL/模型名/API Key 属高级配置，不在主视图堆叠（smoke 双锚定：默认不渲染 + 展开渲染）。

## 5. User Control（§46）

已有并保持：纠正（会话层「不太像」+ 原因 → Correction Memory）、确认（记忆行确认权）、
忘记（软删保留审计）、编辑/固定（单条记忆五行权）、暂停（Data & Sensing 感知开关）、
关闭某类数据（麦克风独立开关）、删除 Memory（记忆行）、删除本地数据（Data & Sensing 申请删除）。

## 6. 门禁

- Me/Intelligence/WhatEchoKnows smoke 全绿（含新增：§44 顺序 DFS 锚定、§47 Advanced 分离）；
- `:feature:qa` 87 测试全绿（Batch 1-5 累计）；app 844（跑批）；detekt 干净。
