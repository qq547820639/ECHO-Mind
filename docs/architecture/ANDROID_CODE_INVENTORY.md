# Android Code Inventory —— 架构清单（ERA 12 Consolidation）

> 状态：CURRENT · 生成：v0.9.0 基线（2026-08）· 本文件是 Android 代码库的**唯一架构分类索引**。
> 分类：`NEW_CORE`（新世界核心）/ `MIGRATING`（过渡中）/ `LEGACY`（旧时代）/ `DELETE_CANDIDATE`（待删）/ `INFRASTRUCTURE`（横切基础设施）。
> 规则（v3 §35）：不能因为「可能以后有用」而永久保留 LEGACY；每个 LEGACY 必须有 migration target + removal condition。

## 1. UI 层

| File | 当前职责 | 目标 | 归属领域 | 分类 | 迁移目标 / 移除条件 |
|---|---|---|---|---|---|
| `ui/EchoMindApp.kt` | 三世界 Shell（ECHO/Journey/Me）+ 紧急 FAB | EchoAppRoot 精简 Shell（navigation/scaffold/safety/lifecycle only） | app | MIGRATING | 已近目标；移除残留 import（SkillListScreen 等） |
| `ui/EchoSceneScreen.kt`（888 行） | ECHO Scene 全部逻辑 | 拆为 Screen(组合) + ViewModel + components（visual/headline/status/why/conversation/actions） | echo | MIGRATING | **本轮拆解**；拆分后 Screen ≤ 组件组合 |
| `ui/EchoSceneUiState.kt`（ui/echo/） | 单一 UI 状态 + 纯函数装配 | 扩展为 §12 结构（headline/why/conversation/actions/intelligence 子状态） | echo | NEW_CORE | — |
| `ui/EchoSceneRenderers.kt` | 帧模型渲染适配（Compose + Canvas） | 并入 ui/echo/components/EchoVisualSurface 或独立 renderer 文件 | presence | NEW_CORE | — |
| `ui/EchoActionOverlay.kt` | Scene 内呼吸/暂停覆盖层 | ui/echo/actions/EchoActionHost + EchoActionRuntime | actions | MIGRATING | 本轮迁入 actions runtime |
| ~~`ui/TrendScreen.kt`~~ | — | `ui/journey/JourneyScreen.kt`（唯一实现）+ `ui/JourneyState.kt`（纯状态函数） | journey | **DELETED** | ✅ ERA 12 已删除（含 @Deprecated 委托）；JourneyDomain 独立装配 |
| ~~`ui/SupportScreen.kt`~~ | — | `ui/me/MeScreen.kt`（根页面）+ 六子领域（Subscription/Support/DataAndSensing/Presence/Intelligence/WhatEchoKnows）；真正支持内容 = `ui/me/SupportSection.kt` | me | **DELETED** | ✅ ERA 12 已删除；测试锚点函数迁 `ui/MeSupportHelpers.kt` |
| `ui/OnboardingScreen.kt` | 三步 onboarding + Awakening | 保持（app 领域） | app | NEW_CORE | — |
| `ui/SkillCardHost.kt` | Skill 卡片 + rememberSkillList（Scene「更多能力（订阅）」分区用） | 订阅能力宿主；SkillListScreen 已删除 | actions/me | LEGACY | 移除条件 = 订阅能力迁移到 Actions runtime 或保留为 Me 子领域（ADR 裁决） |
| `ui/SkillActionRenderers.kt` | Skill 动作渲染器 | 同上 | actions | LEGACY | 随 SkillCardHost 一并处置 |
| `ui/SafetyScreen.kt` | 危机入口页 | 全局安全入口（不属于任何 tab） | app | NEW_CORE | — |
| ~~`ui/LegacyScreens.kt`~~ | — | `DeprecatedInputRemovalTest` source-scan 断言（写路径/定位词不存在） | infra | **DELETED** | ✅ ERA 12 已删除 |

## 2. 领域层

| 域 | 文件（代表性） | 分类 | 说明 |
|---|---|---|---|
| observation（Ground Truth） | `sensing/*`、`localportrait/*`、`model/PortraitCore.kt`、`data/SensingRepository.kt`、`data/PortraitRepository.kt` | NEW_CORE | 永久边界：不依赖 intelligence/affective |
| presence | `presence/*`、`data/PresenceRepository.kt` | NEW_CORE | 渲染器不依赖 Room/API/Provider（架构测试断言） |
| intelligence | `intelligence/*` | NEW_CORE | 依赖 observation 接口（EvidenceAssembler），不反向 |
| memory | `memory/*`、`data/MemoryRepository.kt` | NEW_CORE | 不依赖具体 Provider |
| actions | `actions/*`、`ui/EchoActionOverlay.kt` | NEW_CORE | EchoActionRuntime 本轮建立 |
| journey | `journey/*`（纯视觉）、`ui/TrendScreen.kt`（迁移中） | MIGRATING | JourneyRepository/BuildJourneyUseCase 待建（批次 2） |

## 3. 基础设施

| File | 职责 | 分类 | 处置 |
|---|---|---|---|
| `AppContainer.kt`（376 行） | God Container（DB/Security/API/Sensing/Portrait/Presence/AI/Memory/Skills/Sync/Flags） | MIGRATING | **本轮拆子容器**（Core/Sensing/Presence/Intelligence/Memory），AppContainer 变组合门面 |
| `AppPreferences.kt`（437 行） | 全部 SharedPreferences 状态 | INFRASTRUCTURE | keep（拆分优先级低；键已分组注释） |
| `PassiveSensingPrefs.kt` | DataStore 开关 | INFRASTRUCTURE | keep |
| `EchoDatabase.kt` + DAOs | Room v9 全实体 | INFRASTRUCTURE | keep；migration audit 批次 3 |
| `data/*`（Sync/Outbox/Worker/ServiceRevocation/FeatureFlag/Subscription） | 同步与订阅基础设施 | INFRASTRUCTURE | keep；SyncWorker/看门狗/刷新 Worker 已定型 |

## 4. 已删除（v2 成果，防回归锚点）

- `ui/TodayScreen.kt` → 已重构为 `EchoSceneScreen.kt`（无残留）
- `SkillListScreen`（旧「能力」Tab 全页）→ 已删除（v2-1）

## 5. 所有权 Scope（v3 §41）

| 对象 | Scope | 说明 |
|---|---|---|
| AppContainer（含子容器） | Application | EchoMindApplication lazy 单例 |
| DB / repositories / coordinator / retriever / providers | Application | 进程级单例 |
| Provider credential | Application（Keystore 加密独立文件） | 绝不进 Screen/Worker 临时状态 |
| EchoStateStore / renderer | Process（App+Wallpaper+Dream 共享快照） | Wallpaper/Dream 不初始化容器 |
| ViewModel（EchoSceneViewModel 等） | Screen | 配置变更存活，进程死亡重建 |
| WorkManager Workers | Worker | 经 Application container 访问 |

## 6. Removal Plan 摘要（v3 §33-35）

1. EchoSceneScreen 拆分（本轮）→ 所有 Portrait 主流程由新组件覆盖；
2. Trend→Journey（本轮）+ Support→Me（下轮）→ 旧命名删除；
3. SkillCardHost 订阅能力 → 保留于 Scene 分区或迁 Me 子领域（依赖 ADR 裁决，不永久双轨）；
4. LegacyScreens 常量 → 随 DeprecatedInputRemovalTest 更新删除；
5. backend 410 存根 → 机构历史数据只读保留（migration compatibility，最终决策）。
