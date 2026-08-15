# Product Simplification Eval（ERA 26 / Batch 6）

> 从现在开始持续做：Delete more。历史数据可以保留，UI 不一定保留。

## 1. 旧心理健康产品遗产审计（§49）

| 遗产 | 现状 | 判定 |
|---|---|---|
| questionnaires（问卷） | 仅 DB 表 `questionnaire_results`（迁移保留）与同步 schema；**无 UI** | ✅ UI 已移除；历史数据按 §50 保留 |
| journals（日记） | 仅 DB 表 `journal_entries`（迁移保留）与同步 schema；**无 UI** | ✅ 同上 |
| checkins（打卡） | `practice_completions` 仅迁移层 | ✅ 同上 |
| Skill marketplace | `SkillListSection` 已收敛为 Action 层（BREATHING/PAUSE 两个动作）；SkillCardHost 仅为动作宿主 | ✅ 基本符合 §52「真正有价值的变 Action」；内容型技能未上架 |
| subscription-specific UI | 订阅区独立在 Me 底部（非 ECHO 懂不懂我的开关） | ✅ §51 方向正确（本地模式免费价值完整） |

## 2. AppContainer 瘦身（§62）

- 11 个 Room Migration（MIGRATION_1_2 … 11_12，约 250 行）从 `AppContainer.kt` 移出到
  `data/database/EchoDatabaseMigrations.kt`；AppContainer 只组合六容器 + Room builder。
- DatabaseMigrationTest / HardeningV061Test / Instrumented 迁移测试引用同步更新，全部编译通过。

## 3. core:ports 依赖卫生（§58/§59）

- **修复 core → feature 反向依赖**：`EchoMemory` / `MemoryType` / `RetentionClass` /
  `MemorySensitivity` 契约类型上移到 `core:model`（EchoMemoryContract.kt）；
  `core:ports` 改为只依赖 core:model（**不再依赖 feature:memory**）。
- 渐进迁移（§60）：feature:memory 以 `typealias` 桥接旧 import，全仓零破坏；
  后续步骤（记录在案）：EchoPresenceState/SensingRuntimeStatus 上移后，ports 可完全脱离 feature。

## 4. 门禁

- app 844 + qa 87 + presence 16 全绿（跑批）；detekt 干净；backend 无变更。
