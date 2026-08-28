#!/usr/bin/env bash
# 发布元数据「门禁证据」环境变量（P0-4：每个门禁结论必须可复现）。
#
# 用法（在仓库根执行）：
#   set -a && source scripts/release_evidence.env.sh && set +a
#   ./scripts/package_release.sh --skip-preflight
#
# 纪律：
#   1. 每个 *_EVIDENCE 必须写清「命令 + 环境 + 时间 + 结果」，不得写抽象形容词；
#   2. 未真正执行的门禁**不要**提供对应变量（update_release_metadata 会如实留空）；
#   3. 每次发布运行前更新计数与日期，否则证据与事实不符——那比没有证据更糟。
#
# 2026-08-28 基线（本机实测；Android 与 PostgreSQL 门禁需 CI/真机环境）。
set -a

BUILDER_ENVIRONMENT="local-dev-verified"
BUILDER_PYTHON_VERSION="$(python3 -V 2>/dev/null | awk '{print $2}')"

# —— 已实测 ——
BACKEND_TESTS_EVIDENCE="backend/.venv/bin/python -m pytest -q（Python 3.12.13 / SQLite in-memory）→ 1131 passed + 1 skipped，2026-08-28"
ALEMBIC_ROUNDTRIP_RESULT="passed_sqlite_local_drill_20260828"
ALEMBIC_ROUNDTRIP_EVIDENCE="DATABASE_URL=sqlite:///<tmp> alembic upgrade head → downgrade base → upgrade head：0 error（2026-08-28 本地实测；PostgreSQL 版由 CI alembic-postgres job 执行）"
CONTRACT_DRIFT_CHECK_RESULT="passed_local_20260828"
CONTRACT_DRIFT_CHECK_EVIDENCE="python3 scripts/contract_drift_check.py（61 路径 PASS）+ scripts/fault_injection_check.py（18/18 PASS），2026-08-28 本地实测，与 CI contract-drift job 同源"
STATIC_CHECKS_EVIDENCE="ruff check app tests（0）+ mypy app strict（0 issues / 69 files）+ claim_scan + check_dynamic_code + safety_eval + validate_content_packs（4 包）+ verify_workflow_pins（5 workflow / 66 uses 全 SHA）"
PORTRAIT_GOLDEN_EVIDENCE="backend/scripts/generate_portrait_golden.py --check：5 cases 规范源与生成物零漂移，2026-08-28"
REPO_BLOAT_EVIDENCE="scripts/check_repo_bloat.py：受控文件无超限、无生成物二进制；.git 历史债告警按 docs/operations/REPO_HISTORY_SLIM_RUNBOOK.md 排期"

# —— 未执行（本机环境限制；权威结果以 CI 为准）——
ANDROID_GRADLE_BUILD_EVIDENCE="未执行：本工作区无 Android SDK（~/Library/Android/sdk 与 local.properties 指向的 sdk 均不存在）→ BLOCKED_ENV_ANDROID_SDK；权威门禁为 CI android-ci job"
ANDROID_INSTRUMENTATION_EVIDENCE="未执行：需真机/模拟器 + Android SDK → BLOCKED_ENV_ANDROID_SDK"

DELIVERY_BLOCKED_BY="BLOCKED_ENV_ANDROID_SDK|BLOCKED_ENV_DOCKER_POSTGRES|BLOCKED_EXTERNAL_BAND10_DEVICE|BLOCKED_EXTERNAL_XIAOMI_SDK|BLOCKED_EXTERNAL_PRODUCTION_SIGNING"

set +a
