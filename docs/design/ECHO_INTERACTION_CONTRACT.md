# ECHO_INTERACTION_CONTRACT — 交互契约

> 版本：1.0 · 冻结三世界与跨 Surface 的交互行为。视觉改版不得破坏 safety / privacy / crisis / release gates。

## 一、ECHO Home（现在）

- 首屏 organism 占约 60–70%；默认内容：ECHO organism + 一句今日 Observation + 为什么这么说 + Ask ECHO + 必要轻量 action。
- **禁止**：首页 KPI / 情绪卡片 / 健康评分 / 复杂 Dashboard / endless feed。
- `tap organism` → 微弱局部响应（涟漪/微光），**不执行业务操作**。
- `为什么这么说` → 进入 Evidence Surface（WHY）。
- `Ask ECHO` → contextual Ask（非聊天页）。
- secondary information → 轻微向上 reveal，可变 feed 为止。
- 数据不足 → organism 更轻更模糊，文案明确「不足以比较」。

## 二、WHY / Evidence（可解释性）

三层结构：
1. Observation headline；
2. Today vs personal baseline；
3. evidence window / coverage / source。

每条标 `OBSERVED` / `FELT` / `INTERPRETED`，严格遵守 Intelligence Contract。
**Correction 是一级能力**：用户纠正后更新 context/memory，ECHO 做一次克制的 visual recompose（不只 toast）。

## 三、Ask ECHO

- 不是问题随便问的 ChatGPT 页；问题围绕用户时间与上下文。
- 回答必须引用当前编译后的允许证据、标明 used / not used、证据不足明确说不足。
- Provider failure → deterministic fallback；**No Provider 完整可用**。
- UI 保持 ECHO presence，不把 organism 替换为聊天气泡墙。

## 四、Journey（我的时间）

- 纵向 memory river；日视图小 portrait，选中 morph 到主 ECHO。
- 周 = 7 portrait 成流；月 = 整月 portrait constellation；季/年 = 低密度时间星河。
- 图表只进 Evidence，不进 river 主视觉。
- pinch / scrub 为高级交互，但关键能力必须有可发现按钮/tab。
- Portrait **存参数不存 AI 图**（见 EchoPortraitSnapshot）。

## 五、Me（我的控制权）

首层为 Personal Intelligence Map（Observation / Memory / Intelligence / Devices / Presence 五节点围绕 organism），**不是 Settings List**；下层才进 Data & Permissions / Memory / AI Provider / Export / Notification / About。

## 六、Onboarding = Awakening 状态机

- 禁止假进度条。每个真实 permission/capability 成功 → ECHO 增加真实视觉结构。
- 拒绝 optional permission → 不惩罚、不变灰、核心体验继续。
- 核心 sensing ready → organism morph → 直接进 Day 0；不做传统 Done screen。

## 七、QUIET 态

QUIET ≠ loading / empty error / 「你状态不好」。表现：极少 filament、一个慢 halo、很低 pulse、大面积负空间、无主动 CTA、无红点、无 warning。用户可以什么都不做。
