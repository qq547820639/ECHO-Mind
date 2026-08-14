# ECHO Motion Language —— 运动语义冻结（ERA 14 §66）

> 状态：FROZEN（v1） · 本文件是 ECHO 全部运动/过渡行为的唯一语义契约。
> 只描述当前 main 已实现语义；未实现项明确标注「预留」，禁止写成 implemented（§22）。

## 0. 总原则

1. 同一 ECHO / 多个 Surface（§63）：APP/HOME_WALLPAPER/LOCK_SAFE/DREAM 共享同一 EchoPresenceState；
   Surface 只改变 privacy/layout/animation strength/interaction/power budget（§64）。
2. 所有状态变化平滑（§60）：`smoothPresenceState`（视觉四层 alpha=0.35 插值），禁止状态瞬切。
3. 确定性：同一 snapshot/seed/time → 同一帧（`computeEchoSceneFrame`）；渲染器不得自行推导身份。

## 1. Ambient Motion（常驻氛围运动）

- 语义：缓慢、连续、无事件感的流动；由 EchoVisualParameters 驱动（flowSpeed/pulsePeriodSeconds/turbulence…）。
- 实现：`EchoVisualMapper.map(state, hourOfDay, surface, …)` → `computeEchoSceneFrame`。
- 约束：不可见 = 0 帧（Wallpaper §65）；可见时帧率由 Choreographer 驱动，无自旋。

## 2. State Transitions（状态间过渡）

- 语义：所有视觉层（identity/season/daily/moment）经 interpolation 过渡；单步变化 < 目标差。
- 实现：`smoothPresenceState(prev, next, alpha=0.35)`（EchoIdentityTest.smoothingPreventsJumps 锚定）。

## 3. Unlock / Touch / Scroll

| 语义 | 现状 |
|---|---|
| Unlock | **预留**：当前无 unlock 专属动画（解锁后直接进入可见 ambient） |
| Touch | 已实现：壁纸触摸涟漪（RIPPLE_DURATION_MS=1.2s 衰减；只调制 brightness/accentIntensity/turbulence 表现层，不改底层状态）；不可见触摸不渲染 |
| Scroll | **预留**：应用内滚动不驱动 ECHO 运动（避免误触语义） |

## 4. Why Opening（为什么层展开）

- 语义：Why 层展开为渐进式内容揭示（一句话 → Scene facts → Journey）；展开不改变 ambient 视觉基调。
- 实现：EchoSceneUiState 的 Progressive Why（L2 门槛/依据来源集中裁决）；展开动画由 Compose 组件承载。

## 5. Conversation Opening（对话开启）

- 语义：对话开启进入 WAITING/COMPILING 态（EchoConversationController 状态机）；视觉保持 ambient，不抢占。
- 实现：EchoConversationController（IDLE/COMPILING/WAITING/COMPLETE/FAILED/FALLBACK）。

## 6. Action Animation（行动动画）

- 语义：呼吸（BREATHING）/暂停（PAUSE）行动经 EchoActionRuntime 运行；视觉调制最小化（呼吸 = 慢呼吸周期，暂停 = 冻结 ambient）。
- 实现：EchoActionRuntime（feature:actions）+ EchoActionOverlay。

## 7. Dream（充电屏保）

- 语义：最沉浸 ambient（DREAM surface flow 0.7）；无文字、无交互（除退出）。
- 实现：EchoDreamService（computeVisualParameters surface=DREAM）。

## 8. Reduced Motion（无障碍减少动画）

- 语义：视觉保持静止（flow=0）；仅保留极低频亮度变化。
- 实现：SurfaceMode.REDUCED_MOTION（surfaceFlow=0）+ preferences.presenceReduceMotion。

## 9. Low Power（低功耗）

- 语义：全面降强度（flow 0.35）；夜间模式叠加再降（nightFactor 0.6）。
- 实现：SurfaceMode.LOW_POWER（0.35）+ preferences.presenceNightMode。

## 10. 禁则

- 禁止瞬切（任何视觉层跳变）；禁止渲染器自行推导身份/季节参数（§62 mapper 唯一入口）；
- 禁止在锁屏/壁纸渲染文字（Public Safe 由构造保证）。
