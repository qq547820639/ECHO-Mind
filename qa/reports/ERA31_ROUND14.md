# ERA 31 Round 14 报告（§16 Dream 表面自适应帧率对齐）

> 日期：2026-08-15。R13 给 Wallpaper 落地了自适应帧率；本轮把同一策略对齐到 Dream。

## 审计发现

`EchoDreamView` 用 `postInvalidateOnAnimation()`——系统动画驱动的 invalidate 同样以
满帧率（~60fps）持续重绘，与 Wallpaper 修复前同构的浪费。Dream 仅在充电/底座时运行，
但 §16 的目标是通用的（「静态/低变化阶段自动降低 frame rate」），不应因场景不同打折扣。

## 实现（与 Wallpaper 同一纯策略，零新逻辑）

- `EchoDreamView.onDraw` 结尾：`postInvalidateOnAnimation()` →
  `postDelayed({ invalidate() }, wallpaperFrameIntervalMs(...))`（过渡 33ms / 静置 250ms）；
- 快照重读处增加 `updatedAt` 变化检测 → 触发流畅过渡窗口；
- **0 残留渲染语义保持**：View 脱离窗口后 `invalidate()` 不再触发 `onDraw`，
  回调链自动停止（文档注释同步更新）；
- 时钟覆盖层在 250ms 静置节奏下仍然分钟级准确（远超需求）。

## 实测

- Android 全绿（无新测试——复用 R13 的三档策略测试，策略单一事实源）+ detekt + lint PASS。
