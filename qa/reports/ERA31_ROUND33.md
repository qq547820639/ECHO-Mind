# ERA 31 Round 33 报告（Release Integrity 复核：全量本地预检 + Baseline 文档对齐）

> 日期：2026-08-15。

## 背景

R11–R32 的 22 轮打磨改动了 Android 主源/测试/QA 工件与 backend 文案镜像。按
「release integrity intact」纪律，做一次**全量本地预检复核**——不带旧 proof 自证
新 HEAD 仍然健康，同时把 Release Baseline 文档与真实进度对齐。

## 复核结果

**`scripts/release_preflight.sh` LOCAL PREFLIGHT PASSED**：

- backend：pytest 1077 passed + 1 skipped、ruff 0、mypy strict 0、OpenAPI 导出
  零漂移（docs/openapi.json 与 v0.10.0 一致——backend 契约面未变）、Alembic
  roundtrip、内容包/宣称/动态代码/安全集、契约漂移、故障注入全 PASS、uv.lock
  零漂移；
- Android：testDebugUnitTest（1028）+ assembleDebug + lintDebug（全模块）PASS；
- 预检未产生任何文件漂移（git 只有本轮文档编辑）。

## 文档对齐（docs/release/RELEASE_BASELINE.md）

- 「当前 HEAD ≠ last release」段落过期（仍描述 v0.9.0 之前的 R1–R8）→ 改为
  「v0.10.0 后 R11–R32 已合入 main、尚未新一轮 Release Closure」；
- 纪律第 3 条：「下一次 Release Closure（BATCH 8）…不得带旧 v0.9.0 proof」
  → 去掉 BATCH 8 表述、旧 proof 改为 v0.10.0。

## 实测

- LOCAL PREFLIGHT PASSED（backend 全项 + Android 全项）；Android 1028 全绿。