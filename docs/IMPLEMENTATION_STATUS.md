# ECHO Mind Implementation Status

> 本文件是长期自主演进的**唯一状态锚点**（v2 §92 格式）。
> 最高产品原则：`docs/product/ECHO_PRODUCT_CONSTITUTION.md` + Master Prompt v1/v2。

## Current Era

**ERA 12 — Consolidation 完成 ✅（v3.1 收尾核查通过）**
下一步：ERA 13 — Physical Modularization（ANDROID_DEPENDENCY_GRAPH 已输出，第一个拆分候选 :feature:presence）。

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
| ~~TrendScreen @Deprecated 委托~~ | **deleted** | ✅ v3.1 收尾已删除（无调用方） |
| `ui/SkillCardHost.kt` + `SkillActionRenderers.kt` | KEEP_AS_CONTENT | 订阅能力内容（SKILLS_TO_ACTIONS.md 已裁决）；不再增长新逻辑 |
| ~~LegacyScreens.kt~~ | **deleted** | ✅ 已删除；DeprecatedInputRemovalTest 改 source-scan 断言 |
| backend 410 存根 | keep | 机构历史只读（最终决策） |

## Tests / Build

- Android：**458 tests 全绿**（含 5 项架构边界 + JourneyDomain 4 + DeprecatedInputRemoval 3 source-scan）；assembleDebug/lint/detekt 全 PASS
- backend：1070 passed + 1 skipped（本轮未动 backend）

## Next Highest-Value Task

ERA 13：先出 dependency map（子容器已分组）→ 首个拆出 module 候选 `:feature:presence`（渲染器无 Room/Provider 依赖，边界测试已断言）→ 逐步 intelligence/memory；不一次性拆全部。

