# ERA 31 Round 24 报告（Ask ECHO 依据诚实化：不再一律「参考了：历史画像」）

> 日期：2026-08-15。

## 走查发现

走查 Ask ECHO 的「依据」双清单（v2 §51 信任契约）发现：`DeterministicPersonalAnswerProvider`
对**所有**确定性回答硬编码 `usedSources = [PORTRAIT_HISTORY]`——于是：

- 「我纠正过你的那次，后来你改了吗？」→ 依据显示「参考了：历史画像」（实际用了你纠正过我的记录）；
- 「我说过最近在出差，这有没有影响？」→ 依据显示「参考了：历史画像」（实际用了你告诉我的特殊日期）；
- 「你还记得我确认过的那些事情吗？」→ 依据显示「参考了：历史画像」（实际用了你确认过的偏好）。

用户点开「依据」看到的来源与回答真正用到的东西对不上——信任契约（gate #10）在
最该精确的地方失真。

## 修复

1. **`PersonalAnswer` 增 `usedSources`**（默认 PORTRAIT_HISTORY——节律/画像族如实）；
2. 上下文/纠正/确认族如实覆盖：
   - `travelContext`：无窗口 → 空（「没有找到上下文」不谎称用了数据）；
     有窗口 → `[CONTEXT_EXCEPTIONS, PORTRAIT_HISTORY]`；
   - `correctionsRecall` → `[USER_CORRECTIONS]`（无纠正 → 空）；
   - `confirmedRecall` → `[PREFERENCES]`（无确认 → 空；映射与 EvidenceAssembler 同源：
     USER_CONFIRMED → PREFERENCES）；
   - `confirmedWeekendCheck` → `[PREFERENCES, PORTRAIT_HISTORY]`（用户自述 + 观察对拍）。
3. Provider 由硬编码改为**透传引擎标注**；UI「依据」渲染不动
   （dataSourceLabelForConversation 已有 CONTEXT_EXCEPTIONS/USER_CORRECTIONS/PREFERENCES 标签）。

## 回归

- 引擎 `answerSourcesAreHonest`（+1）：出差上下文 / 纠正回放 / 无上下文空来源 /
  节律族默认，四档锚点。
- 提供者 Robolectric 测试补断言：旅行上下文回答的来源包含 CONTEXT_EXCEPTIONS。

## 实测

- Android 1026 全绿（+1）+ detekt + `:app:lintDebug` PASS。
