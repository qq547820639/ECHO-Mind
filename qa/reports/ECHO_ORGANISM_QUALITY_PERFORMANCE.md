# ECHO_ORGANISM_QUALITY_PERFORMANCE — 性能证据

> 迭代：ECHO Organism Quality Pass + Runtime Finish
> 测量环境：本机 JVM（Robolectric 4.14.1 NATIVE graphics = 真实 android.graphics 软件光栅）
> · JDK 17 · macOS arm64 · 2026-08-18。证据测试：`OrganismQualityPerfTest`（stdout）。
> 真机 GPU/帧率/电池/显存 = **BLOCKED_EXTERNAL_DEVICE**（本环境无设备/模拟器）。

## 1. JVM 可测项（MASTER 1080×2340 · NORMAL 质量 · KNOWN Day28）

| 项 | 实测 | 说明 |
|---|---|---|
| CPU 帧求值（几何，OrganismFrameComputer.compute） | **0.79 ms/帧** | 拓扑缓存生效（rings 6 / longs 16 / fragments 30 / particles 103）；远低于 16.7ms@60fps 预算 |
| 热路径分配（100 帧 compute 堆增量） | **≈0 KB** | §32 零分配纪律保持（帧复用缓存拓扑；无逐帧拓扑重建） |
| Canvas raster（LEGACY 正式后端，软件位图） | 72.21 ms/帧 | **JVM 软件光栅 ≠ 设备**：设备上 Compose/Wallpaper 走 HW 加速 canvas；此数只用于模块间相对比较与回归监控 |
| AGSL mask raster（Advanced 材质输入 CPU 成本） | 22.46 ms/帧 | 设备同样为软件位图栅格（Canvas(bitmap) 恒软件）——见 §3 风险与处置 |

## 2. 结构成本对比（vs 基线）

- 拓扑条数增加（rings 3–4→4–7、longs ≤14→≤22、fragments ≤12→≤40）：帧求值仍 **<1ms**
 （采样点总量 ≈ 6×57+16×57+30×21 ≈ 1,632 点/帧 + 103 粒子——纯函数、零分配）。
- 新增层（atmosphere 2 绘制、有机暗腔 path 48 段、碎片辉光 pass）：只增加常数次 draw call，
  Canvas 软件光栅下是主要成本（72ms 中的多数）；HW canvas 下为 2–3 个额外 GPU op。

## 3. AGSL(Advanced) 成本与处置（§36 纪律）

- mask raster **每帧 CPU 22.46ms**（1080×2340 软件位图；分辨率线性）——Advanced 材质的真实
  输入成本；GPU shader 执行还需叠加（设备项）。
- 处置（保守，符合 216f3b3 既定决策）：
  - **Wallpaper / Dream 钉 LEGACY/CANVAS**（电池敏感面不默认 AGSL）——维持不变；
  - **App Home 请求 STANDARD(AGSL)**（屏幕常亮交互面）：API≥33 真机 HW canvas 走 AGSL，
    API<33 自动 CANVAS+reason；**真机功耗/帧预算 benchmark 未完成前，AGSL 视觉/性能结论
    均为 PENDING（BLOCKED_EXTERNAL_DEVICE）**——`AdvancedBackendVisualGate`（instrumented）
    承担设备门；
  - 若真机 benchmark 显示 AGSL 帧预算超标：处置选项已预留在 mask 降频栅格化
    （运动极慢，mask 可 <30Hz 刷新），本轮不预先实现（无设备证据不加复杂度）。

## 4. Reduced Motion（§38 复验）

- 系数不变并由 `EchoSceneCompilerTest.reducedMotionExactFactors` 锚定：
  particle ×.08 / orbit ×.06 / filament ×.12 / breath .007 + period ×1.45；
  `VisualProfileTest.reducedMotionStopsFlow`（flowSpeed=0 硬契约）、
  `WallpaperSchedulerTest`（reduced → 8fps）全绿；TalkBack 聚合语义未动。

## 5. 门禁汇总

| 门 | 结果 |
|---|---|
| Android testDebugUnitTest（全模块） | PASS（全绿，含 wearable 96 / qa / visual / Compose smoke） |
| detekt（27 规则） | PASS |
| :app:lintDebug（安全规则） | PASS |
| backend pytest | PASS（1084 passed + 1 skipped） |
| 真机（API33–35 / API36+ / Pixel / Xiaomi / 热态 / 24h wallpaper） | BLOCKED_EXTERNAL_DEVICE（无设备；清单已在 docs/wearable + DEVICE_CHECKLIST） |
