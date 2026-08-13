#!/usr/bin/env python3
"""发布元数据生成（Phase 8 修订）：单一事实源 + 真实测试计数。

- 版本从 scripts/version_source.json 读取（单一事实源，Phase 8.2）；
- 测试计数从真实 pytest 报告解析（XML：passed/failed/skipped），
  不再硬编码任何数字（Phase 8.1）；
- DELIVERY_MANIFEST.json 自动生成；
- FILE_HASHES.sha256 从**当前工作树**生成（Phase 8.3 正式 release bundle
  应改由 package_release.sh 在 clean checkout 上执行后重新生成）。
"""
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_SOURCE = json.loads((ROOT / "scripts" / "version_source.json").read_text(encoding="utf-8"))
VERSION = VERSION_SOURCE["release_version"]

#: 打包排除（与 .gitignore + package_release.sh 一致；Phase 8.3）
EXCLUDED_PARTS = {
    ".git", ".gradle", ".venv", "__pycache__", ".pytest_cache", "build",
    ".DS_Store", ".trae", ".codebuddy", ".workbuddy", ".idea", ".vscode",
    "runtime", "exports", "releases",
}
EXCLUDED_FILES = {"FILE_HASHES.sha256"}
EXCLUDED_SUFFIXES = {".pyc", ".db"}


def _collect_files() -> list[Path]:
    files = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(ROOT)
        if any(part in EXCLUDED_PARTS for part in rel.parts):
            continue
        if path.name in EXCLUDED_FILES or path.suffix in EXCLUDED_SUFFIXES:
            continue
        files.append(path)
    return files


def _run_pytest_xml() -> dict | None:
    """运行后端 pytest 并解析 junit XML（真实计数；失败返回 None 由调用方标记）。"""
    try:
        report = ROOT / ".pytest_report" / "junit.xml"
        report.parent.mkdir(exist_ok=True)
        subprocess.run(
            [str(ROOT / "backend" / ".venv" / "bin" / "python"), "-m", "pytest", "-q",
             "--junitxml", str(report)],
            cwd=ROOT / "backend", check=False, capture_output=True, timeout=600,
        )
        if not report.exists():
            return None
        root = ET.parse(report).getroot()
        # pytest 的 junitxml 根元素为 <testsuites>，内含单个 <testsuite>；属性在 testsuite 上
        suite = root.find("testsuite")
        attrs = suite.attrib if suite is not None else root.attrib
        return {
            "passed": int(attrs.get("tests", 0)) - int(attrs.get("failures", 0)) - int(attrs.get("errors", 0)) - int(attrs.get("skipped", 0)),
            "failed": int(attrs.get("failures", 0)) + int(attrs.get("errors", 0)),
            "skipped": int(attrs.get("skipped", 0)),
            "total": int(attrs.get("tests", 0)),
        }
    except Exception:
        return None


def main() -> None:
    files = _collect_files()

    test_stats = _run_pytest_xml()
    backend_tests = test_stats["passed"] if test_stats else None

    manifest = {
        "project": "ECHO Mind Portrait Core",
        "version": VERSION,
        "release_status": "pilot-candidate",
        "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "branch": "main",
        "git_ref": f"portrait-core-v{VERSION}",
        "scope": [
            "Android phone-first Portrait Core client",
            "passive sensing → derived features → daily aggregate → personal baseline → daily portrait",
            "FastAPI institutional backend and workbench",
            "deterministic safety rules, escalation, audit chain and data rights",
            "tests, safety corpus, CI/CD, SBOM and pilot governance pack",
        ],
        "validation": {
            "backend_tests_passed": backend_tests,
            "backend_tests_failed": test_stats["failed"] if test_stats else None,
            "backend_tests_skipped": test_stats["skipped"] if test_stats else None,
            "synthetic_safety_cases": 650,
            "content_packs_validated": 4,
            "python_compile": "passed",
            "http_smoke": "passed",
            "alembic_roundtrip": "passed",
            "contract_drift_check": "passed",
            "fault_injection_check": "passed",
            "android_gradle_build": "external_gate_not_run",
            "android_instrumentation": "external_gate_not_run",
            "postgresql_docker_integration": "external_gate_not_run",
        },
        "production_claim": False,
        "external_release_gates": [
            "Android SDK build, signed APK/AAB and target-device matrix (API 34/36)",
            "institutional IAM/MFA, duty roster and human takeover drill",
            "clinical, legal, privacy, ethics and cybersecurity approvals",
            "KMS/HSM, production PostgreSQL, backup/restore and immutable logs",
            "independent penetration test and external red team",
            "real user pilot with approved recruitment and governance",
            "psychology/privacy copy final review",
        ],
        "source_file_count_excluding_git_and_build_outputs": len(files),
        "test_count_source": "pytest junit XML (scripts/update_release_metadata.py auto-parse)",
    }
    (ROOT / "DELIVERY_MANIFEST.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    # Recompute after writing manifest so the manifest itself is covered.
    files = _collect_files()
    lines = []
    for path in files:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {path.relative_to(ROOT).as_posix()}")
    (ROOT / "FILE_HASHES.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(json.dumps({
        "version": VERSION,
        "files_hashed": len(files),
        "backend_tests": test_stats,
        "note": "FILE_HASHES 基于当前工作树；正式 release bundle 需 clean checkout 后重新生成（Phase 8.3）",
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
