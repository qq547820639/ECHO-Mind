#!/usr/bin/env python3
"""Release Set 完整性测试（ERA 18 §94 + §107 release tests）。

校验 releases/ECHO_Mind_v{VERSION}.release.zip：
1. §94 九要素齐全：signed APK、SOURCE_MANIFEST、SBOM、BUILD_PROVENANCE、
   DELIVERY_MANIFEST、RELEASE_ARTIFACT_MANIFEST、Release Notes、source archives(zip+tar.gz)；
2. RELEASE_ARTIFACT_MANIFEST 的每个条目都在包内且 hash 一致（APK SHA256 绑定）；
3. BUILD_PROVENANCE.release_apk_sha256 == 包内 APK 实测 sha256（§11/§12 绑定）；
4. provenance 记录的 source archive hash == 包内实测；
5. Release Notes 非空且声明版本号。

运行：python3 -m pytest scripts/test_release_set.py -q
"""
from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import distribution as dist  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
VERSION = json.loads(
    (ROOT / "scripts" / "version_source.json").read_text(encoding="utf-8")
)["release_version"]
RELEASE_ZIP = ROOT / "releases" / f"ECHO_Mind_v{VERSION}.release.zip"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


@pytest.fixture(scope="module")
def package_files() -> dict[str, bytes]:
    if not RELEASE_ZIP.is_file():
        pytest.skip(f"release zip 不存在：{RELEASE_ZIP}")
    with zipfile.ZipFile(RELEASE_ZIP) as zf:
        return {name: zf.read(name) for name in zf.namelist()}


def read_in_pkg(files: dict[str, bytes], rel: str) -> bytes:
    candidates = [n for n in files if n == rel or n.endswith("/" + rel)]
    assert candidates, f"包内缺少：{rel}"
    return files[candidates[0]]


def test_release_set_contains_all_required_artifacts(package_files):
    required = [
        f"ECHO_Mind_v{VERSION}.apk",
        "SOURCE_MANIFEST.sha256",
        "sbom.spdx.json",
        "BUILD_PROVENANCE.json",
        "DELIVERY_MANIFEST.json",
        "RELEASE_ARTIFACT_MANIFEST.sha256",
        f"RELEASE_NOTES_v{VERSION}.md",
        f"ECHO_Mind_PortraitCore_v{VERSION}.zip",
        f"ECHO_Mind_PortraitCore_v{VERSION}.tar.gz",
    ]
    present = {n for n in package_files}
    for rel in required:
        assert any(n == rel or n.endswith("/" + rel) for n in present), f"§94 release set 缺少：{rel}"


def test_artifact_manifest_entries_match_package(package_files):
    manifest_text = read_in_pkg(package_files, "RELEASE_ARTIFACT_MANIFEST.sha256").decode("utf-8")
    entries = dist.parse_manifest(manifest_text)
    assert entries, "RELEASE_ARTIFACT_MANIFEST 为空"
    for rel, digest in entries.items():
        data = read_in_pkg(package_files, rel)
        actual = hashlib.sha256(data).hexdigest()
        assert digest == actual, f"artifact hash 不匹配：{rel}"


def test_signed_apk_bound_to_provenance(package_files):
    apk = read_in_pkg(package_files, f"ECHO_Mind_v{VERSION}.apk")
    provenance = json.loads(read_in_pkg(package_files, "BUILD_PROVENANCE.json").decode("utf-8"))
    actual = hashlib.sha256(apk).hexdigest()
    assert provenance["release_apk_sha256"] == actual, "provenance.release_apk_sha256 与包内 APK 不一致"
    assert provenance["signing_stage"] == "signed", "release 包内 APK 必须已签名"
    assert provenance["release_type"] == "release" and not provenance["git_dirty"]


def test_source_archive_hashes_match_provenance(package_files):
    provenance = json.loads(read_in_pkg(package_files, "BUILD_PROVENANCE.json").decode("utf-8"))
    for rel, digest in provenance["source_archive_sha256"].items():
        data = read_in_pkg(package_files, rel)
        assert hashlib.sha256(data).hexdigest() == digest, f"archive hash 与 provenance 不一致：{rel}"


def test_release_notes_declare_version(package_files):
    notes = read_in_pkg(package_files, f"RELEASE_NOTES_v{VERSION}.md").decode("utf-8")
    assert VERSION in notes, "Release Notes 必须声明版本号"
