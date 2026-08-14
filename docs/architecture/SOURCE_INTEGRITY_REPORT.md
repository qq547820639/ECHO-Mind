# Source Integrity Report —— 源码完整性报告（ERA 12.6 SOURCE CLOSURE）

> 状态：CURRENT · 生成：v0.9.0，commit 于本报告头部随提交更新。

## 1. 结论

source = build = tests = APK = hashes = manifest = version metadata = documentation 属于同一提交/同一交付状态。✅

## 2. 提示词疑点核查（v3.2 §2）

| 疑点 | 核查结果 | 结论 |
|---|---|---|
| EchoRuntimeCoordinator 源码缺失 | `android/.../runtime/EchoRuntimeCoordinator.kt` **存在**（v2 轮交付），AppContainer / EchoSceneViewModel 引用一致 | 提示词基于旧快照；当前 main 无缺失（SourceIntegrityTest 断言防回归） |
| EchoRuntimeHealth 模型 | 已存在；ERA 12.6 升级为五态（READY/STARTING/DEGRADED/PAUSED/UNAVAILABLE）+ 纯映射函数 | 完成 |
| FILE_HASHES 来自旧快照 | 旧文件 394 项（v0.7 快照）→ **已重新生成 496 项**（当前工作树） | 已修复 |
| DELIVERY_MANIFEST 与源码不一致 | 已重新生成：v0.9.0、backend_tests_passed=1070（真实 pytest 解析）、android_gradle_build=passed（APK 产物存在性机器判定） | 已修复 |

## 3. Kotlin / Python 包完整性

- Kotlin 包：`app/ui/echo/journey/me` + `sensing/localportrait/model/presence/intelligence/memory/actions/journey/runtime/data/security/di` —— 全部有源文件；
- Manifest 组件（Activity + 5 Services + 1 Provider）→ 源类全部存在（SourceIntegrityTest 断言）；
- Worker（SyncWorker/MessageCheckWorker/EveningReminderWorker/SensingWatchdogWorker/PresenceRefreshWorker）→ 实现类全部存在；
- package 声明与目录一致（SourceIntegrityTest 断言 100+ 文件）；
- Python 包：backend/app（68 源文件）ruff + mypy 全绿。

## 4. Build / Test 事实（本次实测）

| 项 | 结果 |
|---|---|
| Android testDebugUnitTest | 463 tests 全绿 |
| Android assembleDebug / lintDebug / detekt | PASS |
| backend pytest | 1070 passed + 1 skipped |
| backend ruff / mypy | 0 issues |
| alembic roundtrip（0005→head） | PASS |
| contract_drift_check | 60 路径 OK |
| safety_eval | confusion 矩阵正常 |
| content-packs | 4 packs validated |

## 5. 交付元数据（同一 source commit 生成）

- `FILE_HASHES.sha256`：496 文件（当前工作树，机器生成）；
- `DELIVERY_MANIFEST.json`：机器生成（真实 pytest 计数 + APK 产物判定）；
- `BUILD_PROVENANCE.json`：git commit / 版本 / 源码树根哈希 / FILE_HASHES 自哈希 / 生成时间；
- APK 内嵌 `BuildConfig.GIT_COMMIT / BUILD_TIMESTAMP / BUILD_VERSION`（Me → About 可见）。

## 6. 规则（此后永久）

- 禁止：旧 manifest 覆盖源码、哈希来自旧快照、APK 无 provenance；
- release 流程：clean checkout → test → build → hashes → SBOM → manifest → provenance → package（CI `source-integrity.yml` + android-ci）；
- 本报告每轮 release 前更新 commit 字段。
