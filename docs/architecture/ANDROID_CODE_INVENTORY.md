# Android Code Inventory —— 架构清单（ERA 12 Consolidation）

> 状态：CURRENT · 生成：v0.9.0 基线（2026-08）· 本文件是 Android 代码库的**唯一架构分类索引**。
> 分类：`NEW_CORE`（新世界核心）/ `MIGRATING`（过渡中）/ `LEGACY`（旧时代）/ `DELETE_CANDIDATE`（待删）/ `INFRASTRUCTURE`（横切基础设施）。
> 规则（v3 §35）：不能因为「可能以后有用」而永久保留 LEGACY；每个 LEGACY 必须有 migration target + removal condition。

## 1. UI 层

| File | 当前职责 | 目标 | 归属领域 | 分类 | 迁移目标 / 移除条件 |
|---|---|---|---|---|---|
| `ui/EchoMindApp.kt`（120 行） | 三世界 Shell（ECHO/Journey/Me）+ 紧急 FAB | EchoAppRoot 精简 Shell（navigation/scaffold/safety/lifecycle only） | app | MIGRATING | 已近目标；移除残留 import（SkillListScreen 等） |
| `ui/EchoSceneScreen.kt`（314 行，实测） | ECHO Scene 组合层 | 已完成（ADR-024）：Screen 只组合 ViewModel + components | echo | NEW_CORE | ✅ 拆解完成（此前文档误写 888 行；ERA 12.9 修正为实测 LOC） |
| `ui/echo/EchoSceneViewModel.kt`（164 行）+ `ui/echo/EchoSceneUiState.kt`（109 行） | 单一 UI 状态 + 纯函数装配 + 运行时协调消费 | §12 结构（headline/why/conversation/actions/intelligence 子状态） | echo | NEW_CORE | — |
| `ui/echo/components/*`（277 行合计：visual/headline/status/why/conversation/actions 组件族） | Scene 组件族 | 保持组件化 | echo | NEW_CORE | — |
| `presence/EchoSceneRenderers.kt`（194 行） | 帧模型渲染适配（Compose + Canvas） | presence 包（渲染器归位，ADR-024 已完成迁移） | presence | NEW_CORE | — |
| `ui/EchoActionOverlay.kt`（122 行） | Scene 内呼吸/暂停覆盖层 | actions/EchoActionRuntime 已建立；Overlay 状态经 Runtime 注入 | actions | MIGRATING | 下轮随 ERA 13 actions 收口 |
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
| actions | `actions/*`（EchoActionRuntime 60 行，ERA 12 已建立）、`ui/EchoActionOverlay.kt` | NEW_CORE | Overlay 状态经 Runtime 注入；ERA 13 actions 收口 |
| journey 应用层 | `journey/JourneyRepository.kt`（应用服务）+ `journey/JourneyPort.kt`（数据端口）+ `journey/JourneyUiState.kt`（状态+纯函数装配）+ `journey/JourneyEvent.kt` + `journey/JourneyTrendState.kt`（七态纯逻辑） | NEW_CORE | ERA 13：Screen → ViewModel → Repository → 数据实现；ArchitectureBoundaryTest 断言 journey 不依赖 ui |
| journey UI | `ui/journey/JourneyScreen.kt`（293 行，实测）+ `ui/journey/JourneyViewModel.kt`（168 行）+ `ui/journey/JourneyEvidenceView.kt`（142 行） | NEW_CORE | Screen 只组合：Scale selector / Visual Memory River / Narrative / Evidence；无 LaunchedEffect 编排 |
| me 应用层 | `me/MeUiState.kt`（MeUiState + 装配器 + 分组输入）+ `me/MeEvent.kt`（5 事件面）+ `me/FlowCombine.kt`（combine7/8 助手） | NEW_CORE | ERA 13.1：根页面摘要 + 子领域状态纯函数装配 |
| me UI | `ui/me/MeScreen.kt`（124 行，实测）+ `ui/me/MeViewModel.kt` + `ui/me/DataAndSensingViewModel.kt`（§33）+ `ui/me/IntelligenceSettingsViewModel.kt`（§34）+ `ui/me/PresenceSettingsViewModel.kt`（§35）+ `ui/me/MemoryManagementViewModel.kt`（§36） | NEW_CORE | Screen 只组合七卡；无 LaunchedEffect 业务编排；权限请求（UI 平台职责）与业务（VM）分离 |

## 3. 基础设施

| File | 职责 | 分类 | 处置 |
|---|---|---|---|
| `AppContainer.kt`（417 行，实测） | God Container（DB/Security/API/Sensing/Portrait/Presence/AI/Memory/Skills/Sync/Flags） | MIGRATING | 子容器已拆（Core/Sensing/Presence/Intelligence/Memory，ADR-024）；ERA 13.3 真 DI ownership（子容器自持构造） |
| `AppPreferences.kt`（437 行） | 全部 SharedPreferences 状态 | INFRASTRUCTURE | keep（拆分优先级低；键已分组注释） |
| `PassiveSensingPrefs.kt` | DataStore 开关 | INFRASTRUCTURE | keep |
| `EchoDatabase.kt` + DAOs | Room v9 全实体 | INFRASTRUCTURE | keep；migration audit 批次 3 |
| `data/*`（Sync/Outbox/Worker/ServiceRevocation/FeatureFlag/Subscription） | 同步与订阅基础设施 | INFRASTRUCTURE | keep；SyncWorker/看门狗/刷新 Worker 已定型 |

## 4. 已删除（v2 成果，防回归锚点）

- `ui/TodayScreen.kt` → 已重构为 `EchoSceneScreen.kt`（无残留）
- `SkillListScreen`（旧「能力」Tab 全页）→ 已删除（v2-1）

## 4.5 物理模块（ERA 13.5 §49，逐次拆分）

| 模块 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| `:app` | 应用壳 + 全部业务源码 | :feature:actions / :core:security | 主模块 |
| `:feature:actions` | actions/*（EchoActionRuntime/InterventionPolicy） | kotlinx-coroutines（零项目依赖） | ✅ 第一批 |
| `:core:security` | security/*（AndroidKeystoreFieldCipher/FieldCipher） | Android 框架（零项目依赖） | ✅ 第一批 |
| `:core:model` | model/* | 待 CapabilityState/SensingCapability 纯枚举迁入 | 下一批 |
| `:feature:memory` / `:feature:observation` | memory/* / sensing+localportrait+model | 待 model 拆分 | 第三批 |

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

1. EchoSceneScreen 拆分 ✅ 已完成（ADR-024，314 行组合层）→ 所有 Portrait 主流程由新组件覆盖；
2. Trend→Journey ✅、Support→Me ✅（旧命名已删除）；
3. SkillCardHost 订阅能力 → 保留于 Scene 分区或迁 Me 子领域（依赖 ADR 裁决，不永久双轨）；
4. LegacyScreens 常量 → 随 DeprecatedInputRemovalTest 更新删除；
5. backend 410 存根 → 机构历史数据只读保留（migration compatibility，最终决策）。
