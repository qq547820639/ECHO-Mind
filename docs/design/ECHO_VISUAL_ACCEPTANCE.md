# ECHO_VISUAL_ACCEPTANCE — 视觉验收与测试基线

> 版本：1.0 · 本文件定义「视觉任务何时算完成」的可验证条件（Definition of Done 的视觉面）。

## 一、Golden Visual Tests（不拿 AI 设计稿做 pixel-perfect golden）

固定 fixture（固定 seed / presence / clock / surface / viewport），输出真实 renderer screenshot，纳入 CI：

```
SEED · LEARNING · KNOWN · QUIET · LOW_DATA · DAY · NIGHT
REDUCED_MOTION · WALLPAPER · DREAM · WRIST
JOURNEY_DAY · JOURNEY_MONTH · ME · MEMORY · WHY
```

Renderer 算法改变需显式审核 golden。

## 二、确定性验收

- 同一 fixture 重复渲染**逐像素一致**（或数值级一致）。
- Journey 每天可由 `EchoPortraitSnapshot` 参数重建相同 portrait。
- Moment 不改变 Identity；Identity 跨 App/Wallpaper/Dream/Wrist 一致（`identityDistance` 低于阈值）。

## 三、性能验收

| 场景 | 目标 |
|---|---|
| ECHO app scene / transition / Journey day·month / Wallpaper / Dream | 前台默认流畅 60Hz |
| Wallpaper 不可见 | 0 渲染（帧率/CPU 归零） |
| 降级顺序 | particles → filament → secondary glow → reflection；identity/state/privacy 永不降级 |

测试维度：frame time / jank / CPU / GPU / memory / battery / thermal；至少在高端 / 中端 / 较弱 reference profile 验证。

## 四、无障碍验收

- TalkBack 语义：organism 作为装饰/状态视觉给**聚合语义描述**，不朗读数百粒子。
- font scale / high contrast / Reduced Motion / touch target / gesture alternative。
- no information by color alone；OLED 可读性；system inset / edge-to-edge / landscape Dream / 多宽高比。

## 五、验收红线（任一不满足即未完成）

- ECHO 首页第一眼不是 Dashboard，organism 是核心视觉焦点。
- App / Wallpaper / Dream / Wrist 肉眼可认出同一 Identity。
- Visual mapping deterministic；无情绪/心理状态越界映射。
- Low data 不乱推断；No AI Provider 完整可用。
- Wallpaper 隐藏停止渲染；Wrist stale 自动 QUIET；Reduced Motion 正常；TalkBack 正常。
- 所有 screenshot golden / unit / instrumentation / contract / security test 通过；performance gate 通过。
- 无两套产品 UI 残留；文档与实现同步。
