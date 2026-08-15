# Memory / Self Model Quality Eval（ERA 23 / Batch 3）

> Memory 不只是检索库——长期目标是形成 ECHO 的用户自我模型。
> 全部 eval 位于 `:feature:qa`（`QaMemorySelfModelTest`），确定性可重放。

## 1. EchoSelfModel（§32）

六域快照（`EchoSelfModel`）：Rhythm（稳定模式）/ Context（上下文例外）/ Preferences /
Corrections / Stable Patterns / Interaction Preferences。由记忆确定性构建（`buildSelfModel`）；
不是一个巨大 JSON，而是结构化域 + 自然语言行（`echoKnowsLines`，供 What ECHO Knows）。

## 2. Stable Pattern Promotion（§33）

- Observation(1) → **OBSERVED** → (2) → **CANDIDATE** → (≥3) → **CONFIRMED**；
- **确认不是永久真理**：确认后每轮仍被新证据/纠正/时间重新评估；
- 锚定：一次纠正即 WEAKENING（置信显著下调），两次 → CONFLICTING；
- 矛盾历史跨轮保留（不与旧状态静默合并覆盖）；
- 长期无支持且有矛盾史 → OUTDATED（保留历史不删除）；OUTDATED 后出现新鲜确认证据 → 复活为 CONFIRMED（§35「等待更多证据」的出口）。

## 3. Pattern Confidence（§34）

confidence = f(次数, 时间跨度(≥14 天加分), 一致性(最弱证据拖低), 上下文例外覆盖(×0.8),
用户纠正(×0.5), 新鲜度(逐日衰减))。每个因子都有确定性锚点测试。

## 4. Contradiction Handling（§35）

新证据/纠正与旧 Pattern 冲突 → **标记** WEAKENING / CONFLICTING / OUTDATED，绝不静默覆盖；
等待更多证据（复活路径）。确定性矛盾信号 = 用户纠正原文包含模式内容。

## 5. Memory Consolidation（§36）

many short → one consolidated：≥4 条同归一化键的 OBSERVATION → 一条
「过去 N 天持续观察到：…（M 次）」+ 源记忆 id 清单；20 条 → 1 条；输出上限 5 条；
<4 条不合并。语义级合成由 SYNTHESIZE_MEMORY 任务在 AI 层接力。

## 6. Memory Privacy（§37）

- `EchoMemory` 新增显式 `sensitivity`（PERSONAL / SENSITIVE）；
- 仓储映射：CORRECTION → SENSITIVE（用户自述原因，不进锁屏/公开叙事）；
- `echoKnowsLines` 只输出 PUBLIC 可展示行——纠正原文（SENSITIVE）不出现在 What ECHO Knows。

## 7. Context Exception Quality

- 结构化格式往返（kind/note/date）与非法日期不伪造（null date）；
- 非格式内容解析失败 → null（不编造）；
- fixture 驱动：D 出差窗口 → CONTEXT 记忆携带真实日期 → 自模型上下文域可见，
  且模式置信因特殊时期覆盖而打折。

## 8. 门禁

`:feature:qa` 76 测试全绿（Batch 1-3 累计）；app 844（跑批）；detekt 干净。
