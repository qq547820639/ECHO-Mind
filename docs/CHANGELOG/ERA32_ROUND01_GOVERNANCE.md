# ERA 32 R01 — Governance Simplification（治理减法，Batch A 收官）

> 2026-08-15。ERA 32 立即执行批次（Batch A）完成轮。原则：治理减法 + 架构/QA 冻结 + 真实数字。

## 1. Source Reality Generator 多模块修复

`scripts/generate_source_reality.py` 重写（ERA 32 多模块自动发现版）：

- Gradle modules 从 `android/settings.gradle.kts` 自动发现（11 个），**删除手写 MODULE_JAVA_ROOTS**；
- 每 module 自动扫描 `src/main/java` 与 `src/main/kotlin`；
- Manifest Components 在全部 production module 的 AndroidManifest.xml 中解析，源类存在性在全部
  production 源码根判定——`sensing.NotificationCollector`（:feature:observation）等 feature 类
  **不再误报 Missing**（修复前 `.main.sensing.NotificationCollector ❌ 缺源类`）；
- `:feature:qa` 单列统计：Production Kotlin 165 / Test Kotlin 122 / QA Kotlin 36（QA 不属于 Production Runtime）。

`scripts/generate_dependency_graph.py` 同步改为复用同一自动发现（SRC_ROOTS 手写清单删除）；
重生成 ANDROID_DEPENDENCY_GRAPH.md **零漂移**（12 domains / 46 edges 不变），证明行为等价。

## 2. 状态文档体系减法

- 新建 `docs/STATUS.md`：唯一状态锚点（Development HEAD / Last Verified Release / Build Status 自动生成段 /
  Known Product Risks / Known Engineering Risks / Next Product Slice / Governance 冻结纪律）。
- `docs/release/RELEASE_BASELINE.md` → `docs/RELEASE_BASELINE.md`（发布锚点，纪律第 2 条指向 STATUS）。
- 历史轮次细节全部移入 `docs/CHANGELOG/`：IMPLEMENTATION_STATUS_ERA31 / DEVELOPMENT_STATUS_ERA31 /
  RELEASE_READINESS_v0.9.0 / 31_Phase9_Security_Supply_Chain / DOCUMENT_AUTHORITY_legacy /
  README_AUTHORITY_legacy（git mv 保留历史）。
- 旧文档权威两份合并进 `docs/STATUS.md` §7；`docs/release/` 目录移除。

## 3. 状态数字自动生成

- 新增 `scripts/refresh_status_numbers.py`：从实测产物生成 `docs/STATUS.md` §3——
  Android testDebugUnitTest 的 JUnit XML（按 module，含陈旧产物过滤：类名必须仍被 test 源码声明，
  兼容一个文件多个测试类）/ backend `.pytest_report/junit.xml`（兼容 testsuites 包裹）/
  Kotlin·Python 文件数（复用 source reality 自动发现）。产物缺失时如实标注「未执行」，禁止伪造。
- README 删除全部手写测试计数（此前 1008/1078 已漂移），改为指向 `docs/STATUS.md`；
  backend `test_README_pytest_count_matches_collection`（手写数字强制器）改为
  `test_README_delegates_counts_to_status`（防手写数字回流的回归锁）。

## 4. 本轮实测数字（全部实测产物）

- Android 单测：**1031 全绿**（app 871 / feature:intelligence 23 / feature:presence 25 / feature:qa 112）
  ——`--rerun-tasks` 全量重跑实测；历史文档「1032」为陈旧 build 产物放大（LocalPortraitDigestTest.kt
  双类文件 + DebugJourneyTest 同行 @Test 造成的统计口径差），自动生成后数字即事实。
- backend：**1077 passed + 1 skipped**（全量实测）；ruff/mypy 未变（无 backend 生产代码改动）。
- SOURCE_MANIFEST 1072 重生成，verify PASS。

## 5. 审计结论

- **:feature:qa ↔ production 重复**：三项 mirror 全部闭环（QaPortraitMirror=必要镜像+黄金门；
  QaHeadlineEngine 文案重复已消除（learningPhaseHeadline core:model 单点）；QaAskEcho=引擎薄适配器），
  本轮无新增重复。`qa/reports/QA_MIRROR_AUDIT.md` §7 复核。
- **PersonalAnswerEngine 复杂度**：626 行 / 15 族，意图层已存在且严格表锁死，暂不拆分；
  拆分触发条件写入 `qa/reports/PERSONAL_ANSWER_ENGINE_AUDIT.md` §4。
- 无新 feature、无新 module、无新 ADR、无新 synthetic QA（冻结纪律生效）。

## 6. 下一轮

Batch B（真机）：本环境无真机，保持协议（DEVICE_CHECKLIST / collect_wallpaper_metrics.sh / DOGFOOD_PROTOCOL）
待部署侧执行；下一轮转入 Batch C 预检（Core Set 26 条答案复核 / Context / Correction reuse / Provider fallback）
或按真实数据回流顺序执行。

## 7. 纪律备注（本轮教训）

- `scripts/build_source_archive.py` 默认输出到 `releases/`（v0.10.0 发布物所在）——dev head 验证时
  会把发布基线归档覆盖成 dev 内容。本轮已从 release.zip 恢复并校验 hash 一致（test_release_set 16/16）。
  **dev head 环境不得直接跑该脚本落盘 releases/；归档构建属于 clean-checkout Release Closure。**

