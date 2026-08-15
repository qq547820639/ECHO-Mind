# ERA 32 R13 — QA 产品快照 Me 面镜像收口

> 2026-08-15。QA 快照四项 mirror 收口的最后一项（前三项见 R06/R10/QA_MIRROR_AUDIT §7）。

## 1. 问题（与 R06 Journey 同类）

`QaProductSnapshot.me()` 用 baseline 指标自造「你的工作日通常在 09:10 左右明显开始」
「你平时每天屏幕约 N 分钟」等行——**production What ECHO Knows（buildSelfModel +
echoKnowsLines）从不产出这些语句**（生产只产出「我观察到：<记忆内容>（看到过 N 次）」
「你告诉过我：…」「你说过：…」等）。产品预览展示产品不存在的语句 = QA 测的不是产品。

## 2. 修复

- `me()` 改为吃 production 装配：以 profile.specialWindows 合成 CONTEXT 记忆
  （contextExceptionContent 格式）→ `buildSelfModel` → `echoKnowsLines`；
- 时间锚点固定（2026-08-15 epoch），快照保持确定性（R08 的陈旧标注逻辑不因生成时刻漂移）；
- 删除自造行与「ECHO 认识你 N 天了」QA 特有语句。

## 3. 结果（7 profile 快照确定性重生成，净 -96 行）

- D_TRAVEL / E_PROJECT_CRUNCH 等有上下文 profile：「你告诉过我：出差（2026-06-19 起）」——与用户所见同源；
- 无上下文 profile：「我还在慢慢积累关于你的了解。」——production 的诚实兜底，不再展示伪了解。

## 4. 验证

- :feature:qa 全绿（快照套件 / QaMeTrustEval / 黄金门零漂移以外）；
- Android 全模块单测 + detekt + lint 全绿。

## 5. 下一轮

QA 四项 mirror（PortraitMirror 黄金门 / Headline 单点 / AskEcho 薄适配器 / 快照 Journey+Me
production 装配）全部闭环。持续维持性节奏。
