# ECHO Mind Implementation Status

> 本文件是长期自主演进的**唯一状态锚点**。每轮结束更新；禁止虚假完成状态。
> 文档权威顺序见 `docs/DOCUMENT_AUTHORITY.md`。

## Current Era

**ERA 12.7 — SOURCE CLOSURE & RELEASE ATOMICITY 完成 ✅ → 下一轮进入 ERA 13（应用层收口：JourneyViewModel/MeViewModel）**

## Source Closure Status（ERA 12.7 实测）

- ✅ runtime 排除 bug 已修复（SOURCE_MANIFEST 含 runtime/EchoRuntimeCoordinator.kt；verify_source_manifest 472 源文件一致）
- ✅ SOURCE_MANIFEST / RELEASE_ARTIFACT_MANIFEST 分离（源文件 vs APK/SBOM/Delivery/Provenance/Notes）
- ✅ provenance DAG 无循环（source→build→artifacts→provenance→artifact manifest）
- ✅ build status 由 pipeline run 注入（ANDROID_GRADLE_BUILD_RESULT；CI android-ci→release-metadata 同 run 传递）
- ✅ SOURCE_REALITY_REPORT（脚本生成：108 kt/68 py/5 组件/5 Worker/unresolved=0）
- ✅ SourceIntegrityTest 自动发现升级（引用解析/DAO 存在/领域包/五态）

## Source Integrity

- ✅ `docs/architecture/SOURCE_INTEGRITY_REPORT.md`：source = build = tests = hashes = manifest = provenance 同快照（核查结论 + 实测表）
- ✅ EchoRuntimeCoordinator/EchoRuntimeHealth 存在且五态化（提示词缺失疑点已排除）
- ✅ FILE_HASHES.sha256 重新生成（496 文件，机器生成）；DELIVERY_MANIFEST 机器生成（真实 pytest 计数 + APK 产物判定）
- ✅ BUILD_PROVENANCE.json（git commit/版本/源码树哈希/FILE_HASHES 自哈希）
- ✅ BuildConfig 内嵌 GIT_COMMIT / BUILD_TIMESTAMP / BUILD_VERSION（Me → About 可见）
- ✅ SourceIntegrityTest（Manifest 组件/Worker/包路径/关键类/五态模型，5 断言）+ CI `source-integrity.yml`

## Build Status（本次实测）

- Android：**463 tests 全绿**；assembleDebug / lintDebug / detekt PASS
- backend：pytest **1070 passed + 1 skipped**；ruff 0 / mypy 0；alembic roundtrip PASS；contract drift 60 路径 OK；safety eval 正常；content-packs 4 validated

## Completed

- v1 ERA 1-10 / v2 两轮 / ERA 12（批次 1+2+v3.1 收尾）——见 RELEASE_NOTES_v0.9.0 与 ADR-001~024
- **ERA 12.6 Source Closure**（本轮）：runtime 核查、交付元数据重建、provenance、BuildConfig、SourceIntegrityTest、CI gate、文档归档（ARCHITECTURE_REVIEW→archive、docs/current 重建为 v0.9 索引）

## In Progress

- 无。

## Blocked

- 无。

## Legacy Remaining

| 模块 | 处置 | 状态 |
|---|---|---|
| `ui/SkillCardHost.kt` + `SkillActionRenderers.kt` | KEEP_AS_CONTENT（订阅内容，SKILLS_TO_ACTIONS.md 已裁决） | 最终决策 |
| backend 410 存根 | keep（机构历史只读） | 最终决策 |
| 其余旧时代 UI | 已全部删除（LEGACY_REMOVAL_PLAN.md 防回归锚点） | ✅ |

## Architecture Debt

- `ui/journey/JourneyScreen.kt` 仍直接编排 7 个依赖（repository/AI/memory/flags/sync）——**下一轮 JourneyViewModel 化**
- `ui/me/MeScreen.kt` 仍持有共享状态编排（六子领域已拆，但 root 状态提升未 ViewModel 化）——**下一轮 MeViewModel + DataAndSensingViewModel**
- AppContainer 子容器为组合式分组（构造仍在 root）——ERA 13.5 前抽 ports + 真拥有

## Security Debt

- SQLCipher passphrase KDF（Keystore 派生 + 固定 IV 域）→ ERA 17 用 HKDF/HMAC 标准方案迁移（需迁移测试，禁止直接换算法）
- GitHub Actions actions 仍 pin major tag（TODO(ERA17)：pin commit SHA）

## Performance Debt

- Journey 365d 全量 Compose 渲染风险（当前按 30 天聚合；未做 lazy 优化）→ ERA 16 处理
- EchoLifeField 帧渲染依赖 draw-phase 状态读取（当前可用；未做 profile）

## Release Integrity

- ✅ 版本单一事实源 v0.9.0（version_source/README/pyproject/versionName/manifest 一致，CI 断言）
- ✅ provenance/hashes/manifest 同快照生成流程（scripts + CI）
- ⚠️ 正式 release bundle 需 clean checkout 重跑（脚本已注明；流程已 CI 化）

## Next Highest-value Task

ERA 13 应用层收口批次 1：JourneyViewModel + JourneyUiState + Journey 领域访问层（JourneyScreen 去编排）→ 批次 2：MeViewModel + DataAndSensingViewModel + 控制器拆分。
