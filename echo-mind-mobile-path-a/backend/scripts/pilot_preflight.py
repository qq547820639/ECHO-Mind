from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import json
from app.config import get_settings
from app.database import SessionLocal
from sqlalchemy import text


def main() -> int:
    settings = get_settings()
    checks = []
    checks.append({"name": "environment", "ok": settings.environment in {"pilot", "production"}, "value": settings.environment})
    checks.append({"name": "jwt_secret", "ok": "dev-secret" not in settings.jwt_secret})
    checks.append({"name": "field_encryption_secret", "ok": "dev-field" not in settings.field_encryption_secret})
    checks.append({"name": "bootstrap_key", "ok": settings.bootstrap_key != "local-bootstrap-only"})
    try:
        with SessionLocal() as db:
            db.execute(text("SELECT 1"))
        checks.append({"name": "database", "ok": True})
    except Exception as exc:
        checks.append({"name": "database", "ok": False, "error": type(exc).__name__})
    result = {"ok": all(x["ok"] for x in checks), "checks": checks}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
