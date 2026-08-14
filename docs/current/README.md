# docs/current — 当前事实索引（ECHO Mind v0.9 · Personal Ambient Intelligence）

> 本目录是**当前状态**的唯一事实源索引；历史 v0.2 / v0.6 / v0.7 / Path A 文档已全部归档于 `docs/archive/`。
> 文档权威顺序见 `docs/DOCUMENT_AUTHORITY.md` / `docs/README_AUTHORITY.md`。

## 当前事实（v0.9.0，2026-08，ERA 12 收口）

| 事实 | 位置 | 状态 |
|---|---|---|
| 产品最高原则 | `docs/product/ECHO_PRODUCT_CONSTITUTION.md` | 冻结（v1.0） |
| Observation 契约 | `PORTRAIT_CONTRACT.md`（仓库根） | 冻结（Ground Truth） |
| 个人智能契约 | `docs/intelligence/PERSONAL_INTELLIGENCE_CONTRACT.md` | 冻结（v1.0） |
| 情绪智能契约（门槛） | `docs/intelligence/AFFECTIVE_CONTRACT.md` | 冻结（实现前置未满足） |
| AI Provider 规格 | `docs/providers/AI_PROVIDER_SPEC.md` | 冻结（v1.0） |
| Presence 架构 | `docs/presence/ECHO_PRESENCE_ARCHITECTURE.md` | 冻结（v1.0） |
| 版本事实源 | `scripts/version_source.json` | 0.9.0（Android versionCode 6） |
| 产品 README | `README.md` | v0.9.0 |
| 发布说明 | `RELEASE_NOTES_v0.9.0.md` | 本版本 |
| 架构决策 | `docs/architecture/ADRS.md` | ADR-001~024 |
| 源码完整性报告 | `docs/architecture/SOURCE_INTEGRITY_REPORT.md` | CURRENT（ERA 12.8 实测） |
| Source Reality | `docs/architecture/SOURCE_REALITY_REPORT.md` | 脚本生成 |
| DI Ownership | `docs/architecture/DI_OWNERSHIP.md` + `di/EchoContainers.kt` | CURRENT（ERA 13.3：六容器自持构造） |
| Distribution Closure | `scripts/build_source_archive.py` / `scripts/verify_source_archive.py` / `scripts/verify_final_package.py` + `scripts/test_source_archive.py` | ERA 12.8 门禁（CI 强制） |
| Release 流程 | `.github/workflows/release-closure.yml` | §17 原子 release（tag v*） |
| 代码架构清单 | `docs/architecture/ANDROID_CODE_INVENTORY.md` | CURRENT（ERA 12） |
| 依赖图 | `docs/architecture/ANDROID_DEPENDENCY_GRAPH.md` | CURRENT（ERA 13 前置） |
| Legacy 移除计划 | `docs/migrations/LEGACY_REMOVAL_PLAN.md` | CURRENT |
| Skills→Actions | `docs/migrations/SKILLS_TO_ACTIONS.md` | CURRENT |
| 文档权威 | `docs/DOCUMENT_AUTHORITY.md` | CURRENT |
| 实施状态锚点 | `docs/IMPLEMENTATION_STATUS.md` | 每轮更新 |

## 一级产品结构（当前 main 事实）

```text
ECHO（EchoSceneScreen + EchoSceneViewModel + ui/echo 组件族）
Journey（ui/journey/JourneyScreen + journey 领域）
Me（ui/me/MeScreen + 六子领域）
```

旧时代命名（Today / Trend / Support-as-Me / Skills-first IA）已全部退出主路径（见 Legacy Removal Plan）。

## 单一版本事实源

- `scripts/version_source.json`：release_version=0.9.0（CI 强制一致）
- 校验：`backend/tests/test_version_consistency.py` + `scripts/release_preflight.sh` + CI

## 历史文档归档

`docs/archive/`：v0.2 实施报告、v0.6 规格与收口、v0.7 Portrait Core 全套（Phase 0/6/9 审计、交付报告、拆分设计）、持续在场 v1.0 差距分析、ARCHITECTURE_REVIEW_v0.7 —— 均标注 SUPERSEDED / HISTORICAL，禁止作为当前要求来源。
