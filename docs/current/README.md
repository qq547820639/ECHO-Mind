# docs/current — 当前事实索引（Portrait Core v0.7）

> 本目录为**当前状态**的唯一事实源索引；历史 v0.2 / v0.6 / Path A 文档见 `docs/archive/`。

## 当前事实（v0.7.0，2026-08-12）

| 事实 | 位置 | 状态 |
|---|---|---|
| 产品最高契约 | `PORTRAIT_CONTRACT.md`（仓库根） | 冻结（v1.0） |
| 产品 README | `README.md` | v0.8.0 Personal Ambient Intelligence |
| 发布说明 | `RELEASE_NOTES_v0.8.0.md` | 本版本（ERA 1-10） |
| 版本事实源 | `scripts/version_source.json` | 0.8.0（Android versionCode 5） |
| OpenAPI 契约 | `docs/openapi.json` | 60 路径（实时导出） |
| 跨端契约清单 | `docs/contract-manifest.json` | v0.7.0（21 required endpoints） |
| Alembic head | `20260814_0001`（订阅生命周期） | roundtrip PASS |
| Delivery Manifest | `DELIVERY_MANIFEST.json` | v0.7.0（真实测试计数） |
| 端侧画像引擎 | `android/.../localportrait/*.kt` | 本地模式 + 离线回退（与服务端同算法镜像） |
| 分析消息（周小结） | `GET /v1/me/messages` + `LocalPortraitDigest` | 拉取式推送过渡（FCM 就绪后换真推送） |
| 订阅生命周期 | `GET /v1/me/subscription` + 激活码 subscription_days | 订阅制状态机（NULL=永不过期，到期 402 冻结云端能力） |
| Phase 0 事实基线 | `docs/current/00_Phase0_Fresh_Truth_Audit.md` | 本封板轮 |
| Phase 6 UX 规格 | `docs/current/20_Phase6_Onboarding_UX_Spec.md` | **SUPERSEDED**（onboarding 已三步 + Awakening；见 ANDROID_CODE_INVENTORY） |
| Phase 9 legacy 审计 | `docs/current/30_Phase9_Legacy_Audit.md` | 分类记录 |
| 持续在场 v1.0 落地计划 | `docs/current/50_持续在场AI陪伴_差距分析与实施路线_v1.0.md` | **SUPERSEDED**（被 v2/v3 Master Prompt 取代；见 README_AUTHORITY.md） |
| 产品宪法 | `docs/product/ECHO_PRODUCT_CONSTITUTION.md` | 冻结（v1.0，ERA 1） |
| 个人智能契约 | `docs/intelligence/PERSONAL_INTELLIGENCE_CONTRACT.md` | 冻结（v1.0，ERA 1） |
| 情绪智能契约（门槛） | `docs/intelligence/AFFECTIVE_CONTRACT.md` | 冻结（v1.0，ERA 10 前置） |
| AI Provider 规格 | `docs/providers/AI_PROVIDER_SPEC.md` | 冻结（v1.0，ERA 1） |
| Presence 架构 | `docs/presence/ECHO_PRESENCE_ARCHITECTURE.md` | 冻结（v1.0，ERA 1） |
| 目标领域模型 | `docs/intelligence/ECHO_SELF_MODEL.md` | v1.0（ERA 6 实现） |
| 迁移架构 | `docs/architecture/MIGRATION_ARCHITECTURE.md` | v1.0 |
| 架构地图与冲突清单 | `docs/architecture/ECHO_ARCHITECTURE_MAP.md` | v1.0（ERA 1 审计产出） |
| ADR 决策记录 | `docs/architecture/ADRS.md` | v1.0（ADR-001~020，最终裁决） |
| 实施状态锚点 | `docs/IMPLEMENTATION_STATUS.md` | **v0.8.0 交付完成** |

## 单一版本事实源

- `scripts/version_source.json`：release_version=0.7.0（CI 强制一致）
- 校验：`backend/tests/test_version_consistency.py`（README/pyproject/APP_VERSION/Android versionName/DELIVERY_MANIFEST 一致）

## 历史文档归档

`docs/archive/`：v0.2 实施报告、v0.6 PRD 增量/架构/Onboarding 规格/QA 报告/收口总结/收口架构、v0.6.1 交付报告、v0.6.2 权限矩阵、自动验证报告等 13 份。
