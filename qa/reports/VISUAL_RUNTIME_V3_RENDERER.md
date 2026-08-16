# VISUAL_RUNTIME_V3 — Renderer 报告

> IMPLEMENTATION_HEAD=`c4ff17be9a116884b4a6e46ca5c07b6e0c615401`（分支 `visual-runtime-v3`）
> 证据级别：JVM/Robolectric 单测实测；GPU/真机项如实标注外部门。

## 1. 管线（单一 production visual pipeline，未分叉）

```text
Observation → Presence（core:model）→ EchoVisualMapper → EchoVisualParameters   [业务链，不变]
EchoPresenceState → GenomeDeriver → EchoVisualGenome → SurfacePolicy.crop → EchoVisualSpec
  → EchoSceneCompiler → EchoRenderPacket（identity/field/material/motion/surface/interaction/time）
  → OrganismTopologyBuilder（§32 缓存）+ MotionEvaluator（§25 时间纯函数）
  → OrganismFrameComputer → OrganismFrame
  → Backend：LEGACY Canvas（drawOrganism / OrganismCanvasRenderer）
            STANDARD AGSL（AgslEchoBackend，API 33+ RuntimeShader）
            ADVANCED（+RuntimeColorFilter final grading，API 36+）
            ULTRA（disabled by capability）
```

## 2. 逐项状态

| 项 | 状态 | 证据 |
|---|---|---|
| Deterministic identity（SplitMix64；禁 Random/UUID/clock） | PASS | `EchoIdentitySpecTest` 5 项（范围/确定性/多样性/调色板族/LCh） |
| Identity 不只靠颜色（≥3 几何维度） | PASS | `EchoIdentitySpecTest.differentSeedsDifferInGeometryNotOnlyColor` + `IdentityDiversityEvalTest`（lobe/tilt/coreRatio/freq 锚点） |
| 三层拓扑（Rings ~20% / Long ~45% / Fragments ~35%） | PASS | `OrganismTopologyTest.threeLayerTopologyExistsWithRoughProportions` |
| 3D filament + behind-core occlusion | PASS | §15 谐波场/稳定 plane basis/perspective + §16 smoothstep 遮挡（`OrganismFrameComputer.sampleStroke`）；截图可见 front/middle/back |
| Fibonacci 粒子（78/16/6 精确分层） | PASS | `OrganismTopologyTest.fibonacciParticlesClassified`（hash 排名分层，非阈值抽样） |
| Hollow core（cavity/atmosphere/2–4 knots/strands/front membrane） | PASS | `OrganismTopologyTest.coreKnotsStableAcrossCalls` + `VisualReferenceGateTest.cavityPass` |
| Canvas fallback（API 26–32 完整） | PASS | 两 adapter 全绿（`OrganismGoldenRenderTest` 等）；LEGACY 为 golden 基准路径 |
| AGSL（API 33+） | PASS（软件）/ BLOCKED_EXTERNAL（GPU 实测） | `AgslBackendTest`（uniform/premult/soft-knee 结构 + 可用性永不抛 + fallback）；真机/模拟器 GPU 验证无设备 |
| API 36 Advanced（RuntimeColorFilter grading） | PASS（软件）/ BLOCKED_EXTERNAL（GPU 实测） | 同上 + API 门控断言 |
| Capability Router（ULTRA 四硬门） | PASS | `EchoSceneCompilerTest.capabilityRouterTiers`（ULTRA 永不只因 API≥37 启用） |
| WCG/HDR 门控（非必要条件） | PASS | `hdrNeverAllowedOnWallpaper` + `EchoRenderEnvironment.isHdrEligible` 门控链；SDR 管线为默认且漂亮（Reference 门全绿） |
| §24 tone（near-black/soft knee） | PASS | `VisualReferenceGateTest` ALL PASS（near-black ≥58% / highlight ≤4% / warm ≤15% / negative-space ≥40% / visual-mass@.9R ≥82% / cavity 清楚） |
| §25–28 MotionEvaluator（共享时间/慢轨道/呼吸窗） | PASS | `MotionEvaluatorTest` 7 项 |
| §29 触摸（Gaussian、≤5 front、1 ripple、不改状态） | PASS | 帧求值内实现 + `interactionEnvelope` 时间线测试；App/Wallpaper 同一 transient ripple |
| §30 Reduced Motion 精确系数 | PASS | `EchoSceneCompilerTest.reducedMotionExactFactors` |
| §31 质量降级（NORMAL/CONSERVE/MINIMAL） | PASS | `qualityProfiles` + topology 丰富度降级测试 |
| §32 缓存（identity/quality/maturity/version 键） | PASS | `OrganismTopologyTest.cacheRebuildsOnlyOnKeyChange` |
| §41/42/43 LOW_DATA / Sensing Disabled / Error 视觉降级 | PASS | clarity 降级系数 + `motionScale/detailScale` + 文案（scene smoke 状态矩阵） |
| §45 Correction 脉冲（900ms / halo -8% / phase pause 150ms） | PASS | `MotionEvaluatorTest.correctionPulseTimeline` + 触发链（portrait/conversation feedback） |
| §54 Awakening 2200ms 时间线 + 末帧连续 | PASS | `AwakeningTimelineTest`（关键帧 + 末帧全 1.0） |
| §60 历史确定性（canonical time；无 now() 参与） | PASS | `canonicalRoundtripReconstructsSameFrame`（qa+app 双层） |
| Day0→Day180 连续性（orientation/chirality/core/palette ancestry） | PASS | `IdentityContinuityEvalTest` / `QaTimelineE2ETest.identityIsContinuousFromDay0ToDay180` |
| 删除纪律（旧单环管线全移除；无 V2/V3 并存类） | PASS | `VisualRuntimeV3RegressionTest.singleProductionVisualPipelineNotForked` + `oldRenderPipelineStaysDeleted` |

## 3. ULTRA（V3 §75/§97 决策记录）

**ULTRA_DISABLED_BY_CAPABILITY**（允许的成功态）：
- 无 AVP 2025 设备、无真机 benchmark 通道（本环境无 adb/emulator）；
  §75 启用门（API37+ / AVP / benchmark ≥20% 收益 / 三 GPU 厂商稳定 / 电池劣化 <15%）
  逐项无法在源码层证明 → 不实施 Vulkan scaffold（不为炫技引入不可验证代码）。
- Router 中 ULTRA 分支与全部硬门已实现并测试；`ultraFlag/ultraBenchmarkPassed/avp2025`
  默认 false —— ULTRA 永不自动启用。ADVANCED 为当前最高 production tier。

## 4. 已知视觉债（人眼评审前不遮）

- Canvas 后端逐段描边的 glow pass 是近似（AGSL 路径才是真 glow）；
- core knots 在小尺寸下近似两点微光（§83 人眼问题 5 待答）；
- Reference 门阈值全部机器通过，§83 五问保持 **PENDING_HUMAN_REVIEW**。
