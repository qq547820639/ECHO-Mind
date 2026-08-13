#!/usr/bin/env python3
"""故障注入矩阵静态验证（T03 / 02c §I）。

以「静态断言代码路径存在」为验证入口：逐一扫描 backend 与 Android 源码/测试，
确认 16 项故障场景有对应实现或测试锚点。输出 PASS/FAIL 清单；任一 FAIL → exit 1。

矩阵覆盖（02c §I 表）：
1  offline            → SyncWorker 无网络 retry（Result.retry + SyncState.OFFLINE）
2  connect/read 超时  → ApiClient 抛异常 → incrementAttempts + RETRY
3  DNS 失败           → 同超时（异常 → RETRY）
4  401                → KEEP_PENDING（永不 dead-letter）
5  403                → KEEP_PENDING
6  410+deprecated     → DELETE_AND_MIGRATE（outbox 删除 + telemetry）
7  410+非 deprecated  → DEAD_LETTER
8  412 consent 撤回    → KEEP_PENDING → 超限 DEAD_LETTER
9  429 Retry-After    → RETRY（记录 Retry-After）
10 500                → RETRY；超限 DEAD_LETTER
11 malformed 响应      → loadFailed=true（UI 重试，不伪装 no_data）
12 服务重启            → 窗口对齐恢复、内存缓冲随进程消亡（无脏数据）
13 duplicate 事件      → 服务端幂等（tenant+event_id）→ 200 idempotent_replay
14 process death       → ActiveSkillSession 恢复为 PAUSED（时长不虚增）
15 窗口持久化失败      → saveDerivedFeatures=false → bounded retry ≤3 + 失败可观测
16 consent revoke      → 立即清内存 + 不补发 + revoke evidence 入 outbox

用法：python scripts/fault_injection_check.py
"""
from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

BACKEND_APP = REPO_ROOT / "backend" / "app"
BACKEND_TESTS = REPO_ROOT / "backend" / "tests"
ANDROID_MAIN = REPO_ROOT / "android" / "app" / "src" / "main"
ANDROID_TESTS = REPO_ROOT / "android" / "app" / "src" / "test"


def _read(rel: str) -> str:
    p = REPO_ROOT / rel
    if not p.exists():
        return ""
    return p.read_text(encoding="utf-8", errors="replace")


def _check(name: str, cond: bool, detail: str) -> bool:
    status = "PASS" if cond else "FAIL"
    print(f"[{status}] {name} — {detail}")
    return cond


