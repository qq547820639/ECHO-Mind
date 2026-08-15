# ERA 32 R07 — Memory / Journey 规模与维护审计（Batch F/E 预检补全）

> 2026-08-15。§58 Memory Scale（1k/5k/20k）、§59 Journey Scale（365/1000 天）、
> §31 consolidation 决策、§60 维护链——逐项审计 + 补齐主提示明确要求的缺口用例。

## 1. §58 Memory Scale 审计结论

| 路径 | 现状 | 判定 |
|---|---|---|
| 检索（Ask ECHO / Context Compiler） | DAO `observeMemories` SQL 侧按 importance DESC 排序 + `LIMIT` 变体；ContextRanker 复合排序后由 policy.maxMemories 封顶（编译上下文三重上限） | 无「load all → JVM sort」热路径 |
| rankMemories（纯函数） | 复合排序（typePriority → decayScore → importance → lastConfirmedAt），1k/5k/10k 护栏已有 | ✅ |
| purgeExpired | R63 全量扫描（正确性优先，修复过 LIMIT 截断遗漏）+ R64 单轮 2000 条上限（Worker 不超时） | ✅ 有界 |
| derivePatterns | 内容哈希幂等 id；维护周期内执行；O(n) 分组 | ✅ |
| **20k 护栏** | **本轮补齐**：`twentyThousandScaleGuardrails`（rank/sweep/derive 各 < 12s，数量级退化可辨） | ✅ 新增 |

## 2. §59 Journey Scale 审计结论

- 365 天：Year View 装配 + 完整 UI 状态装配双预算（< 2000ms）已有；
- **1000 天：本轮补齐** `journeyUiStateAssembly1000StaysUnderBudget`（< 5000ms）——
  覆盖 R06 新增的期间故事/月前对比装配增量在千天尺度不退化；
- Canonical state / 聚合周期 / 懒渲染缓存架构（§108 两段式装配）不变。

## 3. §31 Memory Consolidation 决策复核（维持 R5 审计决定）

- 生产路径不存在 episodic OBSERVATION 写入循环（唯一写点是用户反馈/上下文/纠正——数量天然有界）；
- 增长已由 retention 层级（7/30/365 天）+ purgeExpired + derivePatterns 幂等覆盖；
- **consolidation 合成模板继续不接线**——触发条件仍是「dogfood 实测记忆增长成为真实问题」
  （R5 决定；本轮复核无新证据推翻）。

## 4. §60 维护链核实

`PresenceRefreshWorker` → `PresenceMaintenanceScript` 顺序固定（refresh → snapshotToday →
purgeExpired → derivePatterns），各段 runCatching 隔离——单段失败不阻断其余；
真机长跑验证（放几周）仍属外部门（Batch B）。

## 5. 验证

- 新增 2 个规模护栏测试全绿（20k 记忆 / 1000 天 Journey）；
- Android 全模块单测 + detekt + lint 全绿（数字见 STATUS §3 自动段）。

## 6. 下一轮

环境内可执行批次已全部收敛（A/C/E 预检/F 预检/G/H）。剩余全部依赖外部真实输入
（真机 Batch B / dogfood Batch D / 真实数据深化的 E·F）。持续工作 = 每轮 Delete Review +
答案复核复跑 + 治理纪律维持。
