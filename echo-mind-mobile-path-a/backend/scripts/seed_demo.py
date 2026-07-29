from pathlib import Path
import hashlib
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.database import Base, SessionLocal, engine
from app.models import Consent, Tenant, User

Base.metadata.create_all(bind=engine)
with SessionLocal() as db:
    tenant = db.get(Tenant, "t_demo")
    if not tenant:
        tenant = Tenant(id="t_demo", name="演示机构")
        db.add(tenant)
    user = db.get(User, "u_demo")
    if not user:
        user = User(id="u_demo", tenant_id="t_demo", external_ref="demo-user")
        db.add(user)
        db.flush()
    consent = db.query(Consent).filter_by(tenant_id="t_demo", user_id="u_demo", consent_type="psychological_data").first()
    if not consent:
        db.add(Consent(
            id="c_demo_psych",
            tenant_id="t_demo",
            user_id="u_demo",
            consent_type="psychological_data",
            version="path-a-consent-2026.07",
            granted=True,
            evidence_hash=hashlib.sha256(b"demo-consent").hexdigest(),
        ))
    db.commit()
print("seeded t_demo / u_demo / psychological_data consent")
