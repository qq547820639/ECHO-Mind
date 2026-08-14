"""v0.6.1：ActivationCode 正式激活凭证模型回归测试。

覆盖（对应 hardening 需求五）：
- 两个 tenant 出现相同业务 external_ref（旧语义歧义）不再影响激活码
- 激活码 collision（hash 唯一）
- expired / revoked / replay
- concurrent redemption（并发消费同一码只成功一次）
- brute-force / rate-limit（IP / device / code 维度）
- restricted user
- 明文码只在签发响应出现一次；数据库只存哈希
- TTL / 一次性消费 / 防 replay / 不泄露租户存在性 / 最小安全审计
"""
import threading

from app.database import SessionLocal
from app.models import ActivationCode, User
from app.services.activation import generate_raw_code, hash_code, issue_code, redeem_code


def _seed_user(user_id: str, external_ref: str, *, status: str = "active", tenant_id: str = "t_demo") -> None:
    with SessionLocal() as db:
        db.add(User(id=user_id, tenant_id=tenant_id, external_ref=external_ref, status=status))
        db.commit()


def _issue(tenant_id: str = "t_demo", user_id: str | None = None, ttl_seconds: int | None = None,
           max_attempts: int = 5) -> tuple[str, str]:
    """签发并返回 (code_id, 明文码)。"""
    with SessionLocal() as db:
        code, raw = issue_code(db, tenant_id=tenant_id, created_by="test", user_id=user_id,
                               ttl_seconds=ttl_seconds, max_attempts=max_attempts)
        db.commit()
        return code.id, raw


def test_raw_code_format_and_hash_only_storage():
    raw = generate_raw_code()
    # 格式 XXXX-XXXX-XXXX-XXXX，无易混淆字符
    assert len(raw) == 19 and raw.count("-") == 3
    for ch in raw.replace("-", ""):
        assert ch in "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    with SessionLocal() as db:
        code, plain = issue_code(db, tenant_id="t_demo", created_by="test")
        db.commit()
        row = db.get(ActivationCode, code.id)
        assert row.code_hash == hash_code(plain)
        assert plain not in row.code_hash  # 明文不直接存
        # 数据库内不存在明文
        assert row.code_hash != plain


def test_two_tenants_same_external_ref_no_ambiguity():
    """两个租户出现相同业务 external_ref：激活码路径不受影响（hash 全局唯一）。"""
    _seed_user("u_t1", "SAME-REF-0001", tenant_id="t_one")
    _seed_user("u_t2", "SAME-REF-0001", tenant_id="t_two")
    id1, code1 = _issue(tenant_id="t_one", user_id="u_t1")
    id2, code2 = _issue(tenant_id="t_two", user_id="u_t2")
    assert id1 != id2 and code1 != code2
    # 各自可兑换（激活码 hash 天然隔离，互不歧义）
    with SessionLocal() as db:
        r1 = redeem_code(db, code=code1, actor_ip="1.1.1.1", device_id="d1")
        assert r1[0] is not None and r1[0].tenant_id == "t_one"
        db.commit()
        r2 = redeem_code(db, code=code2, actor_ip="2.2.2.2", device_id="d2")
        assert r2[0] is not None and r2[0].tenant_id == "t_two"
        db.commit()


def test_redeem_success_binds_and_consumes_once():
    _seed_user("u_act", "EXT-REF-0001")
    _, raw = _issue(user_id="u_act")
    with SessionLocal() as db:
        row = redeem_code(db, code=raw, actor_ip="1.2.3.4", device_id="dev-1")
        assert row[0] is not None
        db.commit()
        again = redeem_code(db, code=raw, actor_ip="1.2.3.4", device_id="dev-1")
        assert again[0] is None  # replay 拒绝
        db.commit()
        stored = db.scalar(
            __import__("sqlalchemy").select(ActivationCode).where(ActivationCode.code_hash == hash_code(raw))
        )
        assert stored.used_at is not None
        assert stored.user_id == "u_act"


