# ERA 31 Round 13 报告（§16 Battery Reality：Wallpaper 自适应帧率落地）

> 日期：2026-08-15。真机数字仍待部署侧采集（DEVICE_CHECKLIST），本轮交付的是
> 「静态/低变化阶段自动降低 frame rate」的**策略与实现**——此前完全缺失。

## 审计发现

`WallpaperRenderController` 已保证不可见零渲染（§65 硬指标 ✓），但可见期
`EchoWallpaperService` 恒以 Choreographer 满帧率（60fps）连续渲染——§16 第二目标
「静态/低变化阶段自动降低 frame rate」没有任何实现。慢速有机呼吸的 ECHO 在
静置期以 60fps 空转，是 Battery impact 指标的结构性浪费。

## 实现

1. **纯策略函数**（feature:presence，JVM 可测）`wallpaperFrameIntervalMs(msSinceVisualChange, rippleActive)`：
   - 参数刚变化（<2s）或触摸涟漪进行中 → 33ms（30fps，慢速运动足够流畅）；
   - 静置期 → **250ms（4fps）**——呼吸周期 4-6s 仍有约 20 帧步进，慢速有机观感不受损；
   - 常量：TRANSITION_FRAME_INTERVAL_MS / IDLE_FRAME_INTERVAL_MS / TRANSITION_WINDOW_MS。
2. **服务接线**（EchoWallpaperService）：
   - `refreshSnapshot` 检测快照 `updatedAt` 变化 → 记录 lastVisualChangeMs；
   - 渲染循环从 `postFrameCallback`（满帧）改为 `postFrameCallbackDelayed(interval)` 自适应调度；
   - 不可见/销毁时 `removeFrameCallback` 对延迟回调同样生效（§65 不变式保持）。
3. **锚点**：`WallpaperFrameIntervalPolicyTest`（过渡/静置/涟漪三档，静置 ≥4× 过渡间隔）；
   PRESENCE_BENCHMARKS.md 硬指标表补行。

## 实测

- Android 全绿（presence +3 策略测试）+ detekt + lint PASS。
- 真机 CPU/GPU/battery 实测数字仍由 `scripts/collect_wallpaper_metrics.sh` +
  DEVICE_CHECKLIST 采集（策略已就绪，等部署侧执行）。
