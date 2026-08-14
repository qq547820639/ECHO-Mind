# docs/current — 当前事实索引（Portrait Core v0.7）

> 本目录为**当前状态**的唯一事实源索引；历史 v0.2 / v0.6 / Path A 文档见 `docs/archive/`。

## 当前事实（v0.7.0，2026-08-12）

| 事实 | 位置 | 状态 |
|---|---|---|
| 产品最高契约 | `PORTRAIT_CONTRACT.md`（仓库根） | 冻结（v1.0） |
| 产品 README | `README.md` | v0.7.0 pilot-candidate |
| 发布说明 | `RELEASE_NOTES_v0.7.0.md` | 本版本 |
| OpenAPI 契约 | `docs/openapi.json` | 60 路径（实时导出） |
| 跨端契约清单 | `docs/contract-manifest.json` | v0.7.0（21 required endpoints） |
| Alembic head | `20260814_0001`（订阅生命周期） | roundtrip PASS |
| Delivery Manifest | `DELIVERY_MANIFEST.json` | v0.7.0（真实测试计数） |
| 端侧画像引擎 | `android/.../localportrait/*.kt` | 本地模式 + 离线回退（与服务端同算法镜像） |
| 分析消息（周小结） | `GET /v1/me/messages` + `LocalPortraitDigest` | 拉取式推送过渡（FCM 就绪后换真推送） |
| 订阅生命周期 | `GET /v1/me/subscription` + 激活码 subscription_days | 订阅制状态机（NULL=永不过期，到期 402 冻结云端能力） |
| Phase 0 事实基线 | `docs/current/00_Phase0_Fresh_Truth_Audit.md` | 本封板轮 |
| Phase 6 UX 规格 | `docs/current/20_Phase6_Onboarding_UX_Spec.md` | 产品经理冻结 |
| Phase 9 legacy 审计 | `docs/current/30_Phase9_Legacy_Audit.md` | 分类记录 |

## 单一版本事实源

- `scripts/version_source.json`：release_version=0.7.0（CI 强制一致）
- 校验：`backend/tests/test_version_consistency.py`（README/pyproject/APP_VERSION/Android versionName/DELIVERY_MANIFEST 一致）

## 历史文档归档

`docs/archive/`：v0.2 实施报告、v0.6 PRD 增量/架构/Onboarding 规格/QA 报告/收口总结/收口架构、v0.6.1 交付报告、v0.6.2 权限矩阵、自动验证报告等 13 份。