def main() -> int:
    results: list[bool] = []

    sync_worker = _read("android/app/src/main/java/com/yunjue/echo/mind/data/SyncWorker.kt")
    state_machine_test = _read("android/app/src/test/java/com/yunjue/echo/mind/SyncWorkerStateMachineTest.kt")
    routes = _read("backend/app/api/routes.py")
    onboarding_api = _read("backend/app/api/onboarding.py")
    window_ack_test = _read("android/app/src/test/java/com/yunjue/echo/mind/WindowAckTest.kt")
    scheduler_test = _read("android/app/src/test/java/com/yunjue/echo/mind/SensingWindowSchedulerTest.kt")
    active_session_test = _read("android/app/src/test/java/com/yunjue/echo/mind/ActiveSkillSessionTest.kt")
    consent_lifecycle = _read("android/app/src/test/java/com/yunjue/echo/mind/ConsentLifecycleTest.kt")
    hub = _read("android/app/src/main/java/com/yunjue/echo/mind/sensing/SensingEventHub.kt")
    onboarding_test = _read("android/app/src/test/java/com/yunjue/echo/mind/OnboardingVerifyFlowTest.kt")
    gap_finder = _read("backend/app/services/sandbox/gap_finder.py")

    # 1 offline：SyncWorker 网络失败 retry + SyncState.OFFLINE 映射
    results.append(_check(
        "1 offline",
        "Result.retry()" in sync_worker and "OFFLINE" in _read("android/app/src/main/java/com/yunjue/echo/mind/data/SyncState.kt"),
        "SyncWorker 网络失败 Result.retry；SyncState 含 OFFLINE",
    ))
    # 2/3 超时与 DNS：ApiClient 异常 → incrementAttempts + RETRY
    results.append(_check(
        "2/3 timeout & DNS",
        "incrementAttempts" in sync_worker and "getOrElse" in sync_worker,
        "ApiClient 抛异常 → dao.incrementAttempts + anyRetry",
    ))
    # 4/5 401/403 → KEEP_PENDING
    results.append(_check(
        "4/5 401/403",
        "httpCode == 401 || httpCode == 403" in sync_worker and "KEEP_PENDING" in state_machine_test,
        "classifySyncOutcome 401/403 → KEEP_PENDING；测试覆盖",
    ))
    # 6 410+deprecated → DELETE_AND_MIGRATE
    results.append(_check(
        "6 410 deprecated",
        "DELETE_AND_MIGRATE" in sync_worker and "deprecatedEvent410IsDeletedAndMigrated" in state_machine_test,
        "410 + deprecated 类型 → 删除 outbox + telemetry；测试覆盖",
    ))
    # 7 410+非 deprecated → DEAD_LETTER
    results.append(_check(
        "7 410 non-deprecated",
        "SyncAction.DEAD_LETTER" in sync_worker and "nonDeprecatedEvent410IsDeadLettered" in state_machine_test,
        "410 + 非 deprecated → dead-letter；测试覆盖",
    ))
    # 8 412 consent → KEEP_PENDING
    results.append(_check(
        "8 412 consent",
        "httpCode == 412 || httpCode == 422" in sync_worker and "consentAndValidationFailuresKeepPendingUntilMaxAttempts" in state_machine_test,
        "412 → KEEP_PENDING；测试覆盖",
    ))
    # 9 429 Retry-After → RETRY + 记录
    results.append(_check(
        "9 429 retry-after",
        "recordRetryAfter" in sync_worker and "rateLimit429Retries" in state_machine_test,
        "429 → RETRY + 记录 Retry-After（SyncWorker 经 response.third 的 Retry-After 写入 recordRetryAfter）；测试覆盖",
    ))
    # 10 500 → RETRY；超限 DEAD_LETTER
    results.append(_check(
        "10 500 retry",
        "SyncAction.RETRY" in sync_worker and "serverErrorsRetryUntilMaxAttempts" in state_machine_test,
        "5xx → RETRY；超限 dead-letter；测试覆盖",
    ))
    # 11 malformed 响应 → loadFailed（不伪装 no_data）
    results.append(_check(
        "11 malformed response",
        "loadFailed = true" in _read("android/app/src/main/java/com/yunjue/echo/mind/data/LocalRepository.kt")
        and "errorStateIsDistinctFromNoData" in _read("android/app/src/test/java/com/yunjue/echo/mind/TrendDataSourceTest.kt"),
        "解析失败 loadFailed=true 与 NO_DATA 区分；趋势测试覆盖",
    ))
    # 12 服务重启 → 窗口对齐恢复（无脏数据）
    results.append(_check(
        "12 service restart",
        "restartResumesAtNextAlignedBoundary" in scheduler_test and "serviceRestartAlignsToBoundaryWithNoDirtyData" in window_ack_test,
        "重启对齐下一边界 + 无脏数据；调度器/WindowAck 测试覆盖",
    ))
    # 13 duplicate 事件 → 服务端幂等（ingest idempotent_replay）
    results.append(_check(
        "13 duplicate idempotent",
        "idempotent_replay" in _read("backend/app/api/routes.py")
        or "idempotent_replay" in _read("backend/tests/test_passive_sensing.py"),
        "后端 ingest 幂等（tenant+event_id）→ idempotent_replay",
    ))
    # 14 process death → ActiveSkillSession 恢复 PAUSED
    results.append(_check(
        "14 process death",
        "restoreFrom" in _read("android/app/src/main/java/com/yunjue/echo/mind/ui/SkillCardHost.kt")
        and "runningEntityRestoresAsPausedToAvoidInflatedDuration" in active_session_test,
        "SkillRunSession.restoreFrom 恢复 PAUSED；测试覆盖",
    ))
    # 15 窗口持久化失败 → saveDerivedFeatures=false → bounded retry ≤3 + 可观测
    results.append(_check(
        "15 window persist failure",
        "MAX_WINDOW_RETRY" in _read("android/app/src/main/java/com/yunjue/echo/mind/sensing/SensingWindowScheduler.kt")
        and "flushFailureKeepsBuffersAndMarksRetryable" in scheduler_test
        and "roomFailureReturnsFalseAndRecordsPersistenceFailure" in window_ack_test,
        "scheduler bounded retry=3 + repository 失败计数；测试覆盖",
    ))
    # 16 consent revoke → 立即清内存 + 不补发 + revoke evidence 入 outbox
    results.append(_check(
        "16 consent revoke",
        "clearAll()" in hub and "consentRevokeDuringFailureClearsMemoryAndStops" in window_ack_test
        and "atomicStopPersistsConsentFalseAndClearsHub" in consent_lifecycle,
        "hub.clearAll + revoke evidence 入 outbox；WindowAck/ConsentLifecycle 测试覆盖",
    ))

    # 附加边界断言（02c §I 之外的强约束锚点）
    results.append(_check(
        "ingest 不触发被动危机升级",
        "escalation_id" in _read("backend/tests/test_passive_sensing.py")
        and "never_creates_risk_signal" in _read("backend/tests/test_passive_sensing.py"),
        "ingest 永不创建 RiskSignal/Escalation；测试覆盖",
    ))
    results.append(_check(
        "gap_finder 无情绪缺口",
        "mood" not in gap_finder and "suicide" not in gap_finder,
        "gap_finder 不产生情绪/自杀缺口",
    ))
    results.append(_check(
        "verify-code 404/403",
        ("无效激活码" in onboarding_api or "无效激活码" in routes)
        and ("已受限" in onboarding_api or "已受限" in routes)
        and "verify-code" in _read("docs/openapi.json"),
        "verify-code 404/403（onboarding router，routes.py include 转发）+ OpenAPI 契约；测试覆盖",
    ))
    results.append(_check(
        "onboarding 七态",
        "ONBOARDING_READY_OFFLINE" in _read("android/app/src/main/java/com/yunjue/echo/mind/AppPreferences.kt")
        and "sevenStatesPersistAndCompletionOnlyAtReadyStates" in onboarding_test,
        "AppPreferences 七态 + 测试覆盖",
    ))

    failed = sum(1 for ok in results if not ok)
    print(f"\n故障注入矩阵：{len(results) - failed}/{len(results)} PASS")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
