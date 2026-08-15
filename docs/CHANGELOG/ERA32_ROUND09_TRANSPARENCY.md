# ERA 32 R09 — §52 数据透明精确词表 + Wallpaper 电池策略复核

> 2026-08-15。两个信任/性能面审计：Ask ECHO 依据双清单的精确性（§52）与
> Wallpaper 不可见零渲染 + 自适应帧率接线（§14/§15）。

## 1. §52 修复（TRUST：词表不精确）

**问题**：回答依据的「没有使用」清单写「没有使用：麦克风、通知正文、精确位置」。
但麦克风可选开启时，派生特征（音量/语速/停顿级）确实经 mic_opt 源进入日聚合
（sources_present 可见）——「没有使用：麦克风」对开启麦克风的用户是**假声明**。
而「原始音频」是构造上永远成立的精确声明（编译器策略硬禁止，检索层不产生该类证据）。

**修复**：词表对齐 §52 原文——「没有使用：原始音频、通知正文、精确位置」。
审计确认：mic 派生特征（rmsDb/speechRate/pauseCount/f0Mean）不驱动任何画像维度、
不进入任何回答计算或 AI 上下文；通知正文（仅派生计数）与精确位置（无权限）同理由成立。

**回归**：`EchoConversationLayerSmokeTest` 锚点同步；参考了/没有使用双清单行为不变。

## 2. §14/§15 Wallpaper 复核（无缺陷，如实记录）

| 检查点 | 结论 |
|---|---|
| 不可见零渲染 | `onVisibilityChanged(false)` → renderActive=false → `stopRendering()` 移除 Choreographer 回调——Invisible: continuous rendering = 0 ✅ |
| 自适应帧率 | 过渡 33ms / 静置 250ms（4fps）单一策略 `wallpaperFrameIntervalMs`，快照变化触发流畅窗口 ✅（R13/R14） |
| 锁屏/解锁 | 可见恢复即重读快照（ERA 54），不依赖 15 分钟刷新周期 ✅ |
| 生命周期 | onSurfaceChanged / onDestroy 均正确重读/停止；无泄漏渲染回调 ✅ |
| 真机数字 | CPU/GPU/帧时/唤醒/电池仍归外部门采集（collect_wallpaper_metrics.sh + DEVICE_CHECKLIST 就绪） |

## 3. 验证

- `EchoConversationLayerSmokeTest` 更新后全绿；Android 全模块单测 + detekt + lint 全绿。

## 4. 下一轮

信任词表继续以「构造上永远成立」为唯一标准复核；持续 Delete Review 节奏。
