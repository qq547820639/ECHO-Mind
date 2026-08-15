# ECHO Mind — 长期用户 Fixture 产品快照索引

Product Quality Era（ERA 19）确定性 fixture：7 个 profile × Day 0/3/7/28/90/180。
同一文件内纵向并排 → 检验「同一个 ECHO 在成长」；跨文件横向比较 → 检验「不同用户明显不是同一个 ECHO」。

| Profile | 人群形状 | 快照 |
|---|---|---|
| PROFILE_A_STABLE | A · 稳定通勤者 | [snapshots/PROFILE_A_STABLE.md](snapshots/PROFILE_A_STABLE.md) |
| PROFILE_B_NIGHT_OWL | B · 夜猫创作者 | [snapshots/PROFILE_B_NIGHT_OWL.md](snapshots/PROFILE_B_NIGHT_OWL.md) |
| PROFILE_C_IRREGULAR | C · 不规律自由职业 | [snapshots/PROFILE_C_IRREGULAR.md](snapshots/PROFILE_C_IRREGULAR.md) |
| PROFILE_D_TRAVEL | D · 高频出差 | [snapshots/PROFILE_D_TRAVEL.md](snapshots/PROFILE_D_TRAVEL.md) |
| PROFILE_E_PROJECT_CRUNCH | E · 项目冲刺期 | [snapshots/PROFILE_E_PROJECT_CRUNCH.md](snapshots/PROFILE_E_PROJECT_CRUNCH.md) |
| PROFILE_F_LOW_DATA | F · 低感知数据用户 | [snapshots/PROFILE_F_LOW_DATA.md](snapshots/PROFILE_F_LOW_DATA.md) |
| PROFILE_G_WEEKEND_DIFFERENT | G · 周末完全不同 | [snapshots/PROFILE_G_WEEKEND_DIFFERENT.md](snapshots/PROFILE_G_WEEKEND_DIFFERENT.md) |

相关文档：
- [PRODUCT_EXPERIENCE_REVIEW.md](PRODUCT_EXPERIENCE_REVIEW.md) — 逐阶段体验审查（ERA 19 §5）
- [ECHO_SCENE_AUDIT.md](ECHO_SCENE_AUDIT.md) — ECHO Scene 产品质量审计（ERA 20 §8）
- [PERSONAL_REASONING_EVAL.md](PERSONAL_REASONING_EVAL.md) — Personal Reasoning 质量 eval（ERA 22 / Batch 2）
- [MEMORY_SELF_MODEL_EVAL.md](MEMORY_SELF_MODEL_EVAL.md) — Memory / Self Model 质量 eval（ERA 23 / Batch 3）
- [JOURNEY_EMOTIONAL_VALUE_EVAL.md](JOURNEY_EMOTIONAL_VALUE_EVAL.md) — Journey 情感价值 eval（ERA 24 / Batch 4）
- [ME_TRUST_EXPERIENCE_EVAL.md](ME_TRUST_EXPERIENCE_EVAL.md) — Me / Trust Experience eval（ERA 25 / Batch 5）
- [PRODUCT_SIMPLIFICATION_EVAL.md](PRODUCT_SIMPLIFICATION_EVAL.md) — 简化 / 模块卫生（ERA 26/28 / Batch 6）
- [RELEASE_QUALITY_GATE.md](RELEASE_QUALITY_GATE.md) — 发布质量门（Batch 8 收官）
- [../DOGFOOD_PROTOCOL.md](../DOGFOOD_PROTOCOL.md) — 30 天 dogfood 协议（ERA 29 §63）
- [ERA31_VISUAL_REVIEW_R1.md](ERA31_VISUAL_REVIEW_R1.md) — ERA 31 Real Render Review R1（真实画面缺陷修复闭环）
- [ERA31_ROUND2.md](ERA31_ROUND2.md) — ERA 31 R2（跨语言黄金门 + 运动序列 + Core Personal Reasoning Set）
- [CORE_PERSONAL_REASONING_SET.md](CORE_PERSONAL_REASONING_SET.md) — BATCH 2 §20 最高价值问题集（26 条）
- [QA_MIRROR_AUDIT.md](QA_MIRROR_AUDIT.md) — :feature:qa 与 production 重复实现审计（ERA 31 BATCH 1）
- [PERSONAL_REASONING_HUMAN_REVIEW.md](PERSONAL_REASONING_HUMAN_REVIEW.md) — BATCH 2 真实回答四层人审（R3/R4）
- [SELF_MODEL_VALUE_AUDIT.md](SELF_MODEL_VALUE_AUDIT.md) — BATCH 3 Self Model 价值审计（R4/R5）
- [ERA31_ROUND5.md](ERA31_ROUND5.md) — ERA 31 R5（BATCH 3 收口 / Journey 第一视觉修复）
- [ERA31_ROUND6.md](ERA31_ROUND6.md) — ERA 31 R6（BATCH 4 收口：时间地标入 Journey / Time-to-ECHO 指标）
- [TIME_TO_ECHO.md](TIME_TO_ECHO.md) — BATCH 5 §50 Time-to-ECHO 测量契约
- [ERA31_ROUND7.md](ERA31_ROUND7.md) — ERA 31 R7（Dogfood 准备 / Delete Audit / QA mirror 收尾）
- [DELETE_AUDIT.md](DELETE_AUDIT.md) — BATCH 7 §39/§40 删除与订阅审计
- [ERA31_ROUND8.md](ERA31_ROUND8.md) — ERA 31 R8（core:ports 依赖倒置收官，模块化停止）
- [ERA31_ROUND10.md](ERA31_ROUND10.md) — ERA 31 R9/R10（v0.10.0 Release Closure 全绿）
- [ERA31_ROUND11.md](ERA31_ROUND11.md) — ERA 31 R11（Headline 自然句优先 / What ECHO Knows 人类语言）
- [ERA31_ROUND12.md](ERA31_ROUND12.md) — ERA 31 R12（ECHO Scene 全链路走查：日期降噪）
- [ERA31_ROUND13.md](ERA31_ROUND13.md) — ERA 31 R13（§16 Wallpaper 自适应帧率：静态期 4fps）
- [ERA31_ROUND14.md](ERA31_ROUND14.md) — ERA 31 R14（Dream 表面自适应帧率对齐）
- [ERA31_ROUND15.md](ERA31_ROUND15.md) — ERA 31 R15（Why 层安静化：AI 催促移除 + 入口一致化）
- [personal_answer_review/answers.md](personal_answer_review/answers.md) — Core Set 26 问真实回答捕获（机器生成）
- [../visual-review/index.html](../visual-review/index.html) — ERA 31 真实渲染画廊（浏览器打开直接看 ECHO）

生成方式：`QaSnapshotSuiteTest`（:feature:qa 单元测试）确定性重放生成，可随时重复生成零漂移。
