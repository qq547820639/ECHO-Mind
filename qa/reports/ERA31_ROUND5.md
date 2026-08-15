# ERA 31 Round 5 报告（BATCH 3 收口 / BATCH 4 起步）

> 日期：2026-08-15。

## 1. BATCH 3 §27 — 「忘掉过去的我」用户可见验收

Pattern Promotion 生命周期（OBSERVED→CANDIDATE→CONFIRMED→WEAKENING→CONFLICTING→OUTDATED）
已有单元覆盖，缺的是**用户看到的验收**。新增
`QaMemorySelfModelTest.outdatedPatternIsPresentedAsChallengedNotCurrentTruth`：

- 旧模式（「工作日晚间结束时间持续后移」）60+ 天无新证据 + 曾被用户纠正 → OUTDATED；
- What ECHO Knows 必须说「之前关于…的判断最近有些出入，我还在观察」；
- **不得**再把旧模式当当前事实说「我观察到：…」。

即：人类模式改变后，ECHO 真的会「忘掉过去的我」——并且用户能看见这个过程（信任面，gate #7）。

## 2. BATCH 3 §29 — Memory consolidation 决策

`consolidateObservations`（raw→consolidated 确定性模板）**暂不接生产**：

- 现有 decay / expiry / purge Worker 已保证记忆数量不无限增长；
- 语义合成属 SYNTHESIZE_MEMORY（AI 层），确定性模板只是半成品，接线没有真实用户问题驱动；
- 触发条件写死：dogfood 实测 OBSERVATION 记忆成为真实问题（>1k 且检索变慢）时再接线。

（Part 4：不新增无产品问题对应的机制；provenance 语义保留在函数契约中。）

## 3. BATCH 4 §30/§31 — Journey 第一视觉修复

审计发现：Journey 打开后第一屏是**免责文案 + 分隔线**，视觉记忆河流排在其后——
违反「Journey 第一眼 = 看见自己的时间」（§30）。

修复（`JourneyScreen.kt`）：免责文案（契约点 2，单测锚点保留）移到页面**底部**以 bodySmall
安静呈现；打开 Journey = 标题 → 尺度选择 → **视觉记忆河流**（第一视觉）→ 长期叙事 → 依据层。
河流优先结构（river → narrative → evidence）本来正确，本轮把唯一挡在第一视觉前的法律文案挪开。

## 4. 实测

- Android qa + app 套件全绿（含新增 forgetting 验收 + JourneyScreenSmokeTest 免责锚点仍通过）；
- 全量套件 + detekt + lint 见最终计数。
