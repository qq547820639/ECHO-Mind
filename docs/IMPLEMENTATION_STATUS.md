# ECHO Mind Implementation Status

> 本文件是长期自主演进的**唯一状态锚点**（v2 §92 格式）。
> 最高产品原则：`docs/product/ECHO_PRODUCT_CONSTITUTION.md` + Master Prompt v1/v2。

## Current Era

**ERA 12 — Consolidation 完成 ✅**（批次 1 + 批次 2 全部落地）
下一步：ERA 13 — Architectural Modularity（依赖图已收口，可开始 Gradle 物理模块化）。

## Current Product Surface

- 一级导航：**ECHO（EchoSceneScreen）/ Journey（JourneyScreen）/ Me（MeScreen）** —— 三世界唯一架构
- ECHO Scene：组件化（VisualSurface/StatusOverlay/WhyLayer/ConversationLayer/ActionLayer）+ EchoSceneViewModel
- Me：六子领域（Subscription / Support / Data & Sensing / Presence / Intelligence / What ECHO Knows）
- 运行时：EchoRuntimeCoordinator（六态 + **EchoRuntimeHealth**）+ EchoConversationController + EchoCorrectionService + EchoActionRuntime

## Completed Vertical Slices

- v1 ERA 1-10 / v2 第一二轮 / ERA 12 批次 1+2（见 ADR-024 与 RELEASE_NOTES）

## In Progress

- 无。

## Architecture Decisions

- ADR-001~024（最新：ADR-024 ERA 12 收口）

## Legacy Remaining（全部有 owner + removal condition；旧命名已退出主路径）

| 模块 | 分类 | 处置 |
|---|---|---|
| `ui/journey/JourneyScreen.kt` 内 `@Deprecated TrendScreen` 委托 | DELETE_CANDIDATE | 无任何 route 调用（EchoMindApp 已指向 JourneyScreen）→ 下一轮删除 |
| `ui/SkillCardHost.kt` + `SkillActionRenderers.kt` | LEGACY | ECHO Scene「更多能力（订阅）」分区使用；去留随订阅能力演进裁决 |
| `ui/LegacyScreens.kt`（3 常量） | LEGACY | DeprecatedInputRemovalTest 锚点；随测试更新删除 |
| backend 410 存根 | keep | 机构历史只读（最终决策） |

## Tests / Build

- Android：**454 tests 全绿**（含 5 项架构边界测试）；assembleDebug/lint/detekt 全 PASS
- backend：1070 passed + 1 skipped（本轮未动 backend）

## Next Highest-Value Task

ERA 13：先出 dependency map（子容器已分组）→ 首个拆出 module 候选 `:feature:presence`（渲染器无 Room/Provider 依赖，边界测试已断言）→ 逐步 intelligence/memory；不一次性拆全部。

