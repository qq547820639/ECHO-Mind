# ECHO Mind Implementation Status

> 本文件是长期自主演进的**唯一状态锚点**（v2 §92 格式）。
> 最高产品原则：`docs/product/ECHO_PRODUCT_CONSTITUTION.md` + Master Prompt v1/v2。

## Current Era

**ERA 12 — Consolidation（进行中：批次 1 完成 ✅ / 批次 2 进行中）**
目标：新 ECHO 正式成为唯一产品架构；系统性清理结构债务。

## Current Product Surface

- 一级导航：**ECHO（EchoSceneScreen）/ Journey（JourneyScreen）/ Me（SupportScreen→MeScreen 迁移中）**
- ECHO Scene：**已组件化**（EchoVisualSurface/EchoStatusOverlay/EchoWhyLayer/EchoConversationLayer/EchoActionLayer）+ **EchoSceneViewModel**（Composable→ViewModel→Coordinator→Repository 分层）
- 对话：EchoConversationController 状态机（IDLE/COMPILING/WAITING/COMPLETE/FAILED/FALLBACK）+ EchoCorrectionService + EchoActionRuntime
- Journey：`ui/journey/JourneyScreen.kt`（纯状态函数留在 `ui/JourneyState.kt` 保测试锚点；旧 TrendScreen 变 @Deprecated 委托）
- 渲染器：`presence/EchoSceneRenderers.kt`（presence 不再依赖 ui；架构测试断言）

## Completed Vertical Slices

- v1 ERA 1-10 / v2 第一二轮（v0.8.0/v0.9.0）——见对应 RELEASE_NOTES
- **ERA 12 批次 1**：ANDROID_CODE_INVENTORY；EchoSceneScreen 拆解 + ViewModel + 三 Controller/Service/Runtime；Journey 正式迁移（ui/journey）；AppContainer 子容器（Core/Sensing/Observation/Presence/Intelligence/Memory）；渲染器归位 presence；架构依赖方向测试（5 项）；README_AUTHORITY + 文档生命周期治理

## In Progress（ERA 12 批次 2）

- Support→Me 正式迁移（ui/me/ 子领域：IntelligenceSettings/PresenceSettings/WhatEchoKnows/DataAndSensing/Support；SupportScreen 只剩支持内容）
- Legacy 移除清单执行（SkillCardHost 订阅能力去留 ADR 裁决；LegacyScreens 常量随测试更新删除）
- Runtime Coordinator hardening（EchoRuntimeHealth 模型）+ 依赖图收口（ERA 13 前置）

## Architecture Decisions

- ADR-001~023（本轮未新增 ADR 号：拆分/迁移决策记录于 ANDROID_CODE_INVENTORY + README_AUTHORITY；批次 2 完成时补 ADR-024 ERA12 收口）

## Legacy Remaining（全部有 owner + removal condition）

| 模块 | 分类 | 处置 |
|---|---|---|
| `ui/TrendScreen`（@Deprecated 委托） | MIGRATING | route 全部指向 JourneyScreen 后删除 |
| `ui/SupportScreen.kt`（982 行） | MIGRATING | 批次 2 拆 ui/me 后只剩支持内容 |
| `ui/SkillCardHost.kt` + `SkillActionRenderers.kt` | LEGACY | Scene 订阅分区使用；去留随订阅能力 ADR 裁决 |
| `ui/LegacyScreens.kt`（3 常量） | LEGACY | 随 DeprecatedInputRemovalTest 更新删除 |
| backend 410 存根 | keep | 机构历史只读（最终决策） |

## Tests / Build

- Android：**454 tests 全绿**（新增 5 架构边界测试）；assembleDebug/lint/detekt 全 PASS
- backend：1070 passed + 1 skipped（本轮未动 backend）

## Next Highest-Value Task

ERA 12 批次 2：Support→Me 子领域拆分（§27-32）+ Provider/Memory/Data&Sensing 独立页 + Legacy 移除清单收口。

