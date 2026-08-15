# Release Baseline vs Development Head

> ERA 31 起正式区分两个概念（ERA 39 §42）：文档不得再混用。
> 本文件是 **Release Baseline 的唯一事实锚点**；Development Head 以 git main 为事实。

## 1. 定义

| 概念 | 含义 | 事实源 |
|---|---|---|
| **DEVELOPMENT_HEAD** | 当前 git main 顶端；包含已合入但尚未发布验证的代码 | `git rev-parse HEAD` |
| **LAST_RELEASE_BASELINE** | 最近一次完整走完发布终检（tests / product QA / Android build / backend build / archive integrity / source manifest / SBOM / provenance / artifact manifest / release package）的 commit | 本文件 |

**当前 HEAD ≠ last release。** ERA 31 v0.10.0 后产品主链打磨轮 R11–R32（Real Render /
Scene / Wallpaper / Reasoning / Correction Reuse / Journey / 苏醒 / 信任与安静化走查）已在
v0.10.0 Release Closure 之后合入 main，尚未完成新一轮 Release Closure（ERA 39 §45）。

## 2. 当前锚点（2026-08-15 更新：v0.10.0 Release Closure）

| 字段 | 值 |
|---|---|
| LAST_RELEASE_BASELINE | `6e840866265da153b1164e780a02646d6b952b6d` |
| LAST_RELEASE_BASELINE 描述 | chore: v0.10.0 release closure artifacts（uv.lock / openapi / SOURCE_MANIFEST 1019 / DELIVERY / SBOM） |
| LAST_RELEASE_BASELINE 日期 | 2026-08-15 |
| 已发布版本 | v0.10.0（versionCode 7；APK `ECHO_Mind_v0.10.0.apk` 本地测试密钥签名 v2,v3，生产签名由运营签名环境执行；release 包 `releases/ECHO_Mind_v0.10.0.release.zip`） |
| 上一 baseline | v0.9.0（`5783036`，被 v0.10.0 取代） |
| DEVELOPMENT_HEAD（记录时） | `6e84086`（provenance git_commit 与之相等；Release Closure 全绿） |
| Release Closure 证据 | LOCAL PREFLIGHT PASSED（backend 1077 / android testDebugUnitTest+assembleDebug+lintDebug）+ assembleRelease（-PECHO_GIT_COMMIT 钉定）+ SOURCE_MANIFEST 1019 + 确定性归档双格式验证 + SBOM 80 + provenance（release / signed v2,v3 / APK 内嵌 commit 绑定）+ artifact manifest + final package §18 终态门禁 PASS + test_release_set 6/6 |

## 3. 纪律

1. 任何文档说「已发布」或「release 通过」时，只对 LAST_RELEASE_BASELINE 及其发布物成立。
2. 描述 Development HEAD 的能力时用 `docs/DEVELOPMENT_STATUS.md`，不得改写历史发布元数据。
3. 下一次 Release Closure 必须在 clean checkout 上全量重新生成
   tests / product QA / Android build / backend build / archive integrity / source manifest /
   SBOM / provenance / artifact manifest / release package——**不得带着旧 v0.10.0 proof 发布新代码**。
4. Release Closure 通过后，把新 commit 更新为本文件的 LAST_RELEASE_BASELINE。
