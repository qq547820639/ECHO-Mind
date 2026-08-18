#!/usr/bin/env python3
"""Build provenance + release artifact manifest（ERA 12.8 修订）。

DAG（无循环哈希，§13）：
  SOURCE_MANIFEST（update_release_metadata.py / git 受控源文件集）
      ↓
  source_tree_sha256（本脚本：清单归一化内容哈希）
      ↓
  clean build → APK / SBOM / source archive
      ↓
  BUILD_PROVENANCE.json（记录 release APK 根路径绑定、unsigned/signed 双哈希、
                          signing_stage/signature_scheme、toolchain、git）
      ↓
  RELEASE_ARTIFACT_MANIFEST.sha256（DAG 末端：只 hash 最终交付物）

§11：根目录 ECHO_Mind_vX.Y.Z.apk 直接进入 provenance（release_apk_path/release_apk_sha256）。
§12：unsigned → sign → final 流程记录 unsigned_apk_sha256 + release_apk_sha256。
§10：artifact manifest 不再 hash build 目录中的 debug/androidTest 临时 APK。
§15：git_dirty=true 时 release_type=development（--require-clean 时直接 FAIL）。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SCHEMA = "echo-build-provenance-v2"


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*args: str) -> str:
    try:
        return subprocess.check_output(
            ["git", *args], cwd=ROOT, stderr=subprocess.DEVNULL
        ).decode().strip()
    except Exception:
        return "unknown"


#: 本流程自身产出的机器生成文件（每次 release run 都重写；dirty 判定只看源码）。
GENERATED_METADATA = {
    "SOURCE_MANIFEST.sha256",
    "RELEASE_ARTIFACT_MANIFEST.sha256",
    "DELIVERY_MANIFEST.json",
    "BUILD_PROVENANCE.json",
    "sbom.spdx.json",
}


def git_dirty() -> bool:
    """§15：源码树是否 dirty。

    机器生成的元数据文件（GENERATED_METADATA）不计入——它们由本流水线重写，
    判定语义是「源码有没有未提交变更」；CI --require-clean 对源码变更硬失败。
    """
    try:
        out = subprocess.check_output(
            ["git", "status", "--porcelain=v1", "--untracked-files=all"], cwd=ROOT
        ).decode("utf-8", "replace")
    except Exception:
        return True
    dirty = []
    for line in out.splitlines():
        path = line[3:].strip().strip('"')
        path = path.split(" -> ", 1)[-1]  # rename 行
        if path not in GENERATED_METADATA:
            dirty.append(line)
    return bool(dirty)


def source_tree_sha256() -> str:
    """source_tree_sha256 = SOURCE_MANIFEST 归一化内容哈希（§13：SOURCE_MANIFEST ↓ tree sha）。"""
    text = (ROOT / "SOURCE_MANIFEST.sha256").read_text(encoding="utf-8")
    normalized = "\n".join(sorted(text.splitlines())) + "\n"
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def detect_jdk_version() -> str:
    java = shutil.which("java")
    if not java and os.environ.get("JAVA_HOME"):
        java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
    if not java:
        return os.environ.get("JDK_VERSION", "")
    try:
        out = subprocess.run([java, "-version"], capture_output=True, text=True).stderr
        m = re.search(r'version "([^"]+)"', out)
        return m.group(1) if m else ""
    except Exception:
        return os.environ.get("JDK_VERSION", "")


def detect_gradle_version() -> str:
    props = ROOT / "android" / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            m = re.match(r"distributionUrl=.*gradle-([\d.]+)-", line)
            if m:
                return m.group(1)
    return os.environ.get("GRADLE_VERSION", "")


def find_apksigner() -> str | None:
    sdk_dirs = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    props = ROOT / "android" / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("sdk.dir="):
                sdk_dirs.append(line.strip().split("=", 1)[1].replace("\\:", ":"))
    for sdk in sdk_dirs:
        if not sdk:
            continue
        for candidate in sorted(Path(sdk).glob("build-tools/*/apksigner"), reverse=True):
            return str(candidate)
    return shutil.which("apksigner")


def inspect_signature(apk: Path) -> tuple[str, str]:
    """返回 (signing_stage, signature_scheme)；不读取任何 signing key 材料。"""
    env_stage = os.environ.get("ECHO_SIGNING_STAGE", "")
    env_scheme = os.environ.get("ECHO_SIGNATURE_SCHEME", "")
    apksigner = find_apksigner()
    if not apksigner:
        return env_stage or "unknown", env_scheme or "unknown"
    env = dict(os.environ)
    if env.get("JAVA_HOME"):
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    try:
        out = subprocess.run(
            [apksigner, "verify", "--verbose", "--print-certs", str(apk)],
            capture_output=True, text=True, env=env,
        ).stdout
    except Exception:
        return env_stage or "unknown", env_scheme or "unknown"
    schemes = re.findall(r"Verified using v(\d+) scheme \(([^)]+)\): true", out)
    if schemes:
        scheme = ",".join(f"v{n}" for n, _ in schemes)
        return env_stage or "signed", env_scheme or scheme
    return env_stage or "signed-unknown", env_scheme or "unknown"


def find_root_apk(version: str) -> Path | None:
    candidate = ROOT / f"ECHO_Mind_v{version}.apk"
    return candidate if candidate.is_file() else None


def find_unsigned_apk() -> Path | None:
    candidate = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"
    return candidate if candidate.is_file() else None


def artifact_manifest_lines(provenance: dict, version: str, release_notes: Path,
                            archives: list[Path], root_apk: Path | None,
                            unsigned_apk: Path | None) -> list[str]:
    """§10：只描述最终交付物。"""
    paths: list[Path] = []
    deliverable_apk = root_apk if root_apk else unsigned_apk
    if deliverable_apk:
        paths.append(deliverable_apk)
        idsig = Path(str(deliverable_apk) + ".idsig")
        if idsig.is_file():
            paths.append(idsig)
    for p in [
        ROOT / "sbom.spdx.json",
        ROOT / "DELIVERY_MANIFEST.json",
        ROOT / "BUILD_PROVENANCE.json",
        ROOT / "SOURCE_MANIFEST.sha256",
        release_notes,
    ]:
        if p.is_file():
            paths.append(p)
    paths += [a for a in archives if a.is_file()]
    lines = [f"{sha256_file(p)}  {nfc(str(p.relative_to(ROOT)))}" for p in paths]
    return sorted(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--require-clean", action="store_true",
                    help="git_dirty=true 时直接 FAIL（正式 release 门禁，§15）")
    args = ap.parse_args()

    version_source = json.loads(
        (ROOT / "scripts" / "version_source.json").read_text(encoding="utf-8")
    )
    version = version_source["release_version"]
    dirty = git_dirty()
    if args.require_clean and dirty:
        print("FAIL：正式 Release 禁止来自 dirty tree（§15）。提交/暂存全部变更后重试。")
        return 1

    root_apk = find_root_apk(version)
    unsigned_apk = find_unsigned_apk()
    signing_stage, signature_scheme = ("unsigned", "unsigned")
    if root_apk:
        signing_stage, signature_scheme = inspect_signature(root_apk)

    archives = [ROOT / "releases" / f"ECHO_Mind_PortraitCore_v{version}.zip",
                ROOT / "releases" / f"ECHO_Mind_PortraitCore_v{version}.tar.gz"]
    sbom = ROOT / "sbom.spdx.json"

    provenance = {
        "schema_version": SCHEMA,
        "release_version": version,
        "git_commit": git("rev-parse", "HEAD"),
        "git_dirty": dirty,
        "release_type": "development" if dirty else "release",
        "source_tree_sha256": source_tree_sha256(),
        "source_manifest_sha256": sha256_file(ROOT / "SOURCE_MANIFEST.sha256"),
        "android_version_name": version_source["android_version_name"],
        "android_version_code": version_source["android_version_code"],
        "backend_version": version_source["backend_package_version"],
        "jdk_version": detect_jdk_version(),
        "gradle_version": detect_gradle_version(),
        "python_version": platform.python_version(),
        "build_timestamp_utc": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        # ci_run_id：只接受真实 run 注入（ECHO_CI_RUN_ID/CI_RUN_ID/GITHUB_RUN_ID）；
        # 无 CI run 时如实 unknown（不伪造空串/占位值——T7-P2-8 诚实化）。
        "ci_run_id": os.environ.get("ECHO_CI_RUN_ID") or os.environ.get("CI_RUN_ID") or os.environ.get("GITHUB_RUN_ID") or "unknown",
        "builder_environment": "ci" if os.environ.get("CI") else "local-dev",
        "unsigned_apk_path": nfc(str(unsigned_apk.relative_to(ROOT))) if unsigned_apk else None,
        "unsigned_apk_sha256": sha256_file(unsigned_apk) if unsigned_apk else None,
        "release_apk_path": nfc(str(root_apk.relative_to(ROOT))) if root_apk else None,
        "release_apk_sha256": sha256_file(root_apk) if root_apk else None,
        "release_apk_idsig_sha256": sha256_file(Path(str(root_apk) + ".idsig")) if root_apk and Path(str(root_apk) + ".idsig").is_file() else None,
        "signing_stage": signing_stage,
        "signature_scheme": signature_scheme,
        "sbom_sha256": sha256_file(sbom) if sbom.is_file() else None,
        "source_archive_sha256": {nfc(str(a.relative_to(ROOT))): sha256_file(a) for a in archives if a.is_file()},
    }
    provenance_path = ROOT / "BUILD_PROVENANCE.json"
    provenance_path.write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    # DAG 末端：RELEASE_ARTIFACT_MANIFEST（最后生成；不参与 provenance，§13 无循环）
    release_notes = ROOT / version_source["release_notes_file"]
    lines = artifact_manifest_lines(
        provenance, version, release_notes, archives, root_apk, unsigned_apk
    )
    (ROOT / "RELEASE_ARTIFACT_MANIFEST.sha256").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )

    print(json.dumps({
        "commit": provenance["git_commit"][:12],
        "release_type": provenance["release_type"],
        "source_tree_sha256": provenance["source_tree_sha256"][:16],
        "release_apk": provenance["release_apk_path"],
        "signing_stage": signing_stage,
        "signature_scheme": signature_scheme,
        "artifact_entries": len(lines),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
