#!/usr/bin/env python3
"""Build provenance + release artifact manifest（ERA 12.7 修订）。

DAG（v3.3 §10，无循环哈希）：
  SOURCE_MANIFEST（update_release_metadata.py）
      ↓
  clean build → APK / SBOM
      ↓
  BUILD_PROVENANCE.json（本脚本；记录 source_tree_digest / APK / SBOM / toolchain / git）
      ↓
  RELEASE_ARTIFACT_MANIFEST.sha256（最后生成：hash APK/SBOM/Delivery/Provenance/ReleaseNotes）

Provenance 不 hash artifact manifest；artifact manifest 不参与 provenance。
路径一律 Unicode NFC（v3.3 §16）。
"""
import hashlib
import json
import os
import platform
import subprocess
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

#: 与 update_release_metadata.py 同规则（禁止模糊目录名误伤源码包）。
EXCLUDED_PARTS = {
    ".git", ".gradle", ".venv", "__pycache__", ".pytest_cache", ".ruff_cache",
    ".mypy_cache", ".coverage", ".pytest_report", "build", ".idea", ".vscode",
    ".DS_Store", ".trae", ".codebuddy", ".workbuddy",
    "releases",
}
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
    return unicodedata.normalize("NFC", str(path.relative_to(ROOT)))


def git(*args: str) -> str:
    try:
        return subprocess.check_output(["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL).decode().strip()
    except Exception:
        return "unknown"


def git_dirty() -> bool:
    try:
        return subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT).strip() != b""
    except Exception:
        return True


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_tree_digest() -> str:
    h = hashlib.sha256()
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(ROOT)
        if any(part in EXCLUDED_PARTS for part in rel.parts):
            continue
        if path.name in EXCLUDED_FILES or path.suffix in EXCLUDED_SUFFIXES:
            continue
        h.update(_nfc(path).encode("utf-8"))
        h.update(b"\x00")
        h.update(sha256_file(path).encode())
    return h.hexdigest()


def find_apks() -> list[Path]:
    out = ROOT / "android" / "app" / "build" / "outputs" / "apk"
    if not out.exists():
        return []
    return sorted(p for p in out.rglob("*.apk") if p.is_file())


def artifact_manifest_entries(provenance_path: Path, release_notes: Path) -> list[str]:
    """hash：APK / SBOM / DELIVERY_MANIFEST / BUILD_PROVENANCE / Release Notes。"""
    lines = []
    for apk in find_apks():
        lines.append(f"{sha256_file(apk)}  {_nfc(apk)}")
    for p in [ROOT / "sbom.spdx.json", ROOT / "DELIVERY_MANIFEST.json", provenance_path, release_notes]:
        if p.exists():
            lines.append(f"{sha256_file(p)}  {_nfc(p)}")
    return sorted(lines)


def main() -> None:
    version_source = json.loads((ROOT / "scripts" / "version_source.json").read_text(encoding="utf-8"))
    dirty = git_dirty()
    jdk = os.environ.get("JAVA_HOME", "")
    gradle = os.environ.get("GRADLE_VERSION", "")
    apks = find_apks()

    provenance = {
        "schema_version": "echo-build-provenance-v1",
        "git_commit": git("rev-parse", "HEAD"),
        "git_dirty": dirty,
        "release_type": "development" if dirty else "release",
        "release_version": version_source["release_version"],
        "android_version_code": version_source["android_version_code"],
        "android_version_name": version_source["android_version_name"],
        "backend_version": version_source["backend_package_version"],
        "source_tree_sha256": source_tree_digest(),
        "apk_sha256": {_nfc(a): sha256_file(a) for a in apks},
        "sbom_sha256": sha256_file(ROOT / "sbom.spdx.json") if (ROOT / "sbom.spdx.json").exists() else None,
        "jdk_version": jdk,
        "gradle_version": gradle,
        "python_version": platform.python_version(),
        "build_timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "ci_run_id": os.environ.get("CI_RUN_ID", ""),
        "builder_environment": "ci" if os.environ.get("CI") else "local-dev",
    }
    provenance_path = ROOT / "BUILD_PROVENANCE.json"
    provenance_path.write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 最后：RELEASE_ARTIFACT_MANIFEST（DAG 末端；不参与 provenance）
    release_notes = ROOT / version_source["release_notes_file"]
    lines = artifact_manifest_entries(provenance_path, release_notes)
    (ROOT / "RELEASE_ARTIFACT_MANIFEST.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(json.dumps({
        "commit": provenance["git_commit"][:12],
        "release_type": provenance["release_type"],
        "source_tree_sha256": provenance["source_tree_sha256"][:16],
        "apks": len(apks),
        "artifact_entries": len(lines),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
