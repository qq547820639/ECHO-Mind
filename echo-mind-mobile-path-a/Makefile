.PHONY: preflight backend-test backend-run openapi safety sbom android package

preflight:
	./scripts/release_preflight.sh

backend-test:
	cd backend && pytest -q

backend-run:
	cd backend && uvicorn app.main:app --reload --port 8000

openapi:
	cd backend && python scripts/export_openapi.py

safety:
	python scripts/validate_content_packs.py
	python scripts/claim_scan.py
	python scripts/check_dynamic_code.py
	python scripts/safety_eval.py

sbom:
	python scripts/generate_sbom.py

android:
	cd android && ./gradlew test assembleDebug lint

package:
	./scripts/package_release.sh
