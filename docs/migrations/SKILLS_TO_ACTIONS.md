# Skills → Actions 迁移计划

> 状态：CURRENT · ERA 12 · 旧 Skill 系统（沙箱下发卡片）向 Actions 运行时收敛的分类与迁移计划。

## 1. 现状

- 免费基础行动已由 `EchoActionRuntime` + `EchoActionLayer`（ECHO Scene 内）承担：呼吸 / 短暂离开屏幕 / 什么也不做。
- 旧 Skill 卡片（backend 沙箱下发、专业审签）保留在 ECHO Scene「更多能力（订阅）」分区（`ui/echo/components/SkillListSection.kt` + `ui/SkillCardHost.kt` + `ui/SkillActionRenderers.kt`）。

## 2. 分类（v3.1 §21）

| 旧能力 | 分类 | 说明 |
|---|---|---|
| 缓慢呼吸等基础练习 | **MIGRATE_TO_ACTION**（已完成） | 已由 Scene 内 1 分钟呼吸替代，统一视觉语言 |
| 专业审签的订阅练习卡片 | **KEEP_AS_CONTENT** | 订阅权益内容（ADR-020 单档边界），保留在「更多能力（订阅）」分区 |
| 危机/专业支持入口 | **SUPPORT_ONLY** | 已由全局紧急 FAB + Me → Support 承担 |
| 旧「能力」Tab 全页（SkillListScreen） | **DELETE**（已完成） | v2-1 已删除 |

## 3. 迁移后处置

- `SkillCardHost.kt` / `SkillActionRenderers.kt` / `rememberSkillList`：随订阅能力内容保留（KEEP_AS_CONTENT），**不再增长新逻辑**；
- 新增免费行动一律走 `EchoActionRuntime`（Scene 内执行，统一视觉）；
- 若订阅能力未来产品化调整（ADR-020 边界重开），本文件先行更新。

## 4. 移除条件

- 订阅内容下架或迁入独立内容引擎 → `SkillCardHost` 系列删除；
- 在此之前不触碰（有真实调用方与真实用户价值）。
