# Presence Benchmarks —— 功耗与帧率验收基准（ERA 14 §65）

> 状态：CURRENT · 硬指标由结构 + 单测强制；设备实测指标由 CI connected-test / 真机矩阵执行（本仓库无模拟器环境时不伪造数字）。

## 1. 硬指标（结构强制 + 单测锚定）

| 指标 | 验收标准 | 强制方式 |
|---|---|---|
| 不可见时 continuous rendering | **= 0** | `WallpaperRenderController`（onVisibilityChanged(false) → renderActive=false → Choreographer 回调移除）；`WallpaperRenderControllerTest.invisibleStopsRendering` |
| destroy 后渲染 | **= 0（永久）** | `WallpaperRenderControllerTest.destroyStopsForever` |
| 不可见触摸绘制 | **= 0** | `WallpaperRenderControllerTest.invisibleTouchProducesNoDraw` |
| 渲染循环驱动 | 仅 Choreographer postFrameCallback（可见时）；无独立线程空转 | EchoWallpaperService 结构 + code review |

## 2. 设备实测基准（CI/真机执行，逐次记录）

| 指标 | 测量方式 | 基线目标（v1） | 状态 |
|---|---|---|---|
| Wallpaper CPU | `adb shell top` / procstat（壁纸进程） | 可见 < 5%；不可见 ~0% | 待 CI 实测 |
| GPU / frame time | `dumpsys gfxinfo <pkg>`（壁纸进程） | p95 frame < 16.6ms | 待 CI 实测 |
| Memory | `dumpsys meminfo` | 壁纸进程 PSS < 80MB 且不可见不增长 | 待 CI 实测 |
| Wakeups | `dumpsys batterystats`（per-uid wakeups） | 不可见期间 wakeups 增量 = 0 | 待 CI 实测 |
| Battery | batterystats 1h 前台壁纸耗电占比 | < 1%/h（QUIET 档） | 待 CI 实测 |
| 首帧计算设备锚点 | `EchoSceneFrameDeviceBenchmarkInstrumentedTest`（computeEchoSceneFrame ×1000 于真实 ART 运行时；info 日志逐次记录） | 模拟器预算 < 10000 ms | **CI connected-test 执行（ERA 43）** |
| Journey 365 装配设备锚点 | 同测试类（assembleJourneyUiState ×3 最优） | 模拟器预算 < 10000 ms | **CI connected-test 执行（ERA 43）** |
| §65 硬指标设备烟测 | 同测试类（不可见 renderActive=false / destroy 永久停止） | 断言硬失败 | **CI connected-test 执行（ERA 43）** |

## 3. 渲染模型（功耗设计的结构前提）

- 状态更新（分钟级 Presence 快照）与渲染帧率解耦：快照变化不触发额外帧；
- 渲染只消费快照 + 确定性帧模型（computeEchoSceneFrame），无 AI/网络/数据库访问；
- 触摸涟漪衰减 1.2s（RIPPLE_DURATION_MS），结束后恢复纯 ambient 帧。

## 4. 声明

- 本仓库无真机/模拟器时**不伪造** 2 节数字；CI connected-test（API 34/36 emulator）与真机矩阵是 2 节的执行点；
- 新增渲染路径必须保持 §1 硬指标（WallpaperRenderControllerTest 防回归）。
