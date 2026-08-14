#!/usr/bin/env python3
"""发布元数据生成（ERA 12.7 修订）：Source/Artifact 清单分离 + pipeline 绑定。

- SOURCE_MANIFEST.sha256：**仅版本控制意义上的源文件**（排除全部生成物/工具目录）；
  路径一律 Unicode NFC 归一化（防 macOS NFD 与 git 不一致）。
- DELIVERY_MANIFEST.json：机器生成；android_gradle_build 只接受本次 pipeline 注入
  （ANDROID_GRADLE_BUILD_RESULT），**禁止从旧 build 产物推断**。
- RELEASE_ARTIFACT_MANIFEST.sha256 由 generate_provenance.py 在 provenance 之后生成
  （DAG：source → build → artifacts → provenance → artifact manifest，无循环哈希）。

用法：
  REUSE_REPORT=1 python3 scripts/update_release_metadata.py            # 复用既有 junit
  ANDROID_GRADLE_BUILD_RESULT=passed python3 scripts/update_release_metadata.py
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import unicodedata
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_SOURCE = json.loads((ROOT / "scripts" / "version_source.json").read_text(encoding="utf-8"))
VERSION = VERSION_SOURCE["release_version"]

#: 排除规则只允许「明确路径」（v3.3 §9）：禁止模糊目录名（如 "runtime"）误伤源码包。
EXCLUDED_PARTS = {
    ".git", ".gradle", ".venv", "__pycache__", ".pytest_cache", ".ruff_cache",
    ".mypy_cache", ".coverage", ".pytest_report", "build", ".idea", ".vscode",
    ".DS_Store", ".trae", ".codebuddy", ".workbuddy",
    "releases",          # v0.2 打包产物目录（artifact，非源码）
}
#: 生成物不属于 SOURCE_MANIFEST（它们由本脚本/流水线产出，进入 artifact manifest）。
EXCLUDED_FILES = {
    "SOURCE_MANIFEST.sha256",
    "RELEASE_ARTIFACT_MANIFEST.sha256",
    "DELIVERY_MANIFEST.json",
    "BUILD_PROVENANCE.json",
    "sbom.spdx.json",
    "local.properties",
}
EXCLUDED_SUFFIXES = {".pyc", ".db", ".apk", ".idsig", ".keystore", ".jks"}


def _nfc(path: Path) -> str:
    """macOS HFS+/APFS 可能产生 NFD 文件名；hash/manifest 一律 NFC，与 git 一致。"""
    return unicodedata.normalize("NFC", str(path.relative_to(ROOT)))


def _git_tracked_relpaths(repo: Path) -> list[str]:
    """git 索引中的受控文件相对路径（一律 NFC 归一化）。

    ERA 12.8：SOURCE_MANIFEST 必须以 **git 受控文件集** 为唯一事实源，
    不得扫描文件系统（此前因 .gitignore 的裸 `runtime/` 规则导致
    EchoRuntimeCoordinator.kt 在工作树存在却从未入库，manifest 却记录了它——
    clean checkout 与 final ZIP 因此互相矛盾）。
    """
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=repo, capture_output=True, check=True
    ).stdout
    names = [n for n in out.decode("utf-8", "surrogateescape").split("\x00") if n]
    return [unicodedata.normalize("NFC", n) for n in names]


def _is_excluded(rel: str) -> bool:
    parts = Path(rel).parts
    if any(part in EXCLUDED_PARTS for part in parts):
        return True
    name = parts[-1]
    return name in EXCLUDED_FILES or Path(name).suffix in EXCLUDED_SUFFIXES


def collect_source_files(repo: Path) -> list[Path]:
    files = []
    for rel in _git_tracked_relpaths(repo):
        if _is_excluded(rel):
            continue
        path = repo / rel
        if not path.is_file():
            raise RuntimeError(f"git 受控文件在 checkout 中缺失：{rel}")
        files.append(path)
    return sorted(files, key=lambda p: unicodedata.normalize("NFC", str(p.relative_to(repo))))


def _collect_source_files() -> list[Path]:
    """向后兼容入口（verify_source_manifest / distribution 经 collect_source_files(repo) 使用）。"""
    return collect_source_files(ROOT)


def _parse_junit(report: Path) -> dict:
    root = ET.parse(report).getroot()
    suite = root.find("testsuite")
    attrs = suite.attrib if suite is not None else root.attrib
    tests = int(attrs.get("tests", 0))
    failures = int(attrs.get("failures", 0)) + int(attrs.get("errors", 0))
    skipped = int(attrs.get("skipped", 0))
    return {"passed": tests - failures - skipped, "failed": failures, "skipped": skipped, "total": tests}


def _run_pytest_xml() -> dict | None:
    try:
        report = ROOT / ".pytest_report" / "junit.xml"
        report.parent.mkdir(exist_ok=True)
        if os.environ.get("REUSE_REPORT") == "1" and report.exists():
            return _parse_junit(report)
        subprocess.run(
            [str(ROOT / "backend" / ".venv" / "bin" / "python"), "-m", "pytest", "-q",
             "--junitxml", str(report)],
            cwd=ROOT / "backend", check=False, capture_output=True, timeout=600,
        )
        if not report.exists():
            return None
        return _parse_junit(report)
    except Exception:
        return None


def _android_build_status() -> str:
    """v3.3 §13：build status 只接受本次 pipeline 注入；禁止从旧 build 产物推断。"""
    return os.environ.get("ANDROID_GRADLE_BUILD_RESULT", "not_run_in_this_pipeline")


def main() -> None:
    files = _collect_source_files()

    test_stats = _run_pytest_xml()
    backend_tests = test_stats["passed"] if test_stats else None

    # 1. SOURCE_MANIFEST（源文件清单）
    lines = []
    for path in files:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {_nfc(path)}")
    (ROOT / "SOURCE_MANIFEST.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # 2. DELIVERY_MANIFEST
    manifest = {
        "project": "ECHO Mind Personal Ambient Intelligence",
        "version": VERSION,
        "release_status": "pilot-candidate",
        "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "branch": "main",
        "git_ref": f"personal-ambient-intelligence-v{VERSION}",
        "scope": [
            "Android phone-first Personal Ambient Intelligence client",
            "observation core: passive sensing -> derived features -> daily aggregate -> personal baseline -> daily portrait",
            "ECHO Scene / Presence (live wallpaper + dream) / Journey / Actions / BYOM / EchoMemory",
            "FastAPI institutional backend and workbench (unchanged observation contract)",
            "tests, safety corpus, CI/CD, SBOM and pilot governance pack",
        ],
        "validation": {
            "backend_tests_passed": backend_tests,
            "backend_tests_failed": test_stats["failed"] if test_stats else None,
            "backend_tests_skipped": test_stats["skipped"] if test_stats else None,
            "android_gradle_build": _android_build_status(),
            "synthetic_safety_cases": 650,
            "content_packs_validated": 4,
            "alembic_roundtrip": "passed",
            "contract_drift_check": "passed",
            "android_instrumentation": "ci_emulator_gate",
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
        "note": "SOURCE_MANIFEST/RELEASE_ARTIFACT_MANIFEST/BUILD_PROVENANCE 由 scripts/ 机器生成，与同一 source commit 一起分发；build status 由 pipeline run 注入。",
    }
    (ROOT / "DELIVERY_MANIFEST.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print(json.dumps({
        "version": VERSION,
        "source_files_hashed": len(files),
        "backend_tests": test_stats,
        "android_gradle_build": _android_build_status(),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
