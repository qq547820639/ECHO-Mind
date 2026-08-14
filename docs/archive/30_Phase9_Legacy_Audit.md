# Phase 9.1 — Legacy Cleanup 审计分类

> 生成：2026-08-12 · Portrait Core 封板

## 分类标准

| 类 | 定义 | 处置 |
|---|---|---|
| A | migration compatibility（历史数据/迁移依赖） | 保留，不破坏 migration history |
| B | Workbench dependency（机构后台/console 依赖） | 保留，标注 legacy compatibility |
| C | current Portrait dependency（当前画像链依赖） | 保留（核心） |
| D | no caller（无调用方） | 下一版本（v0.8）删除计划 |

## 审计结果

| 组件 | 位置 | 调用方 | 分类 | 备注 |
|---|---|---|---|---|
| CheckinCreate / checkins | `backend/app/api/legacy.py`、`app/models.py` Checkin | legacy.py（410 存根）、data_rights（DSR 矩阵）、escalations（审计兼容） | **A** | v0.8 removal target（README 已标注）；Android saveCheckin 已删（v0.6.1） |
| JournalEntry / journals | legacy.py（410 写存根 + GET 只读）、narratives | data_rights、legacy | **A** | 同 Checkin |
| Questionnaire | legacy.py（410 存根）、schemas.QuestionnaireCreate | legacy | **A** | v0.8 removal target |
| PracticeCompletion | legacy.py（410 存根） | legacy | **A** | v0.8 removal target |
| DailyNarrative / build_daily_narrative | `app/services/profile.py`、`app/api/narratives.py` | narratives API（机构后台历史兼容） | **B** | v0.6 已标记 legacy compatibility；Portrait 主链不依赖它（Phase 5 后 features.py 不再调用 narrative——已由 materializer 取代） |
| UserProfile / profiles.py | `app/api/profiles.py`、`app/services/profile.py` | Trend（Phase 6 移除中）、机构后台 | **B/C** | Phase 6.5 移除 Android Trend 依赖后为 B 类（机构后台）；traits 标记 v0.8 removal target |
| TenantPortrait / tenant_portrait.py | `app/api/tenant_portrait.py` | 机构后台（去标识群体视图） | **B** | 保留（机构需求），mood_distribution 恒空 + suppressed |
| 旧 Skill 路径（skill 直接下发 vs Portrait 外围） | `app/api/skills.py` | Skill 页（外围能力） | **C** | 保留（外围），不占据主叙事 |
| 旧 content packs | content-packs/ | 机构后台 | **B** | 保留（外围） |
| Path A 文档 | docs/archive/（已归档） | 无 | **D（文档）** | 已归档 docs/archive/（Phase 9.2） |

## 结论

- **无 D 类代码**可安全删除（所有旧组件均有 migration/后台调用方或 410 存根）；
- Checkin/Journal/Questionnaire/Practice **410 存根保留**（migration compatibility），v0.8 退役计划已标注；
- DailyNarrative **不再被 Portrait 主链调用**（Phase 5 materializer 取代），但 narratives API 保留（机构后台），标记 legacy；
- 本轮**不删除任何 migration 历史**（遵守工作纪律）。

## v0.8 删除计划（建议）

1. Checkin/Questionnaire/Practice 410 存根 → 若机构后台无历史消费者，删除 schema 与路由
2. DailyNarrative 表 → 数据保留策略确认后迁移
3. UserProfile.traits → 机构后台改读 aggregate/baseline 后移除
4. Path A 文档 → 保留 archive 引用

> 本审计仅为分类记录；实际删除需 v0.8 单独评审（本轮 P0/P1 优先）。
