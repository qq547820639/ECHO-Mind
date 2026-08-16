# VISUAL_RUNTIME_V3 — Performance 报告

> IMPLEMENTATION_HEAD=`c4ff17be9a116884b4a6e46ca5c07b6e0c615401`（分支 `visual-runtime-v3`）
> 本环境无 adb / 无 emulator → 真机性能项一律 BLOCKED_EXTERNAL_ANDROID_DEVICE，不伪造。

## 1. 软件侧（实测绿）

| 项 | 状态 | 证据 |
|---|---|---|
| 场景帧计算预算（1000 次 JVM < 2000ms） | PASS | `PerformanceBaselineTest.sceneFrameComputationStaysUnderBudget`（V3 新求值器实测绿） |
| 拓扑缓存零热路径分配纪律（§32：hot path 无 new Random/Bitmap/每帧 Path 重建） | PASS | `OrganismTopologyBuilder` 缓存键测试 + AGSL session bitmap 复用 |
| Wallpaper 不可见 = 0 连续绘制 | PASS | `WallpaperRenderController` 状态机（callback 移除）+ 既有长测适配 |
| §69 自适应调度表（0/8/10/12/18/30；30fps cap） | PASS | `WallpaperSchedulerTest.priorityTable` |
| §71 Dream 帧率（entry 24 / steady 15 / reduced 8） | PASS | `WallpaperSchedulerTest.dreamSchedule` |
| §72 burn-in deterministic offset（±3/±2dp） | PASS | `burnInOffsetsDeterministicAndBounded` |
| §31 Power/Thermal 降级（CONSERVE/MINIMAL 映射 + 拓扑丰富度） | PASS | `AgslBackendTest.qualityGateMapping` + `qualityDegradesRichnessNotIdentity` |
| §77 固定降级顺序（glints→particles→fragments→far halo→samples 56→40→count→fps） | PASS | `samplesFor` + qualityProfile 表（实现于 topology/computer） |
| 壁纸长运行稳定性（快照恢复同帧/无 NaN/粒子有界） | PASS | `QaWallpaperLongRunTest` / `EchoPresenceSnapshotRecoveryTest` |

## 2. 真机/设备项（BLOCKED_EXTERNAL_ANDROID_DEVICE）

| 项 | 状态 | 备注 |
|---|---|---|
| App P95 ≤16.67ms / P99 ≤24ms（真机） | BLOCKED_EXTERNAL | 无 adb/emulator；设备锚点测试已迁移新求值器（`EchoSceneFrameDeviceBenchmarkInstrumentedTest`），有设备即跑 |
| Standard/Advanced 增量显存 <24MB / Ultra <32MB | BLOCKED_EXTERNAL | 需真机 dumpsys |
| AGSL/ADVANCED GPU 实测（帧率/功耗/热） | BLOCKED_EXTERNAL | RuntimeShader 需真实 GPU |
| Wallpaper 电池增量（Battery Historian 1h 对比） | BLOCKED_EXTERNAL | `qa/visual-review/DEVICE_CHECKLIST.md` 待执行 |
| 热态调度实测（thermal 回调触发降级） | BLOCKED_EXTERNAL | 逻辑单测绿；真机热态待验 |
| 模拟器 | NOT_APPLICABLE | 本环境 SDK 无 emulator 包/无 AVD（无法给 EMULATOR_PASS） |

## 3. ULTRA

ULTRA_DISABLED_BY_CAPABILITY（§97 允许的成功态；四硬门全false默认，详见 RENDERER 报告 §3）。
不成为 Release blocker。

## 4. 性能回归观察

- 新求值器（3D 拓扑采样 + 逐点遮挡）比旧单环重；JVM 预算内实测绿（1000 帧 < 2000ms）。
  Canvas 后端逐段描边成本随 samples（56/48/40）与丝数线性——§31/§77 降级链已就位；
  真机帧时间以设备锚点测试 + DEVICE_CHECKLIST 为准。
- Wearable 集成测试在全量并行下两次计时 flake（非产品缺陷；await 上限已固化 30s）。
