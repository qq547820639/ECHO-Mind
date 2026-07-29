import os
os.environ["DATABASE_URL"] = "sqlite+pysqlite:///:memory:"
os.environ["JWT_SECRET"] = "test-secret-at-least-32-bytes-long"

import pytest
from fastapi.testclient import TestClient
from app.auth import create_access_token
from app.database import Base, SessionLocal, engine
from app.main import app
from app.models import Consent, Tenant, User


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
