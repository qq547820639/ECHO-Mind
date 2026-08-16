# ECHO_SURFACE_POLICY — 跨 Surface 统一策略

> 版本：1.0 · 统一定义每个 Surface 的隐私/表现边界。
> Renderer 必须拿到**已经安全裁剪**的 spec；隐私判断**不得散落在 UI**。

## 一、Surface 枚举与边界

| Surface | 允许文字 | 允许证据 | 动效复杂度 | 亮度 | 用户标识 | Memory 访问 | Context 访问 |
|---|---|---|---|---|---|---|---|
| `APP_PRIVATE` | 全部（含 privateNarrative） | 完整 | 高 | 全 | 允许 | 允许 | 允许 |
| `APP_EVIDENCE` | 全部 + 来源/置信 | 完整 + 溯源 | 高 | 全 | 允许 | 允许 | 允许 |
| `WALLPAPER_VISUAL_ONLY` | **无文字** | 无 | 中（低功耗） | 中 | 禁止 | 禁止 | 禁止 |
| `LOCK_PUBLIC_SAFE` | 仅 PUBLIC_SAFE | 无 | 低 | 低 | 禁止 | 禁止 | 禁止 |
| `DREAM_AMBIENT` | 极少（时钟/充电可选） | 无 | 低（慢呼吸） | 低暖 | 禁止 | 禁止 | 禁止 |
| `WRIST_PUBLIC_SAFE` | 极少 | 无 | 极低（few shapes） | 低 | 禁止 | 禁止 | 禁止 |

## 二、铁律

1. **SAME ECHO**：所有 Surface 共享同一 `identitySeed` 与当前 `Presence`，只改变艺术表现强度，不改变 ECHO state。
2. **WALLPAPER 不可见 = 停止渲染**（0 帧率 0 CPU），由渲染生命周期控制器保证，可单测。
3. **PUBLIC SAFE 由构造保证**：Wallpaper/Lock 不渲染任何文字 → 无敏感文字泄露可能。
4. **Wrist 是 SECOND BODY**：手机高维 genome → deterministic downsample → wrist 低维参数；断连保留 Identity、Moment → QUIET，不显示 ERROR。
5. **Style 只改艺术表现**，不改 ECHO state。

## 三、降级顺序（性能/功耗压力下）

```
particles → filament detail → secondary glow → reflection effects
```

**永不降级**：identity / state correctness / privacy policy。

## 四、Reduced Motion（独立 policy，非静态截图）

- 大幅降低 particle movement；
- 降低 orbit；
- 保留低频呼吸；
- 保留轻微 luminance drift。
- Identity 不因此改变。
