# Release Baseline vs Development Head

> ERA 31 起正式区分两个概念（ERA 39 §42）：文档不得再混用。
> 本文件是 **Release Baseline 的唯一事实锚点**；Development Head 以 git main 为事实，
> 其状态见 `docs/STATUS.md`（§1 Current Development HEAD）。

## 1. 定义

| 概念 | 含义 | 事实源 |
|---|---|---|
| **DEVELOPMENT_HEAD** | 当前 git main 顶端；包含已合入但尚未发布验证的代码 | `git rev-parse HEAD` |
| **LAST_RELEASE_BASELINE** | 最近一次完整走完发布终检（tests / product QA / Android build / backend build / archive integrity / source manifest / SBOM / provenance / artifact manifest / release package）的 commit | 本文件 |

## 2. 当前锚点（2026-08-15 更新：v0.11.0 Release Closure）

| 字段 | 值 |
|---|---|
| LAST_RELEASE_BASELINE | `88db3b9c0d9384b2f492fa8e135eaa3806562d74` |
| LAST_RELEASE_BASELINE 描述 | chore: v0.11.0 release closure artifacts（SOURCE_MANIFEST 1083 / DELIVERY / SBOM 80） |
| LAST_RELEASE_BASELINE 日期 | 2026-08-15 |
| 已发布版本 | v0.11.0（versionCode 8；APK `ECHO_Mind_v0.11.0.apk` 本地测试密钥签名 v2,v3，生产签名由运营签名环境执行；release 包 `releases/ECHO_Mind_v0.11.0.release.zip`） |
| 上一 baseline | v0.10.0（`6e84086`，被 v0.11.0 取代） |
| DEVELOPMENT_HEAD（记录时） | `88db3b9`（provenance git_commit 与之相等；Release Closure 全绿） |
| Release Closure 证据 | backend 1077 passed + 1 skipped + ruff + mypy strict；Android testDebugUnitTest 全绿 + lint + detekt + assembleDebug；assembleRelease（-PECHO_GIT_COMMIT 全 40 位钉定）；SOURCE_MANIFEST 1083；确定性归档双格式验证；SBOM 80；provenance（release / signed v2,v3 / APK 内嵌 commit 绑定）；artifact manifest 9 条目；final package §18 终态门禁 PASS；test_release_set + test_source_archive 16/16 |

## 3. 纪律

1. 任何文档说「已发布」或「release 通过」时，只对 LAST_RELEASE_BASELINE 及其发布物成立。
2. 描述 Development HEAD 的能力时用 `docs/STATUS.md`，不得改写历史发布元数据。
3. 下一次 Release Closure 必须在 clean checkout 上全量重新生成
   tests / product QA / Android build / backend build / archive integrity / source manifest /
   SBOM / provenance / artifact manifest / release package——**不得带着旧 v0.11.0 proof 发布新代码**。
4. Release Closure 通过后，把新 commit 更新为本文件的 LAST_RELEASE_BASELINE。
5. ERA 32 补充：closure 之后任何受控文件更新（含本文档）都必须同步重生成 SOURCE_MANIFEST
   （发布包内清单为 closure 时点的快照；仓库清单以 git 受控文件集为唯一事实源）。