def test_redeem_unknown_code_returns_none_uniform_404(client):
    """未知码：None（路由层统一 404 文案），不泄露租户/user 存在性。"""
    response = client.post("/v1/onboarding/verify-code", json={"code": "NO-SUCH-CODE-99"})
    assert response.status_code == 404
    assert "无效激活码" in response.json()["detail"]


def test_redeem_revoked_code_returns_none():
    from datetime import UTC, datetime

    _, raw = _issue()
    with SessionLocal() as db:
        row = db.scalar(__import__("sqlalchemy").select(ActivationCode).where(ActivationCode.code_hash == hash_code(raw)))
        row.revoked_at = datetime.now(UTC)
        db.commit()
        assert redeem_code(db, code=raw, actor_ip="9.9.9.9", device_id="d")[0] is None
        db.commit()


def test_redeem_expired_code_returns_none():
    from datetime import UTC, datetime, timedelta

    _, raw = _issue(ttl_seconds=60)
    with SessionLocal() as db:
        row = db.scalar(__import__("sqlalchemy").select(ActivationCode).where(ActivationCode.code_hash == hash_code(raw)))
        row.expires_at = datetime.now(UTC) - timedelta(seconds=1)
        db.commit()
        assert redeem_code(db, code=raw, actor_ip="9.9.9.9", device_id="d")[0] is None
        db.commit()


def test_redeem_expired_code_exact_boundary_rejected():
    """ERA 45 边缘契约：TTL 到期时间 == 现在（毫秒级边界）→ 拒绝（_is_expired 用 <=）。"""
    from datetime import UTC, datetime

    _, raw = _issue(ttl_seconds=60)
    with SessionLocal() as db:
        row = db.scalar(__import__("sqlalchemy").select(ActivationCode).where(ActivationCode.code_hash == hash_code(raw)))
        row.expires_at = datetime.now(UTC)
        db.commit()
        assert redeem_code(db, code=raw, actor_ip="9.9.9.9", device_id="d")[0] is None
        db.commit()


def test_redeem_restricted_user_returns_none():
    _seed_user("u_restricted", "EXT-RESTR-1", status="restricted")
    _, raw = _issue(user_id="u_restricted")
    with SessionLocal() as db:
        assert redeem_code(db, code=raw, actor_ip="1.1.1.1", device_id="d")[0] is None
        db.commit()


def test_redeem_withdrawn_user_returns_none():
    _seed_user("u_withdrawn", "EXT-WITHD-1", status="withdrawal_pending")
    _, raw = _issue(user_id="u_withdrawn")
    with SessionLocal() as db:
        assert redeem_code(db, code=raw, actor_ip="1.1.1.1", device_id="d")[0] is None
        db.commit()


def test_brute_force_attempt_limit_blocks():
    """同一码多次失败尝试递增 attempt_count，达到 max_attempts 后拒绝（防爆破）。"""
    from datetime import UTC, datetime

    _seed_user("u_bf", "EXT-BF-0001")
    _, raw = _issue(user_id="u_bf", max_attempts=3)
    # 制造同一码的失败：先吊销，再尝试兑换（revoked → 失败并递增 attempt_count）
    with SessionLocal() as db:
        row = db.scalar(__import__("sqlalchemy").select(ActivationCode).where(ActivationCode.code_hash == hash_code(raw)))
        row.revoked_at = datetime.now(UTC)
        db.commit()
    for _ in range(3):
        with SessionLocal() as db:
            assert redeem_code(db, code=raw, actor_ip="5.5.5.5", device_id="dev-bf")[0] is None
            db.commit()
    with SessionLocal() as db:
        row = db.scalar(__import__("sqlalchemy").select(ActivationCode).where(ActivationCode.code_hash == hash_code(raw)))
        assert row.attempt_count >= 3
        # 即使解除吊销，attempt_count 已达 max_attempts → 仍拒绝（限流不因状态变化解除）
        row.revoked_at = None
        db.commit()
        assert redeem_code(db, code=raw, actor_ip="5.5.5.5", device_id="dev-bf")[0] is None
        db.commit()


