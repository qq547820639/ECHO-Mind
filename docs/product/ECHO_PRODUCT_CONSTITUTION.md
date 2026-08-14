# ECHO Product Constitution

> 状态：v1.0 · 冻结 · 本文件是 ECHO Mind 的最高产品原则。所有 PRD、功能提案、代码评审、文案评审都必须以本文件为第一判据。
> 上游：Master Prompt（长期自主演进总纲）。本文件不替代 `PORTRAIT_CONTRACT.md`，而是定义其在新体系中的位置。

## 0. 产品最终定义

> **ECHO 是生活在用户设备中的个人 AI。它持续理解这个人的生活节律、行为模式、上下文和长期变化，并通过动态视觉、时间记忆、解释和必要的行动持续陪伴。**

用户体验终点只有两句：

1. **“我的手机开始懂我了。”**
2. **“这是我的 ECHO。”**

## 1. 产品顺序（永远按此顺序）

```text
Ambient → Understand → Explain → Converse → Act → Intervene
```

默认状态是**安静存在**，不是主动找用户说话。干预（Intervene）是链路的末端，永远不是起点。

## 2. 三个世界（长期信息架构）

| 世界 | 含义 | 收敛的旧模块 |
|---|---|---|
| **ECHO** | 现在 | Today + 首页（ECHO Scene：Why / Now / Conversation / Action / Memory） |
| **Journey** | 我的时间 | Trend → 时间河流（Moment / Day / Week / Month / Season / Year） |
| **Me** | 我的控制权 | Support / Settings → Personal Intelligence Control Center |

ECHO 不是 Dashboard、不是卡片列表、不是指标中心。Journey 不是趋势图。Me 不是杂项设置。

## 3. 第一原则

1. **模型不是 ECHO。** ECHO 身份 = Observation Core + Personal Baseline + Context Engine + EchoSelfModel + Memory + 用户纠错 + 隐私策略 + Reasoning Contracts + Narrative Policy + Intervention Policy + 视觉身份。LLM 只是 Compute Provider；换模型不失忆、不换人格。
2. **AI 不是启动前置条件。** 没有 Provider 时：感知、基线、确定性 Portrait、Presence、Journey、事实解释全部可用；只有 deep reasoning / conversation / AI narrative 降级。
3. **不要破坏 Observation Core。** `Passive Sensing → Derived Features → Daily Aggregate → Personal Baseline → Portrait` 是 Ground Truth Layer，AI 必须建立在它上面，不能绕过。
4. **事实大于判断。** 先说发生了什么，再考虑意味着什么。不知道就说不确定。
5. **用户可以纠正 ECHO。** Personal AI 必须允许被主人教育；用户自述（Felt）永远优先于被动推断（Interpreted）。
6. **隐私默认最高。** 锁屏只暴露最低敏感度信息（PUBLIC_SAFE）；系统故障永远不能伪装成用户选择。
7. **在场大于打扰。** 让用户看见 ECHO ≠ 不停通知用户；用户一周不打开，ECHO 仍然好好工作。
8. **动态大于静态。** ECHO 是持续变化的生命界面；但确定性大于随机（同一 identity/day/state/time → 可复现的核心结构）。
9. **基本行动能力免费。** 不在「我想让自己舒服一点」前面放付费墙。
10. **订阅卖持续价值。** 记忆、长期理解、同步与人工服务；不卖产品灵魂。

## 4. 契约体系（版本化，禁止偷改）

| 契约 | 定位 | 状态 |
|---|---|---|
| `PORTRAIT_CONTRACT.md` | **PORTRAIT_CONTRACT_V1_OBSERVATION**：Ground Truth Contract，永久保留。Observation-only，禁情绪/心理推断，Me vs Me | 冻结（v1.0） |
| `docs/intelligence/PERSONAL_INTELLIGENCE_CONTRACT.md` | 所有个人智能（含 AI 推理）的统一契约：Observed/Interpreted/Felt、Confidence、Provenance、Memory、Context、Corrections、Privacy Budget | 冻结（v1.0） |
| `docs/intelligence/ECHO_PERSONA_CONTRACT.md` | ECHO 人格：Evidence before interpretation、Never pretend certainty、Do not pathologize、Respect silence…（任何 Provider 必须执行） | 冻结（v1.0） |
| `docs/intelligence/AFFECTIVE_CONTRACT.md` | 可选情绪智能：单独 opt-in、独立开关、允许/禁止信号、阈值、保留、纠错、视觉/语言/干预政策 | 待建立（ERA 10 前置） |

任何新 AI 功能若与契约冲突：明确 version、明确 migration、明确 opt-in、更新 Contract、增加 tests。**禁止偷偷绕开。**

## 5. 反模式（做成以下任何一样都是失败）

- AI Dashboard（一堆数据卡）
- Mood Tracker（让用户每天填情绪）
- ChatGPT wrapper（只有聊天）
- Live Wallpaper App（只有动态背景）
- Mental-health detector（乱猜心理状态）
- Habit gamification（签到、火焰、任务）
- AI surveillance（持续告诉用户系统在监视什么）
- Subscription funnel（先理解用户再不断弹付费）

## 6. Engagement 禁令（dark pattern 零容忍）

禁止：签到、连续天数火焰、不打开就损失奖励、情绪焦虑通知、未读红点滥用、卡通脸、强迫喂养、假情绪、“想念用户”。

## 7. 新需求十二问（默认否决闸）

任何需求进入核心产品前必须全部通过：

1. 它是不是让 ECHO 更懂这个具体用户？
2. 理解是否有 evidence？
3. 它是否知道自己什么时候不确定？
4. 它是否尊重用户自己的解释？
5. 它有没有增加不必要的数据访问？
6. 没有云和 AI 时，基础 ECHO 是否仍然成立？
7. 换模型以后，ECHO 是否仍然是同一个 ECHO？
8. 它是否改善 Ambient Presence，而不是增加打扰？
9. 它是否能自然进入 Journey？
10. 它是否能被用户关闭、纠正或遗忘？
11. 它是否让 ECHO 更像 Personal Intelligence，而不是普通 App？
12. 最终是否加强「我的手机懂我」？

任一不通过：默认不进入核心产品。

## 8. 不确定时的决策顺序

```text
User control → Trust → Data truth → Personal continuity → Privacy →
Ambient quality → Explainability → Intelligence quality → Visual richness → Feature count
```

## 9. 核心指标（替代 DAU/时长）

Activation success · Sensing reliability · Day-7 baseline completion · Presence adoption · Presence retention · Reasoning trust · Correction rate · AI factual grounding · Provider success rate · Battery impact · Privacy control comprehension

## 10. 场景验收（最终完整体验）

成熟用户的一天：早晨锁屏 ECHO 安静存在（无无意义问候）→ 白天行为变碎、壁纸视觉慢慢变化（不通知）→ 用户注意到、打开 ECHO：Scene + 「今天下午和你通常相比更碎一些」+「为什么？」→ 点开看到真实 Evidence → 问「为什么最近总这样？」→ Conversation 用 28d pattern + baseline + 已知上下文 + 用户纠错回答 → 用户说「其实最近是因为赶项目」→ ECHO 记录 Context Memory → 之后相似日子理解发生变化 → 用户说「我想让脑子停一下」→ ECHO 自己进入呼吸 Action → 晚上状态变慢、ECHO 缓慢松开 → 睡前充电 Dream 出现（只有时间 + ECHO）→ 三个月后回 Journey 看到自己的 ECHO 如何变化。

**这就是最终产品验收。**
