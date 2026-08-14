# ECHO Presence Architecture

> 状态：v1.0 · 冻结（架构层）· 实现顺序：ERA 2（App Scene）→ ERA 3（Wallpaper/Dream）。
> 位置：`docs/presence/ECHO_PRESENCE_ARCHITECTURE.md`

## 1. 单一状态原则（最高优先级）

**全系统只有一个 Current ECHO State。**

```text
Sensing Layer → Feature Engine → Personal Baseline Engine
        → Current State Engine → EchoStateStore
                  ↓                ↓               ↓
              Today UI      Wallpaper       DreamService
```

禁止：Today 用自己的状态、Wallpaper 用另一套、Dream 又算一套、Chat 再自己推理。视觉和语言可以因 surface 不同而变化，底层认知不能矛盾。

## 2. EchoPresenceState（核心对象）

```kotlin
data class EchoPresenceState(
    val updatedAt: Instant,
    val sensingStatus: SensingRuntimeStatus,      // 见 SensingRuntimeStatus 六态
    val maturity: EchoMaturity,                    // SEED/DISCOVERING/EMERGING/KNOWN/MATURE
    val rhythmState: RhythmState,
    val behaviorState: BehaviorState,
    val affectiveState: AffectiveState?,           // ERA 10 才允许非 null
    val confidence: Float,
    val identityGenome: EchoIdentityGenome,        // 数月级
    val lifeSeason: EchoLifeSeason,                // 数周级
    val dailyComposition: EchoDailyComposition,    // 一天级
    val momentState: EchoMomentState,              // 分钟/小时级
    val publicNarrative: EchoNarrative?,           // 仅 PUBLIC_SAFE 可进入锁屏/壁纸
    val privateNarrative: EchoNarrative?
)
```

- **低频更新**：分钟级（5-15 分钟）重算；渲染帧率与状态更新率是两个时间尺度。
- 唯一写入方：Current State Engine（挂接 Sensing 窗口持久化钩子）；Today/Wallpaper/Dream 只读。
- 持久化：最近一版快照落盘（进程死亡后 Wallpaper 恢复用）；不持久化高频向量历史。

## 3. SensingRuntimeStatus（统一六态）

```text
NOT_AUTHORIZED / STARTING / ACTIVE / DEGRADED / SYSTEM_PAUSED / USER_PAUSED
```

铁律：**「关闭」永远只表示用户行为（USER_PAUSED）。** 网络错误、flag 失败、同步失败、系统杀进程 → SYSTEM_PAUSED / DEGRADED，绝不伪装成用户关闭。以系统真实权限状态为 UI 唯一事实来源。

## 4. 视觉四时间层

| 层 | 周期 | 决定 |
|---|---|---|
| **Identity Genome** | 数月/长期 | 我的 ECHO 是谁（installationSeed + long-term baseline + visual preference + 稳定个人模式）。同一用户不同日子的 ECHO 必须有明显视觉血缘，禁止每天换皮肤 |
| **Life Season** | 数周/数月 | 最近的人生阶段如何改变它 |
| **Daily Composition** | 一天 | 今天的 ECHO 长什么样 |
| **Moment Modulation** | 分钟/小时 | 它现在怎样呼吸 |

## 5. Generative Visual Engine

```text
EchoPresenceState → EchoVisualParameters → EchoSceneRenderer
```

- `EchoVisualParameters`（连续维度，禁止「焦虑=红/开心=黄」式映射）：
  `flowSpeed / coherence / turbulence / particleDensity / coreOpenness / dispersion / pulsePeriod / depth / brightness / contrast / accentIntensity / structureComplexity`
- **Confidence 影响视觉确定程度**：低置信度 → 更弥散、更少语义结构；高置信度 → 结构更清晰。但不让用户学会「某颜色=某心理判断」。
- **Determinism**：给定 identity/day/state/time，画面有可重复的核心结构（Journey 视觉记忆的前提）。
- **Renderer 不依赖业务数据库**：输入只有 `EchoVisualParameters + time + viewport + surfaceMode + interaction`。

```kotlin
interface EchoRenderer   // 允许未来 CanvasRenderer / ShaderRenderer / GpuRenderer
```

## 6. Surface Modes（同一视觉身份，不同表现强度）

```text
APP             信息最完整（visual / summary / why / conversation / action）
HOME_WALLPAPER  视觉为主；允许 scroll response、touch ripple；禁止长期显示敏感文字
LOCK_SAFE       更静；默认 visual only；禁止情绪推测/心理状态/私密内容/具体习惯异常/support info
DREAM           最沉浸（大 ECHO + clock + date + public-safe phrase；大量 UI 自动隐藏）
REDUCED_MOTION  无障碍
LOW_POWER       低功耗
```

## 7. Wallpaper 实现要点（ERA 3）

- `WallpaperService` + `WallpaperService.Engine`；Renderer 只在可见时工作；`onVisibilityChanged(false)` 停止持续绘制。
- 支持 visibility / surface lifecycle / offset / touch / zoom / destination（按 API 版本 feature detection 降级）。
- **Wallpaper 不运行 Personal Intelligence pipeline**，只消费 EchoPresenceState。
- `AI/sensing update rate ≠ render frame rate`。

## 8. Dream 实现要点（ERA 3）

- `DreamService`（Android 官方 screensaver：充电/底座 + 空闲运行；部分 OEM 无入口，文案不承诺全设备可用）。
- `onDreamingStarted/Stopped`；退出后 0 残留渲染；内容全部 PUBLIC_SAFE。
- 它是独立 Ambient Surface，不是锁屏替代品。

## 9. 能耗原则（验收指标，非建议）

- 不可见：0 CPU（profiler 断言）。
- 可见：帧率上限（默认 30fps）；参数只在状态变化时重算。
- 夜间模式：默认更暗更慢。
- 验收：壁纸可见时电池影响 ≤ 预算（真机 + Battery Historian 校准）。**不能因为视觉漂亮导致用户最终关闭壁纸。**

## 10. Presence 控制中心（Me 内）

动态壁纸开关（跳系统选择器，不是「导出壁纸」）· 充电屏保 · 锁屏隐私（仅视觉 / 视觉+简短状态，默认仅视觉）· 动态程度（安静/默认/明显）· 夜间模式 · 减少动画（无障碍）。

## 11. 未来扩展（不做承诺，只留接口）

Watch / Tablet / Desktop / Earbuds —— 共享 EchoSelfModel / Memory / Identity Genome；是同一个 ECHO，不是多个助手。
