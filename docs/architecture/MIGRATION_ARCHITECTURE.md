# Migration Architecture —— 从 Portrait Core v0.7 到 Personal Ambient Intelligence

> 状态：v1.0 · 渐进迁移总纲 · 原则：**不丢弃用户已有数据；不重写已经正确的东西；大规模演进必须渐进。**
> 位置：`docs/architecture/MIGRATION_ARCHITECTURE.md`

## 1. 迁移总原则

1. Observation Core 是 Ground Truth Layer，**原样保留并继续演进**，不是旧包袱。
2. 新模型（EchoSelfModel / EchoPresenceState / Memory）**建立在现有数据之上**：基线、聚合、画像都是它们的输入，不是要替换掉的东西。
3. 每次变更：明确 version → 明确 migration → 明确 opt-in（涉及新数据类）→ 更新 Contract → 增加 tests。
4. 仓库始终可构建；每个 ERA 交付完整 vertical slice（domain + data + UI + tests + migration + error handling + docs）。

## 2. 现有数据资产 → 新模型映射

| 现状（v0.7） | 去向 | 动作 |
|---|---|---|
| `feature_vectors`（Room，22 维派生特征） | RhythmModel / BehaviorModel 的证据源 | 保留；不迁移 |
| `daily_behavior_aggregates`（服务端）/ `LocalDayAggregate`（端侧） | RhythmModel 的每日事实 | 保留；Context Compiler 的 evidence 来源 |
| `personal_baselines` / `LocalBaselineCalculator`（28 天 robust） | RhythmModel 核心 | 保留；EchoNow 的「通常」来源 |
| `daily_portraits` / Room `portrait_daily` 缓存 | Journey 的视觉记忆单元 + Narrative 素材 | 保留；ERA 8 生成 visual snapshot 索引 |
| `consents` + consent 证据链 | Me 权限展示 + 新 opt-in 模板 | 保留；Affective/Provider 新 consent 复用同模式 |
| 画像反馈（`portrait_feedback` + Outbox） | Correction Memory 的第一批数据 | **迁移**：补 reason 字段 → Correction Memory（ERA 6） |
| `baseline_snapshot_digest` | EchoIdentityGenome 的种子来源之一 | 复用（不新建） |
| `sensingActive` 布尔 + `CapabilityState` | SensingRuntimeStatus 六态的输入 | **重构**（ERA 1）：布尔降级为原始输入 |
| onboarding 七态状态机 | 保留（激活生命周期），与 SensingRuntimeStatus（运行态）正交 | 不迁移，语义分层 |

## 3. 新数据 schema（增量，不破坏旧表）

| 新对象 | 存储 | 迁移 |
|---|---|---|
| `EchoPresenceState` 快照 | 进程内 StateFlow + SharedPreferences 单条快照 | 无迁移（可重算） |
| `firstAwakenedAtEpochMs` | SharedPreferences 新键 | 老用户回填：取本地最早 feature window 日期 |
| `EchoMemory`（ERA 6） | Room 新表 `echo_memories`（id/type/content/source/confidence/createdAt/lastConfirmedAt/importance/retentionClass/provenance） | 迁移 8→9 纯加表；Correction Memory 从 portrait_feedback 回填 |
| Provider credentials（ERA 4） | Keystore 加密独立存储（复用 AndroidKeystoreFieldCipher 模式） | 无迁移；与个人数据逻辑隔离 |
| Affective state（ERA 10） | 新表 + 独立 consent 类型 | 必须 opt-in；不自动从画像链迁移任何推断 |

## 4. 现有用户回填策略（关键）

- v0.7 老用户已完成 onboarding → 看不到新 ECHO Awakening。回填：`firstAwakenedAtEpochMs = 本地最早特征窗口日期`，maturity 按「今天 - 该日期」推导（SEED/DISCOVERING/EMERGING/KNOWN/MATURE），不强行重放授权。
- 基线数据原样可用 → Day7 仪式沿用 `baselineUnlockedShown` 机制（不重复触发）。
- 订阅/激活码语义不变；ERA 后续的订阅档位变更另走产品评审（不属本迁移）。

## 5. 迁移与兼容纪律

- 数据库：沿用 Room Migration 链（当前 head=8），只做纯加表/加列，**禁止**重写既有 migration 历史（`MIGRATION_1_2 … MIGRATION_7_8` 全部保留）。
- Android 兼容：minSdk 26 不动；高版本能力（Wallpaper destination、Dream 等）全部 feature detection 降级。
- 契约兼容：`PORTRAIT_CONTRACT.md` 作为 PORTRAIT_CONTRACT_V1_OBSERVATION 永久保留；新契约是父集，不是替换。
- 用户数据权利：删除入口必须覆盖新数据类（Memory / Provider credentials / Affective），支持分项删除（Personal data / Memory / AI conversation / Provider credentials / Everything）。

## 6. ERA 迁移依赖顺序

```text
ERA 1（Constitution & Trust：状态语义统一）── 无数据迁移，纯重构 + 新键
ERA 2（ECHO Scene：EchoPresenceState）── 快照可重算，无迁移
ERA 3（Presence）── 无数据迁移
ERA 4（BYOM）── 新增加密 credential 存储（隔离）
ERA 5（Context Compiler）── 无数据迁移（只读既有证据）
ERA 6（EchoSelfModel & Memory）── 新增 echo_memories 表 + feedback 回填
ERA 7（Conversation）── 新增会话存储（可删，不进备份）
ERA 8（Journey）── 新增 visual snapshot 索引（由确定性渲染生成，可重算）
ERA 9（Actions）── 复用 skill session 存储 + 偏好扩展
ERA 10（Affective）── 独立表 + 独立 opt-in consent（模板复用 voice_features 闭环）
```