def test_rate_limit_per_ip_blocks_repeated_failures():
    """同一 IP 窗口内失败超过阈值后拒绝（即使换码）。"""
    _seed_user("u_rl", "EXT-RL-0001")
    _, raw = _issue(user_id="u_rl", max_attempts=20)
    from app.services.activation import MAX_FAILURES_PER_IP

    wrong = "WRONG-CODE-0001"
    for _ in range(MAX_FAILURES_PER_IP + 2):
        with SessionLocal() as db:
            redeem_code(db, code=wrong, actor_ip="6.6.6.6", device_id="dev-x")
            db.commit()
    # 正确码也因 IP 维度 rate limit 被拒
    with SessionLocal() as db:
        assert redeem_code(db, code=raw, actor_ip="6.6.6.6", device_id="dev-x")[0] is None
        db.commit()


def test_rate_limit_per_code_threshold_applies_at_code_limit():
    """同一 code 窗口内失败达到 MAX_FAILURES_PER_CODE（10）即拒绝（fail-closed）。

    v0.6.2 修复回归：原实现取三维最大计数再与 max(limits)=20 比较，per-code
    限额被抬到 20；修复后每维度独立阈值，第 11 次失败（换新 IP/device）命中
    code 维度即被限流，不得再返回 revoked。
    """
    from datetime import UTC, datetime

    from sqlalchemy import select

    from app.services.activation import MAX_FAILURES_PER_CODE

    _seed_user("u_code_rl", "EXT-CODE-RL-1")
    _, raw = _issue(user_id="u_code_rl", max_attempts=50)
    # 吊销该码制造失败（沿用 test_brute_force_attempt_limit_blocks 模式）。
    with SessionLocal() as db:
        row = db.scalar(select(ActivationCode).where(ActivationCode.code_hash == hash_code(raw)))
        row.revoked_at = datetime.now(UTC)
        db.commit()
    # 前 MAX_FAILURES_PER_CODE 次失败：尚未触及 code 阈值，返回 revoked。
    for i in range(MAX_FAILURES_PER_CODE):
        with SessionLocal() as db:
            row, reason = redeem_code(db, code=raw, actor_ip=f"30.0.0.{i}", device_id=f"dev-c{i}")
            assert row is None and reason == "revoked"
            db.commit()
    # 第 MAX_FAILURES_PER_CODE + 1 次（全新 IP/device）：code 维度计数=10 → rate_limited。
    with SessionLocal() as db:
        row, reason = redeem_code(db, code=raw, actor_ip="30.9.9.9", device_id="dev-new")
        assert row is None and reason == "rate_limited"
        db.commit()


def test_rate_limit_ip_dimension_does_not_block_other_ips():
    """IP 维度限流只作用于该 IP；换新 IP 尝试未失败的码不被误伤（维度独立）。"""
    from app.services.activation import MAX_FAILURES_PER_IP

    _seed_user("u_ip_rl", "EXT-IP-RL-1")
    _, raw = _issue(user_id="u_ip_rl", max_attempts=50)
    # 同一 IP 用错误码制造失败，超过 IP 维度阈值（错误码不递增 attempt_count）。
    wrong = "WRONG-CODE-IPRL-1"
    for _ in range(MAX_FAILURES_PER_IP + 2):
        with SessionLocal() as db:
            redeem_code(db, code=wrong, actor_ip="40.40.40.40", device_id="dev-ip")
            db.commit()
    # 换新 IP + 新 device：code/IP/device 三维计数均为 0 → 兑换成功。
    with SessionLocal() as db:
        row, reason = redeem_code(db, code=raw, actor_ip="41.41.41.41", device_id="dev-new")
        assert row is not None, reason
        db.commit()


