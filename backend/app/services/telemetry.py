"""隐私安全的结构化操作遥测（v0.6.1，P2-16）。

原则：
- 只记录非敏感操作字段（request_id / method / path / status / duration_ms /
  少量分类计数）；
- **禁止记录**：原始传感数据、access token、journal/free text、
  明文敏感 consent 内容、加密前心理数据；
- 使用 stdlib logging（JSON-ish 单行结构），不引入额外依赖；
- 保留 request-id 全链（中间件把 X-Request-ID 写回响应头）。
"""
from __future__ import annotations

import json
import logging
import threading
from typing import Any

logger = logging.getLogger("echo_mind.telemetry")

#: 遥测单行日志的稳定字段顺序（便于日志平台解析）。
_FIELD_ORDER = ("ts", "level", "event", "request_id", "method", "path", "status", "duration_ms")


def _emit(event: str, **fields: Any) -> None:
    record: dict[str, object] = {"ts": _now_iso(), "level": "info", "event": event}
    for key in _FIELD_ORDER:
        if key in fields and fields[key] is not None:
            record[key] = fields[key]
    for key, value in fields.items():
        if key not in record:
            record[key] = value
    logger.info(json.dumps(record, ensure_ascii=False, default=str))


def _now_iso() -> str:
    from datetime import UTC, datetime

    return datetime.now(UTC).isoformat(timespec="milliseconds")


def log_request(*, request_id: str, method: str, path: str, status: int, duration_ms: int) -> None:
    """每请求遥测：method/path/status/duration（不记录任何 body 内容）。"""
    _emit("http.request", request_id=request_id, method=method, path=path, status=status, duration_ms=duration_ms)


def log_sync_outcome(*, request_id: str, event_type: str, outcome: str, retry_after: int | None = None) -> None:
    """端侧同步结果上报（客户端 SyncWorker 结构化遥测的事件分类；后端消费/代理用）。"""
    _emit("sync.outcome", request_id=request_id, event_type=event_type, outcome=outcome, retry_after=retry_after)


def log_feature_ingest_reject(*, request_id: str, category: str, reason: str) -> None:
    """feature ingest 拒绝原因分类（412 consent / 422 payload / flag off 等），
    不记录特征内容本身。"""
    _emit("feature.ingest_reject", request_id=request_id, category=category, reason=reason)


#: 并发安全的计数器（分类遥测用；跨进程精确计数由日志聚合平台负责）。
_counters: dict[str, int] = {}
_counters_lock = threading.Lock()


def count_event(category: str) -> None:
    with _counters_lock:
        _counters[category] = _counters.get(category, 0) + 1


def snapshot_counts() -> dict[str, int]:
    with _counters_lock:
        return dict(_counters)
