# Legacy Removal Plan

> 状态：CURRENT · ERA 12 · 旧时代代码的完整移除计划（v3.1 §41/§42：不允许永久 compatibility）。

## 已删除（防回归锚点）

| path | replacement | target era | 状态 |
|---|---|---|---|
| `ui/TodayScreen.kt` | `ui/EchoSceneScreen.kt`（+ ui/echo 组件族） | ERA 12 | ✅ deleted |
| `ui/SupportScreen.kt` | `ui/me/MeScreen.kt`（六子领域） | ERA 12 | ✅ deleted |
| `ui/LegacyScreens.kt`（3 停用文案常量） | `DeprecatedInputRemovalTest` source-scan 断言 | ERA 12 | ✅ deleted |
| `ui/journey/…TrendScreen` @Deprecated 委托 | `JourneyScreen`（唯一实现） | ERA 12 | ✅ deleted |
| `SkillListScreen`（旧「能力」Tab 全页） | ECHO Scene「更多能力（订阅）」分区 | v2-1 | ✅ deleted |

## 保留（有真实调用方；最终决策，非遗留）

| path | 保留理由 | removal condition |
|---|---|---|
| `ui/SkillCardHost.kt` + `SkillActionRenderers.kt` + `rememberSkillList` | 订阅能力内容（KEEP_AS_CONTENT，见 SKILLS_TO_ACTIONS.md） | 订阅内容下架或迁入独立内容引擎 |
| `ui/JourneyState.kt`（Trend 七态纯函数 + 文案） | Journey 证据层复用 + 单测锚点（TrendDataSourceTest/PortraitTimelineTest） | 与测试同步演进；非 UI 死代码 |
| backend legacy 410 存根（Checkin/Journal/Questionnaire/Practice） | 机构历史数据只读（migration compatibility） | 机构后台确认无历史消费者 |
| `ui/MeSupportHelpers.kt` | Me 子领域共享 + 测试锚点 | —（非 legacy，当前实现） |

## 剩余审计项（每轮复核，非永久保留）

| 项 | 判定 |
|---|---|
| Room 旧表（checkins/journals/questionnaire_results/practice_completions） | keep（migration history + 只读历史）；v3.1 §101 无剩余 active 写入 |
| `onboarding 七态` 旧状态位 | keep（激活生命周期，与 SensingRuntimeStatus 正交） |
| `data/LegacyScreens` 相关 import | 已随 LegacyScreens.kt 删除清理 |

## 规则

- 任何过渡 wrapper 必须带 removal condition，并在条件满足的同一轮删除；
- 本文件每次 ERA 结束更新；「永久 compatibility」禁止。
