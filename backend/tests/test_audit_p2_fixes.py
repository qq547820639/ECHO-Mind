"""深化迭代 T6（backend）审计 P2 修复回归测试。

覆盖（对应 LEDGER T6 台账）：
- P2-5/P2-10：复合索引迁移 20260818_0001（downgrade→upgrade 可重放 + PRAGMA 索引存在）；
- P2-2：portraits fallback coverage 阈值引用 MIN_COVERAGE 单一事实源；
- P2-3：GET /v1/skills、GET /v1/skills/{id}、GET /v1/tenant/portrait 无写副作用；
- P2-6：GET /v1/narratives from/to 范围 clamp（防极端跨度 DoS 面）；
- P2-7：DSR delete / revoke_service 吊销 refresh token（旧凭证不可再续期）；
- P2-9：legacy verify-code 回退多租户同 external_ref 命中确定。
"""
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest
from sqlalchemy import create_engine, func, select, text

from app.database import SessionLocal
from app.models import AuditEvent, Tenant, User

BACKEND_DIR = Path(__file__).resolve().parents[1]


def _admin_headers() -> dict:
    from app.auth import create_access_token

    return {"Authorization": f"Bearer {create_access_token('admin_demo', 't_demo', 'admin')}"}


def _audit_count(tenant_id: str = "t_demo") -> int:
    with SessionLocal() as db:
        return db.scalar(
            select(func.count()).select_from(AuditEvent).where(AuditEvent.tenant_id == tenant_id)
        ) or 0


def _seed_user(user_id: str, external_ref: str) -> None:
    with SessionLocal() as db:
        db.add(User(id=user_id, tenant_id="t_demo", external_ref=external_ref))
        db.commit()


# ---------------------------------------------------------------- P2-5/P2-10 索引迁移

def _sqlite_indexes(conn, table: str) -> set[str]:
    return {row[1] for row in conn.execute(text(f"PRAGMA index_list({table})"))}


@pytest.mark.sqlite_only
def test_query_indexes_migration_replayable(tmp_path, monkeypatch):
    """P2-5/P2-10：迁移链头为 20260818_0001，两个复合索引存在且 downgrade→upgrade 可重放。

    sqlite_only：显式构造 sqlite:/// 临时库（PostgreSQL round-trip 由 CI job 覆盖）。
    """
    from alembic import command
    from alembic.config import Config

    from app.config import get_settings

    db_file = tmp_path / "query_indexes_replay.db"
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{db_file}")
    get_settings.cache_clear()
    try:
        cfg = Config(str(BACKEND_DIR / "alembic.ini"))
        cfg.set_main_option("script_location", str(BACKEND_DIR / "alembic"))

        command.upgrade(cfg, "head")
        with create_engine(f"sqlite:///{db_file}").connect() as conn:
            head = conn.execute(text("SELECT version_num FROM alembic_version")).scalar_one()
            esc_indexes = _sqlite_indexes(conn, "escalations")
            audit_indexes = _sqlite_indexes(conn, "audit_events")
        assert head == "20260818_0001"
        assert "ix_escalations_tenant_opened_at" in esc_indexes
        assert "ix_audit_events_tenant_occurred_at" in audit_indexes

        # downgrade 到前一节点：两个索引随迁移成对删除
        command.downgrade(cfg, "20260816_0001")
        with create_engine(f"sqlite:///{db_file}").connect() as conn:
            esc_indexes = _sqlite_indexes(conn, "escalations")
            audit_indexes = _sqlite_indexes(conn, "audit_events")
        assert "ix_escalations_tenant_opened_at" not in esc_indexes
        assert "ix_audit_events_tenant_occurred_at" not in audit_indexes

        # 再 upgrade：可重放（幂等回到链头）
        command.upgrade(cfg, "head")
        with create_engine(f"sqlite:///{db_file}").connect() as conn:
            head = conn.execute(text("SELECT version_num FROM alembic_version")).scalar_one()
            esc_indexes = _sqlite_indexes(conn, "escalations")
            audit_indexes = _sqlite_indexes(conn, "audit_events")
        assert head == "20260818_0001"
        assert "ix_escalations_tenant_opened_at" in esc_indexes
        assert "ix_audit_events_tenant_occurred_at" in audit_indexes
    finally:
        get_settings.cache_clear()


# ---------------------------------------------------------------- P2-2 常量单一事实源

def test_portraits_fallback_uses_min_coverage_constant():
    """P2-2：portraits fallback 有效日阈值不得出现 0.25 字面量（引用 MIN_COVERAGE）。"""
    from app.services.baseline.calculator import MIN_COVERAGE

    source = (BACKEND_DIR / "app" / "api" / "portraits.py").read_text(encoding="utf-8")
    assert "coverage_score >= 0.25" not in source, "portraits.py 回退到硬编码 0.25 字面量"
    assert "MIN_COVERAGE" in source
    assert MIN_COVERAGE == 0.25


# ---------------------------------------------------------------- P2-3 GET 无写副作用

def test_get_skills_list_no_audit_write(client, user_headers):
    """P2-3：GET /v1/skills 不写审计、不 commit（audit_events 计数不变）。"""
    before = _audit_count()
    response = client.get("/v1/skills", headers=user_headers)
    assert response.status_code == 200
    assert _audit_count() == before