def test_concurrent_redemption_only_one_wins():
    """并发消费同一码：只有一个线程能成功（原子 UPDATE ... WHERE used_at IS NULL）。

    并发需要真实多连接：使用文件型 SQLite（每线程独立连接 + busy timeout），
    覆盖 SQLite 测试环境的并发语义；PG job 复用同一 UPDATE 语句验证。
    """
    import tempfile

    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    from app.database import Base

    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    engine = create_engine(
        f"sqlite:///{tmp.name}",
        connect_args={"check_same_thread": False, "timeout": 30},
        pool_pre_ping=True,
    )
    Base.metadata.create_all(bind=engine)
    factory = sessionmaker(bind=engine, autoflush=False, autocommit=False, expire_on_commit=False)

    try:
        with factory() as db:
            db.add(User(id="u_conc", tenant_id="t_demo", external_ref="EXT-CONC-1", status="active"))
            code, raw = issue_code(db, tenant_id="t_demo", created_by="test", user_id="u_conc")
            db.commit()

        results: list[bool] = []
        lock = threading.Lock()

        def try_redeem() -> None:
            try:
                with factory() as db:
                    ok = redeem_code(db, code=raw, actor_ip="7.7.7.7",
                                     device_id=f"dev-{threading.get_ident()}")[0] is not None
                    db.commit()
                with lock:
                    results.append(ok)
            except Exception as exc:  # noqa: BLE001 记录并发异常以便断言定位
                with lock:
                    results.append(False)
                    results.append(f"error:{type(exc).__name__}")

        threads = [threading.Thread(target=try_redeem) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert results.count(True) == 1, f"并发消费同一码应只有一次成功: {results}"
        with factory() as db:
            stored = db.scalar(
                __import__("sqlalchemy").select(ActivationCode).where(ActivationCode.code_hash == hash_code(raw))
            )
            assert stored.used_at is not None
    finally:
        import os

        os.unlink(tmp.name)


def test_issue_revoke_endpoints_admin_only(client, admin_headers):
    """admin 签发/吊销/列表端点（不含明文泄漏）。"""
    # admin 签发
    resp = client.post(
        "/v1/admin/activation-codes",
        json={"user_id": None, "ttl_seconds": 3600, "max_attempts": 5},
        headers=admin_headers,
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] and len(body["code"]) == 19
    assert "code_hash" not in body  # 不暴露 hash

    # 列表不含明文
    listed = client.get("/v1/admin/activation-codes", headers=admin_headers)
    assert listed.status_code == 200
    assert all("code" not in item for item in listed.json())

    # 吊销后兑换失败
    revoked = client.post(f"/v1/admin/activation-codes/{body['id']}/revoke", headers=admin_headers)
    assert revoked.status_code == 200
    with SessionLocal() as db:
        assert redeem_code(db, code=body["code"], actor_ip="8.8.8.8", device_id="dev")[0] is None
        db.commit()

    # 非 admin 无权限
    user_token = client.post(
        "/v1/onboarding/verify-code",
        json={"code": "LEGACY-CODE-0001"},
    )
    if user_token.status_code == 200:
        headers = {"Authorization": f"Bearer {user_token.json()['access_token']}"}
        denied = client.post("/v1/admin/activation-codes", json={}, headers=headers)
        assert denied.status_code == 403


def test_admin_issue_binds_user_then_verify_code_exchange(client, admin_headers):
    """完整链路：admin 签发（预绑定用户）→ 用户用明文码兑换 → 拿到 token。"""
    _seed_user("u_full", "EXT-FULL-1")
    issued = client.post(
        "/v1/admin/activation-codes",
        json={"user_id": "u_full"},
        headers=admin_headers,
    )
    assert issued.status_code == 200
    plain = issued.json()["code"]

    exchange = client.post("/v1/onboarding/verify-code", json={"code": plain})
    assert exchange.status_code == 200
    body = exchange.json()
    assert body["user_id"] == "u_full"
    assert body["access_token"]
    assert body["restricted"] is False

    # replay：同码再次兑换被拒
    replay = client.post("/v1/onboarding/verify-code", json={"code": plain})
    assert replay.status_code == 403
