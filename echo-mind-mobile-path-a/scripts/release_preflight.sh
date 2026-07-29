#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python -m compileall -q backend/app backend/scripts backend/tests scripts
(cd backend && pytest -q)
python scripts/validate_content_packs.py
python scripts/claim_scan.py
python scripts/check_dynamic_code.py
python scripts/safety_eval.py
(cd backend && python scripts/export_openapi.py)
rm -f /tmp/echo-migration.db
(cd backend && DATABASE_URL=sqlite:////tmp/echo-migration.db alembic upgrade head)
(cd backend && DATABASE_URL=sqlite:////tmp/echo-migration.db alembic downgrade base)
rm -rf /tmp/kotlin-check && mkdir -p /tmp/kotlin-check
kotlinc \
  android/app/src/main/java/com/yunjue/echo/mind/model/Models.kt \
  android/app/src/main/java/com/yunjue/echo/mind/security/SafetyEngine.kt \
  android/app/src/main/java/com/yunjue/echo/mind/security/QuestionnaireScorer.kt \
  -d /tmp/kotlin-check/core.jar
python - <<'PY'
import json
from pathlib import Path
json.loads(Path("sbom.spdx.json").read_text(encoding="utf-8"))
print("SBOM JSON valid")
PY
printf '\nLOCAL PREFLIGHT PASSED\n'
printf 'Android SDK build remains an external gate in this environment.\n'