def test_get_tenant_portrait_no_audit_write(client, admin_headers):
    """P2-3：GET /v1/tenant/portrait 不写审计、不 commit（audit_events 计数不变）。"""
    before = _audit_count()
    response = client.get("/v1/tenant/portrait", headers=admin_headers)
    assert response.status_code == 200
    assert _audit_count() == before


# ---------------------------------------------------------------- P2-6 narratives 范围 clamp

def test_narratives_extreme_range_clamped(client, user_headers):
    """P2-6：from=1970→to=9999 极端跨度被 clamp 到 NARRATIVE_MAX_RANGE_DAYS。"""
    from app.api.narratives import NARRATIVE_MAX_RANGE_DAYS

    response = client.get(
        "/v1/narratives",
        params={"user_id": "u_demo", "from": "1970-01-01", "to": "9999-12-31"},
        headers=user_headers,
    )
    assert response.status_code == 200
    body = response.json()
    coverage = body["coverage"]
    assert coverage["requested_days"] == NARRATIVE_MAX_RANGE_DAYS
    assert len(coverage["missing_dates"]) <= NARRATIVE_MAX_RANGE_DAYS
    # 返回的 from 反映 clamp 后实际窗口（以 to 为锚保留最近 N 天）
    assert date.fromisoformat(body["from"]) == date(9999, 12, 31) - timedelta(
        days=NARRATIVE_MAX_RANGE_DAYS - 1
    )
    assert body["to"] == "9999-12-31"


def test_narratives_normal_range_not_clamped(client, user_headers):
    """P2-6：正常小范围查询不受 clamp 影响。"""
    response = client.get(
        "/v1/narratives",
        params={"user_id": "u_demo", "from": "2026-08-01", "to": "2026-08-07"},
        headers=user_headers,
    )
    assert response.status_code == 200
    body = response.json()
    assert body["from"] == "2026-08-01"
    assert body["to"] == "2026-08-07"
    assert body["coverage"]["requested_days"] == 7


# ---------------------------------------------------------------- P2-7 DSR 吊销 refresh token

def _bind_refresh_token(client, code: str) -> dict:
    response = client.post("/v1/onboarding/verify-code", json={"code": code})
    assert response.status_code == 200
    return response.json()


def test_dsr_delete_revokes_refresh_token(client, admin_headers):
    """P2-7：DSR delete 完成后旧 refresh token 不可再换新 access token。"""
    _seed_user("u_dsr_revoke", "REFRESH-DSR-DEL-01")
    bound = _bind_refresh_token(client, "REFRESH-DSR-DEL-01")

    ok = client.post(
        "/v1/auth/refresh",
        json={"user_id": bound["user_id"], "refresh_token": bound["refresh_token"]},
    )
    assert ok.status_code == 200

    dsr = client.post(
        "/v1/data-subject-requests",
        json={"event_id": "evt_dsr_revoke_del_0001", "user_id": "u_dsr_revoke",
              "request_type": "delete"},
        headers=admin_headers,
    )
    assert dsr.status_code == 200
    completed = client.post(
        f"/v1/data-subject-requests/{dsr.json()['id']}/complete",
        json={},
        headers=admin_headers,
    )
    assert completed.status_code == 200

    rejected = client.post(
        "/v1/auth/refresh",
        json={"user_id": bound["user_id"], "refresh_token": bound["refresh_token"]},
    )
    assert rejected.status_code == 401


def test_revoke_service_revokes_refresh_token(client, admin_headers):
    """P2-7：revoke_service 创建即吊销 refresh token（无需等待 complete）。"""
    _seed_user("u_dsr_withdraw", "REFRESH-DSR-WD-01")
    bound = _bind_refresh_token(client, "REFRESH-DSR-WD-01")

    dsr = client.post(
        "/v1/data-subject-requests",
        json={"event_id": "evt_dsr_revoke_wd_0001", "user_id": "u_dsr_withdraw",
              "request_type": "revoke_service"},
        headers=admin_headers,
    )
    assert dsr.status_code == 200

    rejected = client.post(
        "/v1/auth/refresh",
        json={"user_id": bound["user_id"], "refresh_token": bound["refresh_token"]},
    )
    assert rejected.status_code == 401


# ---------------------------------------------------------------- P2-9 legacy 回退确定性

def test_legacy_verify_code_deterministic_hit(client):
    """P2-9：多租户同 external_ref 时 legacy 回退命中确定（created_at/id 最早）。"""
    with SessionLocal() as db:
        db.add(Tenant(id="t_other_org", name="OtherOrg"))
        db.add(User(id="u_legacy_early", tenant_id="t_other_org", external_ref="SHARED-REF-77",
                    created_at=datetime(2026, 1, 1, tzinfo=UTC)))
        db.add(User(id="u_legacy_late", tenant_id="t_demo", external_ref="SHARED-REF-77",
                    created_at=datetime(2026, 2, 1, tzinfo=UTC)))
        db.commit()

    first = client.post("/v1/onboarding/verify-code", json={"code": "SHARED-REF-77"})
    second = client.post("/v1/onboarding/verify-code", json={"code": "SHARED-REF-77"})
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["user_id"] == "u_legacy_early"
    assert first.json()["user_id"] == second.json()["user_id"]
