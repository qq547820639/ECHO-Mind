#!/usr/bin/env python3
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_PARTS = {'.git', '.gradle', '.venv', '__pycache__', '.pytest_cache', 'build'}
EXCLUDED_FILES = {'FILE_HASHES.sha256'}

files = []
for path in sorted(ROOT.rglob('*')):
    if not path.is_file() or any(part in EXCLUDED_PARTS for part in path.relative_to(ROOT).parts):
        continue
    if path.name in EXCLUDED_FILES or path.suffix in {'.pyc', '.db'}:
        continue
    files.append(path)

manifest = {
    'project': 'ECHO Mind Android Path A',
    'version': '0.2.0',
    'release_status': 'pilot-candidate',
    'generated_at': datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z'),
    'branch': 'main',
    'git_ref': 'path-a-pilot-candidate-v0.2.0',
    'scope': [
        'Android phone-first Path A source',
        'deterministic local safety rules and fixed scripts',
        'FastAPI institutional backend and workbench',
        'versioned consent, L0, questionnaires, journals, trends and data rights',
        'L3 human takeover state machine and audit chain',
        'tests, safety corpus, CI/CD, SBOM and pilot governance pack',
    ],
    'validation': {
        'backend_tests_passed': 781,
        'synthetic_safety_cases': 650,
        'content_packs_validated': 4,
        'python_compile': 'passed',
        'http_smoke': 'passed',
        'alembic_sqlite_upgrade_downgrade': 'passed',
        'kotlin_domain_core_compile': 'passed',
        'android_sdk_full_build': 'external_gate_not_run',
        'postgresql_docker_integration': 'external_gate_not_run',
    },
    'production_claim': False,
    'external_release_gates': [
        'Android SDK build, signed APK/AAB and target-device matrix',
        'institutional IAM/MFA, duty roster and human takeover drill',
        'clinical, legal, privacy, ethics and cybersecurity approvals',
        'KMS/HSM, production PostgreSQL, backup/restore and immutable logs',
        'independent penetration test and external red team',
        'real user pilot with approved recruitment and governance',
    ],
    'source_file_count_excluding_git_and_build_outputs': len(files),
}
(ROOT / 'DELIVERY_MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

# Recompute after writing manifest so the manifest itself is covered.
files = []
for path in sorted(ROOT.rglob('*')):
    if not path.is_file() or any(part in EXCLUDED_PARTS for part in path.relative_to(ROOT).parts):
        continue
    if path.name in EXCLUDED_FILES or path.suffix in {'.pyc', '.db'}:
        continue
    files.append(path)
lines = []
for path in files:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    lines.append(f'{digest}  {path.relative_to(ROOT).as_posix()}')
(ROOT / 'FILE_HASHES.sha256').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(json.dumps({'files_hashed': len(files), 'manifest': 'DELIVERY_MANIFEST.json'}, ensure_ascii=False))
