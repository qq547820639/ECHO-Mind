# Release Baseline vs Development Head

> ERA 31 起正式区分两个概念（ERA 39 §42）：文档不得再混用。
> 本文件是 **Release Baseline 的唯一事实锚点**；Development Head 以 git main 为事实。

## 1. 定义

| 概念 | 含义 | 事实源 |
|---|---|---|
| **DEVELOPMENT_HEAD** | 当前 git main 顶端；包含已合入但尚未发布验证的代码 | `git rev-parse HEAD` |
| **LAST_RELEASE_BASELINE** | 最近一次完整走完发布终检（tests / product QA / Android build / backend build / archive integrity / source manifest / SBOM / provenance / artifact manifest / release package）的 commit | 本文件 |

**当前 HEAD ≠ last release。** Product Quality Era（R1–R8）的新代码在 v0.9.0 发布终检之后合入，
尚未完成新一轮 Release Closure（ERA 39 §45）。

## 2. 当前锚点（2026-08-15 记录）

| 字段 | 值 |
|---|---|
| LAST_RELEASE_BASELINE | `578303665334f438010274df22ba001983d1b043` |
| LAST_RELEASE_BASELINE 描述 | feat: 发布就绪全量终检收官 —— README/docs-current/ADRS 事实终检（ADR-001~072 / 迁移链 2→12 / 五套 CI）+ ADR-072 结项 + 后续方向记录 |
| LAST_RELEASE_BASELINE 日期 | 2026-08-15 16:56:24 +0800 |
| 已发布版本 | v0.9.0（APK `ECHO_Mind_v0.9.0.apk`，release 包 `releases/ECHO_Mind_PortraitCore_v0.9.0.zip/.tar.gz`） |
| DEVELOPMENT_HEAD（记录时） | `b16fc1ce119beeaa75104a312ddc7194a2c48c98`（Product Quality Era R8） |
| HEAD 相对 baseline | +10 commits（Product Quality Era R1–R8，尚未重新发布） |

## 3. 纪律

1. 任何文档说「已发布」或「release 通过」时，只对 LAST_RELEASE_BASELINE 及其发布物成立。
2. 描述 Development HEAD 的能力时用 `docs/DEVELOPMENT_STATUS.md`，不得改写历史发布元数据。
3. 下一次 Release Closure（BATCH 8）必须在 clean checkout 上全量重新生成
   tests / product QA / Android build / backend build / archive integrity / source manifest /
   SBOM / provenance / artifact manifest / release package——**不得带着旧 v0.9.0 proof 发布新代码**。
4. Release Closure 通过后，把新 commit 更新为本文件的 LAST_RELEASE_BASELINE。
