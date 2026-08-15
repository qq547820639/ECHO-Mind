# Source Integrity Report —— 源码完整性报告（ERA 12.8 FINAL DISTRIBUTION CLOSURE）

> 状态：CURRENT · 版本 v0.9.0 · 本报告随 ERA 12.8 实测更新。

## 1. 结论

Git source = SOURCE_MANIFEST = distributed source ZIP/tar.gz = extracted verified source = tested source = built source = signed APK = BUILD_PROVENANCE = RELEASE_ARTIFACT_MANIFEST = final release package，同一提交/同一交付状态。✅

## 2. 提示词疑点核查（v3.2 §2 → ERA 12.8 终态）

| 疑点 | ERA 12.8 核查结果 | 结论 |
|---|---|---|
| EchoRuntimeCoordinator 源码缺失 | **此前确为真问题**：文件（145 行正式实现）在工作树存在，但 `.gitignore` 裸 `runtime/` 规则使其从未入库——SOURCE_MANIFEST 记录了它，`git archive`/clean checkout 必漏包。已修复：`.gitignore` 根锚定 `/runtime/`，文件入库（commit 本 ERA） | 已修复（git 受控 + SourceIntegrityTest 断言防回归） |
| EchoRuntimeHealth 模型 | 已存在；五态（READY/STARTING/DEGRADED/PAUSED/UNAVAILABLE）+ 纯映射函数（sensingComponentHealth/providerComponentHealth） | 完成 |
| Unicode 文件名打包后 path mutation（#Uxxxx） | 根因：历史 `zip` CLI 打包对非 ASCII 条目不置 UTF-8 标志（消费端 cp437/乱码/libarchive #U 转义）。修复：Python 确定性打包，NFC + EFS 0x800 显式置位 + 验证门禁拒绝缺标志条目 | 已修复（test_source_archive 负例覆盖） |
| Git checkout 与 final archive 非同一 Gate | 修复：manifest=git 受控文件集；archive 由同一文件集构建并内嵌 manifest；`verify_source_archive.py` 解包后二次校验；CI source-integrity/release-closure 同 run 执行 | 已修复 |
| root release APK 未绑定 provenance | 修复：`BUILD_PROVENANCE` 记录 `release_apk_path/release_apk_sha256/unsigned_apk_sha256/signing_stage/signature_scheme`（schema v2） | 已修复 |
| 文档描述打包前 worktree | 修复：本报告/`docs/STATUS.md` 只描述 git 受控状态；manifest 无法再记录未入库文件 | 已修复 |

## 3. Kotlin / Python 包完整性

- Kotlin 包：`app/ui/echo/journey/me` + `sensing/localportrait/model/presence/intelligence/memory/actions/journey/runtime/data/security/di` —— 全部有源文件（SOURCE_REALITY_REPORT：108 kt / 68 py / 5 组件 / 5 Worker / unresolved=0）；
- `com.yunjue.echo.mind.runtime` 包：EchoRuntimeCoordinator.kt（含 EchoRuntimeState/EchoRuntimeHealth/RuntimeComponentStatus）—— git 受控 ✅；
- Manifest 组件（Activity + 5 Services + 1 Provider）→ 源类全部存在（SourceIntegrityTest 断言）；
- Worker（SyncWorker/MessageCheckWorker/EveningReminderWorker/SensingWatchdogWorker/PresenceRefreshWorker）→ 实现类全部存在；
- package 声明与目录一致（SourceIntegrityTest 断言 100+ 文件）。

## 4. Distribution Closure 实测（ERA 12.8 本轮）

| 项 | 结果 |
|---|---|
| SOURCE_MANIFEST（git 受控源文件集） | 479 文件，verify_source_manifest PASS |
| source archive（zip + tar.gz）构建 | 确定性（同 commit 字节一致），479 文件 + 内嵌 manifest |
| verify_source_archive（zip / tar.gz） | PASS（hash 479/479，NFC PASS，UTF-8 标志 PASS，required PASS，unexpected 0） |
| test_source_archive.py（fixture：中文/emoji/空格/长路径 + 篡改负例） | 10 passed |
| 根 APK provenance 绑定 | release_apk_sha256 / unsigned_apk_sha256 / signing_stage / signature_scheme 记录 |
| RELEASE_ARTIFACT_MANIFEST | 仅最终交付物（APK/idsig/SBOM/provenance/delivery/notes/source archives/SOURCE_MANIFEST） |
| final release package 验证 | verify_final_package PASS（§18 终态门禁） |

## 5. 交付元数据链（DAG 无循环）

SOURCE_MANIFEST → source_tree_sha256 →（clean build）APK / SBOM / source archive → BUILD_PROVENANCE → RELEASE_ARTIFACT_MANIFEST → final release package（验证时递归复核）。

- BUILD_PROVENANCE.json：schema v2；git_commit / git_dirty / release_type / source_tree_sha256 / source_manifest_sha256 / 版本三元组 / jdk / gradle / python / build_timestamp_utc / ci_run_id / unsigned+signed APK 双哈希 / signing_stage / signature_scheme / sbom_sha256 / source_archive_sha256；
- 敏感 signing key 材料永不入 provenance / 仓库（keystore 走 CI secrets，本仓库 .gitignore 忽略 *.keystore/*.jks）。

## 6. 规则（此后永久）

- 禁止：旧 manifest 覆盖源码、哈希来自旧快照、APK 无 provenance、ZIP 上覆盖文件、dirty tree 出正式 release；
- SOURCE_MANIFEST 只描述 git 受控源文件；RELEASE_ARTIFACT_MANIFEST 只描述最终交付物；
- release 流程：clean checkout → verify source → backend gates → Android test/lint/detekt → release build →（sign）→ SBOM → SOURCE_MANIFEST → source archive → verify extracted archive → BUILD_PROVENANCE → DELIVERY_MANIFEST → RELEASE_ARTIFACT_MANIFEST → final package → verify final package（CI `release-closure.yml`，同一 run）；
- 本报告每轮 release 前更新。

## 7. 已知边界（如实记录）

- Android 本机构建依赖 JDK+SDK（本机 Corretto-17 @ /tmp/echo-build + SDK 36）；缺失时由 CI android-ci/release-closure 兜底，本地如实标注 ENVIRONMENT BLOCKED，不伪造 PASS；
- 正式签名发布需要 repo secrets（ANDROID_KEYSTORE_BASE64 等）；无 secrets 时 release-closure 交付 unsigned development snapshot（provenance 如实记录 signing_stage=unsigned）。
