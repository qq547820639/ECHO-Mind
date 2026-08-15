# Self Model Value Audit（BATCH 3 §26/§27）

> ERA 31 BATCH 3 第 1/2 项：对每一项 Self Model 信息问——
> 它是否改变：未来 reasoning / 未来 retrieval / Journey / Presence / Action / What ECHO Knows？
> 全部回答否 → 删除或降级。审计日期：2026-08-15（ERA 31 R4）。

## 结论总表

| Self Model 项 | 生产消费方 | 判定 | 处置 |
|---|---|---|---|
| `rhythmPatterns`（StablePattern 生命周期） | `echoKnowsLines` → What ECHO Knows UI（confirmedPatterns/challengedPatterns 两行） | ✅ 有消费方 | 保留 |
| `contexts`（ContextExceptionInfo） | `echoKnowsLines`（「你告诉过我：…」）；原始 CONTEXT 记忆另被 PersonalAnswerEngine 直接消费 | ✅ 有消费方 | 保留（快照视图） |
| `preferences` | `echoKnowsLines`（「你说过：…」） | ✅ 有消费方 | 保留 |
| `corrections` | `echoKnowsLines` 的空态判断（敏感内容不公开展示）；原始 CORRECTION 记忆被 PersonalAnswerEngine 直接消费 | ✅ 有消费方（最小） | 保留（敏感边界） |
| `interactionPreferences` | **无**（仅 QaMemorySelfModelTest 一条断言） | ❌ 无消费方 | **已删除**（ERA 31 R4）：交互类偏好并入 `preferences` |
| `generatedAt` | 无直接消费（快照元数据） | 元数据 | 保留（provenance 语义） |

## 关键发现

1. **`interactionPreferences` 是死数据**：`buildSelfModel` 用 INTERACTION_KEYWORDS 切出「提醒/通知/视觉…」
   偏好子集，但生产代码无任何读取点（只有一条 QA 断言）。已删除字段与切分逻辑，
   交互类偏好直接并入 `preferences`（What ECHO Knows 展示不变性更好：用户确认过的都说）。
2. **Self Model 目前只服务 What ECHO Knows**：reasoning 链（EchoContextRetriever → Compiler）
   与 PersonalAnswerEngine 直接消费原始记忆（CONTEXT/CORRECTION/USER_CONFIRMED），
   不经过 Self Model——这是正确的分工（原始记忆是 provenance，Self Model 是展示快照），
   不构成重复实现，审计确认后保持不变（不做「为统一而统一」的重构）。
3. Pattern Promotion 状态机（Observation→Candidate→Confirmed→Weakening→Conflicting→Outdated）
   由 `promotePatterns` 驱动，消费方为 `confirmedPatterns`/`challengedPatterns` 展示 +
   QaMemorySelfModelTest——「ECHO 忘掉过去的我」已由生命周期覆盖（PatternState.OUTDATED）。

## 已执行修改

- `feature/memory/EchoSelfModel.kt`：删除 `interactionPreferences` 字段、INTERACTION_KEYWORDS 切分；
  doc 注释 6 域 → 5 域；`preferences` 合并语义。
- `QaMemorySelfModelTest`：删除死断言，锁定「交互类偏好并入 preferences」新语义。

## 下一步（BATCH 3 剩余）

- ✅ §27 Pattern contradiction（ERA 31 R5）：用户可见验收测试落地——`QaMemorySelfModelTest.outdatedPatternIsPresentedAsChallengedNotCurrentTruth`
  （旧模式 60+ 天无新证据且曾被纠正 → OUTDATED → knowsLines「有些出入」，不再当当前事实展示）。
- §29 Memory consolidation：**决策 = 暂不接线**。`consolidateObservations` 保持纯函数 + 测试；
  现有 decay/expiry/purge Worker 已保证记忆不无限增长；语义合成属于 SYNTHESIZE_MEMORY（AI 层）。
  触发条件：dogfood 实测 OBSERVATION 记忆数量成为真实问题（如 >1k 且检索变慢）时再接线，不提前建设。
- What ECHO Knows 人类语言 / edit·forget·confirm UX：列 Batch 3 后续。
