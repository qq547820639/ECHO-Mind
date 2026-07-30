from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.database import SessionLocal
from app.services.audit import verify_audit_chain

if __name__ == "__main__":
    import argparse, json
    parser = argparse.ArgumentParser()
    parser.add_argument("tenant_id")
    args = parser.parse_args()
    with SessionLocal() as db:
        result = verify_audit_chain(db, args.tenant_id)
    print(json.dumps(result, ensure_ascii=False, indent=2, default=str))
    raise SystemExit(0 if result["valid"] else 2)
