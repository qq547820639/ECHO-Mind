"""激活码签发与兑换（v0.6.1）。

生产级激活凭证模型，取代「User.external_ref 隐式承担激活码」的旧语义：

- 数据库只存 SHA-256 哈希（code_hash），明文码仅在签发响应中出现一次；
- 全局唯一哈希 → 两个 tenant 出现相同业务 external_ref 不再互相歧义；
- 一次性消费：并发兑换同一码时原子 UPDATE ... WHERE used_at IS NULL 保证只成功一次；
- TTL：expires_at 过期即拒绝；
- 防爆破：attempt_count/max_attempts + IP/device/code 维度 rate limit（[activation_attempts] 表）；
- 失败/成功均有最小必要审计（失败写入 [ActivationAttempt]，成功写审计链 onboarding.verify）；
- 响应统一 404/403 文案，不泄露 tenant/user 是否存在。

所有函数不 commit（由路由层统一 commit），便于测试回滚。
"""
from __future__ import annotations

import hashlib
import secrets
from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import ActivationAttempt, ActivationCode, User
from app.services.audit import append_audit

#: 明文码默认长度（不含分隔符）。
CODE_RAW_LENGTH = 16
#: 明文码字符集（去除易混淆字符 0/O/1/I/l）。
CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
#: rate limit 回看窗口。
RATE_LIMIT_WINDOW = timedelta(minutes=15)
#: 同一 code_hash 窗口内最大失败次数。
MAX_FAILURES_PER_CODE = 10
#: 同一 IP 窗口内最大失败次数。
MAX_FAILURES_PER_IP = 20
#: 同一 device 窗口内最大失败次数。
MAX_FAILURES_PER_DEVICE = 20


def _aware(value: datetime | None) -> datetime | None:
    """SQLite 返回 naive datetime；统一按 UTC 解释后再比较。"""
    if value is None:
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def hash_code(code: str) -> str:
    """SHA-256(code)（加固定 pepper 防彩虹表；pepper 来自部署配置，非明文码本身）。"""
    settings = get_settings()
    digest = hashlib.sha256(f"{settings.bootstrap_key}:activation:{code}".encode("utf-8")).hexdigest()
    return digest


def generate_raw_code() -> str:
    """生成 `XXXX-XXXX-XXXX-XXXX` 格式明文码（16 位，安全随机）。"""
    raw = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_RAW_LENGTH))
    return "-".join(raw[i : i + 4] for i in range(0, CODE_RAW_LENGTH, 4))


def issue_code(
    db: Session,
    *,
    tenant_id: str,
    created_by: str,
    user_id: str | None = None,
    ttl_seconds: int | None = None,
    max_attempts: int = 5,
) -> tuple[ActivationCode, str]:
    """签发一枚激活码（返回 (记录, 明文码)）。

    明文码只在此处返回一次；数据库只存 code_hash。
    ttl_seconds 缺省用 settings.activation_code_ttl_seconds。
    """
    settings = get_settings()
    raw = generate_raw_code()
    code = ActivationCode(
        tenant_id=tenant_id,
        user_id=user_id,
        code_hash=hash_code(raw),
        created_by=created_by,
        expires_at=datetime.now(UTC) + timedelta(
            seconds=ttl_seconds if ttl_seconds is not None else settings.activation_code_ttl_seconds
        ),
        max_attempts=max_attempts,
    )
    db.add(code)
    db.flush()
    return code, raw


def _count_recent_failures(db: Session, *, code_hash: str | None, actor_ip: str | None, device_id: str | None) -> dict[str, int]:
    """窗口内失败尝试数（按维度独立计数）。

    v0.6.2 修复：原实现返回三个维度中的最大值，再与 ``max(limits)`` 比较，
    导致 per-code 限额（10）被抬到 20、维度阈值整体失效。现返回
    ``{"code": …, "ip": …, "device": …}``，由调用方按各自阈值独立判定
    （fail-closed：任一维度超限即拒绝）。
    """
    cutoff = datetime.now(UTC) - RATE_LIMIT_WINDOW
    counts = {"code": 0, "ip": 0, "device": 0}
    if code_hash:
        counts["code"] = db.scalar(
            select(func.count()).select_from(ActivationAttempt).where(
                ActivationAttempt.code_hash == code_hash,
                ActivationAttempt.result == "failure",
                ActivationAttempt.attempted_at >= cutoff,
            )
        ) or 0
    if actor_ip:
        counts["ip"] = db.scalar(
            select(func.count()).select_from(ActivationAttempt).where(
                ActivationAttempt.actor_ip == actor_ip,
                ActivationAttempt.result == "failure",
                ActivationAttempt.attempted_at >= cutoff,
            )
        ) or 0
    if device_id:
        counts["device"] = db.scalar(
            select(func.count()).select_from(ActivationAttempt).where(
                ActivationAttempt.device_id == device_id,
                ActivationAttempt.result == "failure",
                ActivationAttempt.attempted_at >= cutoff,
            )
        ) or 0
    return counts


