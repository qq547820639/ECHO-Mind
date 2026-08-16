# ECHO_VISUAL_CONSTITUTION — ECHO 视觉宪法

> 版本：1.0 · 状态：冻结基线（visual-runtime 分支建立）
> 本文件冻结「ECHO 视觉是什么 / 不是什么」。所有 renderer、Surface、映射表、文案视觉化必须服从本宪法。
> 与 PORTRAIT_CONTRACT / PERSONAL_INTELLIGENCE_CONTRACT / AFFECTIVE_CONTRACT 冲突时，**以冻结契约为准**，本宪法只做视觉表达，不创造任何语义。
> 参考设计稿（`设计稿/*.png`）仅为 art direction；最终所有 ECHO 必须由运行时 renderer 生成，禁止把设计稿当静态背景图。

## 一、第一原则

ECHO 是 **Personal Ambient Intelligence**，不是 Dashboard。视觉的第一任务是「陪伴与可识别的同一性」，不是「信息陈列」。

1. **ECHO Organism 永远是第一视觉焦点。** 打开 App 第一眼看到 ECHO，不是 UI card。首屏约 60–70% 空间给 organism。
2. **三个世界保持**：ECHO = 现在 · Journey = 我的时间 · Me = 我的控制权。
3. **SAME ECHO**：App / Wallpaper / Dream / Wrist 必须肉眼可认出同一 Identity。Wrist 是 SECOND BODY，不复制 Memory / SelfModel / Journey / Provider。

## 二、视觉语言（Art Direction → 运行时规则）

| 维度 | 规则 |
|---|---|
| 底色 | deep navy / OLED black（约 `#050A18` → `#02040C` 径向衰减） |
| 主光谱 | 蓝 → 紫（accentHue 收敛于青蓝—紫区间，由 Identity Genome 派生，不随状态突变） |
| 暖金 | 仅作极少量生命性高光（accent 微光 / Dream 充电态），面积占比 < 5%，不得成为主色 |
| 空间深度 | ambient field + 多层 glow + 视差粒子 + orbital field，营造纵深而非平面圆 |
| 结构 | filament 网状膜 + orbital trajectories + 粒子系统 + core glow + ambient halo + reflection/ripple |
| 拟人 | **不使用拟人脸；不做可爱宠物；不做 SaaS 风；不做传统 mental-health UI** |

## 三、禁止清单（红线，CI 与人类评审双重把关）

- ❌ red = bad / green = good 的评价性配色。
- ❌ 大量堆 Material Card（信息用留白与层级，不用卡片墙）。
- ❌ 显示「心理健康分」「情绪分」「能量 62%」「专注 68%」等**无 Ground Truth** 的数字。
- ❌ 任何心理诊断式视觉映射（低活动 = 抑郁 / 晚睡 = 不健康 / 高活动 = 开心）。
- ❌ 拟人脸、眼睛、表情符号化的「情绪脸」。
- ❌ 把设计稿 PNG 当作成品贴图或背景。

## 四、数据不足的表达

数据不足**不通过危险色**表达，而通过视觉降级表达：

- `dataClarity` 降低 → organism 更轻、更模糊、filament 更稀疏；
- `particleDensity` 降低 → 粒子更稀；
- `motion` 降低 → 更慢的呼吸与漂移；
- 文案明确「不足以比较」。

> 设计稿冲突记录（ADR-0001）：设计稿 5/8 的「情绪：平静 / 能量 62% / 专注 68%」、设计稿 6 的「情绪识别与语音分析」、设计稿 9 的「情绪/能量/专注折线」**全部剥离**。保留其 organism 视觉与版式节奏，数字与心理标签替换为宪法允许的 baseline 相对表述与 data coverage 视觉降级。

## 五、可识别的「不一样」

用户应逐渐**仅通过 ECHO 视觉本体**认出「今天和自己的通常不一样」——通过 orbital geometry 偏差、filament coherence、particle density、drift 的非评价性变化，而非颜色告警。
