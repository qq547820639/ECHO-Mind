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

生成方式：`QaSnapshotSuiteTest`（:feature:qa 单元测试）确定性重放生成，可随时重复生成零漂移。