def _record_attempt(
    db: Session,
    *,
    tenant_id: str | None,
    code_hash: str,
    actor_ip: str | None,
    device_id: str | None,
    result: str,
) -> None:
    db.add(ActivationAttempt(
        tenant_id=tenant_id,
        code_hash=code_hash,
        actor_ip=actor_ip,
        device_id=device_id,
        result=result,
    ))
    db.flush()


def _reject(db: Session, *, code_hash: str, actor_ip: str | None, device_id: str | None,
            tenant_id: str | None, reason: str) -> None:
    """统一失败路径：写尝试记录 + 递增 attempt_count（fail-closed，不泄露内部细节）。"""
    _record_attempt(db, tenant_id=tenant_id, code_hash=code_hash, actor_ip=actor_ip,
                    device_id=device_id, result="failure")
    if tenant_id is not None:
        db.execute(
            update(ActivationCode)
            .where(ActivationCode.code_hash == code_hash)
            .values(attempt_count=ActivationCode.attempt_count + 1)
        )
        db.flush()


def redeem_code(
    db: Session,
    *,
    code: str,
    actor_ip: str | None,
    device_id: str | None,
) -> tuple[ActivationCode | None, str | None]:
    """兑换激活码（原子、幂等消费）。

    返回 (row, reason)：
    - (row, None)：兑换成功（used_at 已写、已 flush）
    - (None, "not_found")：码不存在（调用方可回退 legacy 路径；统一 404）
    - (None, reason)：命中但被拒（revoked / expired / replay / rate_limited / user_not_active），
      调用方统一 403 文案，不得泄露内部细节。
    并发：UPDATE ... WHERE used_at IS NULL AND revoked_at IS NULL → 行级原子，
    只有一个并发事务能把 used_at 从 NULL 变为非 NULL。
    """
    settings = get_settings()
    now = datetime.now(UTC)
    code_hash = hash_code(code)

    row = db.scalar(select(ActivationCode).where(ActivationCode.code_hash == code_hash))
    if row is None:
        # 未知码：不写 attempt（避免为任意猜测码创建索引热点），只写审计失败行
        _record_attempt(db, tenant_id=None, code_hash=code_hash, actor_ip=actor_ip,
                        device_id=device_id, result="failure")
        return None, "not_found"

    # rate limit（code / IP / device 三维，各自独立阈值，fail-closed：任一超限即拒绝）
    recent = _count_recent_failures(db, code_hash=code_hash, actor_ip=actor_ip, device_id=device_id)
    rate_limited = (
        recent["code"] >= MAX_FAILURES_PER_CODE
        or recent["ip"] >= MAX_FAILURES_PER_IP
        or recent["device"] >= MAX_FAILURES_PER_DEVICE
    )
    if rate_limited or (row.attempt_count or 0) >= (row.max_attempts or 5):
        _reject(db, code_hash=code_hash, actor_ip=actor_ip, device_id=device_id,
                tenant_id=row.tenant_id, reason="rate_limited")
        return None, "rate_limited"
    if row.revoked_at is not None:
        _reject(db, code_hash=code_hash, actor_ip=actor_ip, device_id=device_id,
                tenant_id=row.tenant_id, reason="revoked")
        return None, "revoked"
    if _aware(row.expires_at) is not None and _aware(row.expires_at) <= now:
        _reject(db, code_hash=code_hash, actor_ip=actor_ip, device_id=device_id,
                tenant_id=row.tenant_id, reason="expired")
        return None, "expired"
    if row.used_at is not None:
        _reject(db, code_hash=code_hash, actor_ip=actor_ip, device_id=device_id,
                tenant_id=row.tenant_id, reason="replay")
        return None, "replay"

    # 绑定用户状态检查（restricted / withdrawal_pending 拒绝；不泄露内部细节）
    if row.user_id is not None:
        user = db.get(User, row.user_id)
        if user is None or user.status != "active":
            _reject(db, code_hash=code_hash, actor_ip=actor_ip, device_id=device_id,
                    tenant_id=row.tenant_id, reason="user_not_active")
            return None, "user_not_active"

    # 原子消费：只有 used_at 仍为 NULL 的那一笔能成功（并发安全，PG/SQLite 均行级原子）
    result = db.execute(
        update(ActivationCode)
        .where(
            ActivationCode.code_hash == code_hash,
            ActivationCode.used_at.is_(None),
            ActivationCode.revoked_at.is_(None),
        )
        .values(used_at=now, attempt_count=ActivationCode.attempt_count + 1)
        .returning(ActivationCode.id)
    )
    consumed_id = result.scalar_one_or_none()
    if consumed_id is None:
        # 并发竞争失败：按 replay 处理
        _reject(db, code_hash=code_hash, actor_ip=actor_ip, device_id=device_id,
                tenant_id=row.tenant_id, reason="replay")
        return None, "replay"
    db.flush()
    _record_attempt(db, tenant_id=row.tenant_id, code_hash=code_hash, actor_ip=actor_ip,
                    device_id=device_id, result="success")
    # 兑换成功后审计（success path）
    append_audit(
        db,
        tenant_id=row.tenant_id,
        actor_type="user",
        actor_id=row.user_id or "unknown",
        action="onboarding.verify",
        object_type="activation_code",
        object_id=row.id,
        metadata={"restricted": False},
    )
    return row, None
