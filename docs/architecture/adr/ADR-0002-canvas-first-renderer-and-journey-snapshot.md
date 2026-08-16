# ADR-0002 — Canvas 优先渲染、Journey Snapshot 存储格式、跨 Surface 接线

- 状态：Accepted（visual-runtime 分支 R2–R6）
- 日期：2026-08-16
- 决策者：首席 Android/Graphics/Interaction Engineer（用户全权授权）

## Context

Visual Runtime 需在 minSdk 26 的广泛设备上确定性、低功耗地渲染 9 层 organism，并支持
Journey 每日重建、跨 Surface（App/Wallpaper/Dream/Wrist）一致。设计稿为 art direction，
需落地为运行时代码而非贴图。

## Decision

1. **渲染技术：Canvas 优先，shader 作 progressive enhancement。**
   - `core:visual`（纯 Kotlin，仅依赖 `core:model`）输出纯数据帧模型 `OrganismFrame`；
   - 两个 adapter 消费同一帧：Compose Canvas（`EchoOrganism`，APP Scene）与
     `android.graphics.Canvas`（`OrganismCanvasRenderer`，Wallpaper/Dream/离屏 golden）。
   - 不引 GPU shader 作为主路径：minSdk 26 覆盖与功耗不可控，违反「平台正确性优先」。
   - AGSL shader 可作为高配设备的 ambient glow 增强，但不作为产品正确性依赖。

2. **模块边界：`core:visual` 不依赖 Observation implementation。**
   - 渲染管线冻结为 `EchoPresenceState → GenomeDeriver → EchoVisualGenome → SurfacePolicy.crop → EchoVisualSpec → OrganismFrameComputer → adapter`。
   - UI Screen（EchoVisualSurface）只渲染 `EchoPresenceState`，不直接根据 Observation 特征决定视觉。
   - `feature:presencevisual` 承载 Android/Compose adapter，与纯算法的 `core:visual` 分离。

3. **Journey Portrait 存参数不存图（EchoPortraitSnapshot）。**
   - 保存 date + genome 快照 + identity/composition revision + evidenceSummaryRef + version；
   - 不存 AI 生图、不存 PNG；同一天由 `PORTRAIT_CANONICAL_TIME_SECONDS` 固定时间点确定性重建。
   - 日期相位用 `dayNumber(年*372+月*31+日)` + 取模小数（**不用** `String.hashCode()`——相邻日期低位碰撞导致帧相同，已修复）。

4. **坐标系：归一化坐标 + minDim 各向同性半径。**
   - 渲染端 x×W、y×H；圆形半径以 minDim 为基准 → `isoX=minDim/W`、`isoY=minDim/H`，
     保证 organism 球体在任意宽高比下不变成梭形。

## Consequences

- 正向：确定性可重建、跨 Surface 同一 identity、低功耗可控、core 与 Observation 解耦、可 JVM 测。
- 代价：旧 `EchoLifeField`/`computeEchoSceneFrame`/`journeyDayParams` 与新 organism 暂并存，待 Migration 阶段收敛删除。
- 已知环境限制（非代码问题）：工作区路径含空格触发 AGP 8.13.2 `DexingNoClasspathTransform`
  「file located outside the root directory」bug，导致 `:app:assembleDebug` 在含空格路径失败；
  已在无空格副本验证打包成功。CI 应使用无空格 checkout 路径。

## Migration

- 旧 renderer 仅在被新 organism 完全替代的 Surface 上停止使用；保留至 Golden gates 全绿后删除（§27）。
