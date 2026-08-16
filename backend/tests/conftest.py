import os

# 测试默认使用 SQLite 内存库；CI 的 PostgreSQL job 通过环境变量覆盖（setdefault 不覆盖已设置的）。
os.environ.setdefault("DATABASE_URL", "sqlite+pysqlite:///:memory:")
# ERA 32 R25：全部三项秘密必须显式非默认值（validate_production_secrets 对任何环境 fail-closed）。
os.environ.setdefault("JWT_SECRET", "test-secret-at-least-32-bytes-long")
os.environ.setdefault("FIELD_ENCRYPTION_SECRET", "test-field-encryption-secret-not-default")
os.environ.setdefault("BOOTSTRAP_KEY", "test-bootstrap-key-not-default")

import pytest
from fastapi.testclient import TestClient
from app.auth import create_access_token
from app.database import Base, SessionLocal, engine
from app.main import app
from app.models import Consent, Tenant, User


def _is_postgres() -> bool:
    return (os.environ.get("DATABASE_URL") or "").startswith("postgres")


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line("markers", "sqlite_only: 依赖 SQLite 内存库语义的用例（PG job 跳过）")
    config.addinivalue_line("markers", "postgres_only: 依赖 PostgreSQL 语义的用例（SQLite 默认路径跳过）")


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    """按当前 DATABASE_URL 跳过不适用的用例：
    - PG 运行时跳过 sqlite_only；
    - SQLite 运行时跳过 postgres_only。
    """
    if _is_postgres():
        skip_marker = "sqlite_only"
        reason = "当前运行于 PostgreSQL，跳过 SQLite-only 用例"
    else:
        skip_marker = "postgres_only"
        reason = "当前运行于 SQLite 默认路径，跳过 PostgreSQL-only 用例"
    skip = pytest.mark.skip(reason=reason)
    for item in items:
        if skip_marker in item.keywords:
            item.add_marker(skip)


@pytest.fixture(autouse=True)
def clean_db():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        db.add(Tenant(id="t_demo", name="Demo"))
        db.add(User(id="u_demo", tenant_id="t_demo", external_ref="demo"))
        db.add(User(id="u_other", tenant_id="t_demo", external_ref="other"))
        db.add(Consent(id="c_demo", tenant_id="t_demo", user_id="u_demo", consent_type="psychological_data", version="test-v1", granted=True, evidence_hash="0" * 64))
        db.add(Consent(id="c_other", tenant_id="t_demo", user_id="u_other", consent_type="psychological_data", version="test-v1", granted=True, evidence_hash="1" * 64))
        db.commit()
    yield
    Base.metadata.drop_all(bind=engine)


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def user_headers():
    return {"Authorization": f"Bearer {create_access_token('u_demo', 't_demo', 'user')}"}


@pytest.fixture
def staff_headers():
    return {"Authorization": f"Bearer {create_access_token('staff', 't_demo', 'on_call')}"}


@pytest.fixture
def professional_headers():
    return {"Authorization": f"Bearer {create_access_token('pro', 't_demo', 'professional')}"}


@pytest.fixture
def admin_headers():
    """admin 角色 headers（subject=admin_demo，tenant=t_demo）。"""
    return {"Authorization": f"Bearer {create_access_token('admin_demo', 't_demo', 'admin')}"}


@pytest.fixture
def auditor_headers():
    """auditor 角色 headers（subject=auditor_demo，tenant=t_demo）。"""
    return {"Authorization": f"Bearer {create_access_token('auditor_demo', 't_demo', 'auditor')}"}


@pytest.fixture
def passive_sensing_consent(client, user_headers):
    """预置 u_demo 的 passive_sensing 同意（granted=True）。

    参考 test_passive_sensing.py L23-31 的 grant 模式；测试依赖此 fixture 时
    无需再单独 grant，可直接 ingest 派生特征。
    """
    response = client.post("/v1/onboarding/consents", json={
        "user_id": "u_demo",
        "consent_type": "passive_sensing",
        "version": "test-v1",
        "granted": True,
        "evidence_hash": "a" * 64,
    }, headers=user_headers)
    assert response.status_code == 200
    return response.json()
