# ADR-0001 — ECHO Visual Runtime 架构与设计稿冲突裁决

- 状态：Accepted（visual-runtime 分支）
- 日期：2026-08-16
- 决策者：首席 Android/Graphics/Interaction Engineer（用户全权授权）

## Context

需将最新视觉设计（`设计稿/*.png`）转化为确定性、低功耗、跨 Surface 一致的 Visual Runtime。现状：
- `feature:presence` 已有确定性算法内核（SplitMix64 identity 派生、七维 Identity Genome、四层 genome、LCG 帧模型、SurfaceMode、自适应帧间隔、壁纸停渲）。
- 但 renderer 只是「单环 + 圆点粒子 + 径向渐变」，远未达设计稿的 filament 网状膜 / orbital field / 多层 glow / ripple 层次。
- 视觉算法目前散落在 `feature:presence`，未独立成 `core/visual`，UI 与算法边界不够硬。

设计稿冲突：稿 5/8 含「情绪：平静 / 能量 62% / 专注 68%」、稿 6 含「情绪识别与语音分析」、稿 9 含「情绪/能量/专注折线」——违反 PORTRAIT/AFFECTIVE 冻结契约与「不显示无 Ground Truth 数字」。

## Decision

1. **新建 `android/core/visual` 纯 Kotlin 模块**（无 Android 依赖，JVM 可测），承载 model/math/noise/motion/render/surface/testing。core/visual **不依赖** Observation implementation，只消费 `EchoPresenceState` 与 `EchoVisualGenome`。
2. **渲染管线冻结为**：`EchoPresenceState → EchoVisualMapper → EchoVisualGenome → SurfacePolicy → EchoVisualSpec → Renderer`。UI Screen 不得直接根据 Observation 特征决定视觉。
3. **渲染技术选型：Canvas 优先，shader 作 progressive enhancement**。`core/visual` 输出纯数据帧模型（`EchoSceneFrame`/分层结构）；Compose Canvas 与 android.graphics.Canvas 两个 adapter 共用同一帧模型。GPU shader（AGSL/GLSL）只作为高配设备的视觉增强，**不作为产品正确性依赖**。
4. **设计稿冲突裁决：剥离违规元素**。保留 organism 视觉与版式节奏，移除一切无 Ground Truth 数字与心理标签；情绪/能量/专注折线不进 Journey 主视觉，数据图表只进 Evidence。能量/专注/情绪 KPI 不实现。
5. **复用而非重写**：现有 `EchoIdentity.kt`（identity 派生）、`VisualProfile.kt`（mapper）、`computeEchoSceneFrame`（帧模型）作为算法基础下沉/迁入 core/visual，渲染层在其上扩展为 9 层 organism。

## Alternatives

- 直接用 AI 生图/静态 PNG：拒绝（违反确定性、低功耗、可重建、不可运行时降级）。
- GPU shader 为主渲染路径：拒绝（minSdk 26 设备覆盖与功耗不可控，违反「平台正确性优先」）。
- 在 feature:presence 内继续膨胀：拒绝（模块边界不清，无法保证 core 不依赖 Observation）。

## Consequences

- 正向：视觉算法集中可测、跨 Surface 一致、确定性可重建、功耗可控、契约红线由架构保证。
- 代价：需迁移现有 presence 视觉代码并保留向后兼容；旧 Compose 渲染器最终删除（Migration 阶段）。
- Golden tests 必须覆盖 renderer 行为，算法变更需显式审核。

## Migration

保留 `feature:presence` 现有公共 API 作为过渡门面；`core/visual` 就绪后逐 Surface 切换，最终删除旧 renderer（详见 §27 Cleanup）。
