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

validation 门禁值（T7-P2-2 诚实化）：alembic_roundtrip/contract_drift_check/
android_instrumentation 只接受对应 env 注入（ALEMBIC_ROUNDTRIP_RESULT /
CONTRACT_DRIFT_CHECK_RESULT / ANDROID_INSTRUMENTATION_RESULT），未注入如实输出
"not_run"；synthetic_safety_cases/content_packs_validated 由仓库实测推导。
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
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
    # ERA 32 R26：汇总全部 <testsuite>（与 refresh_status_numbers 一致）——
    # 此前只读首个 suite，多 suite junitxml 会静默漏计。
    suites = root.findall("testsuite")
    if not suites:
        suites = [root]
    tests = 0
    failures = 0
    skipped = 0
    for suite in suites:
        attrs = suite.attrib
        tests += int(attrs.get("tests", 0))
        failures += int(attrs.get("failures", 0)) + int(attrs.get("errors", 0))
        skipped += int(attrs.get("skipped", 0))
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


def _injected(env_name: str) -> str:
    """T7-P2-2 诚实化：validation 门禁值只接受 pipeline 注入；未注入时如实 not_run。

    替代此前硬编码 "passed"/"ci_emulator_gate"（--skip-preflight 下照样输出的假宣称）。
    """
    return os.environ.get(env_name, "not_run")


def _evidence_map() -> dict[str, str]:
    """P0-4（2026-08-28）：门禁结论必须可复现。

    `validation` 只给结论，"passed 是谁跑的、怎么跑的"无从判断——这正是
    "门禁存在 ≠ 门禁跑在交付上"的根源。这里把每个门禁的执行方式（命令 + 环境 +
    时间）作为 `validation_evidence` 一并写入清单；未提供的一律不出现（禁止伪造）。
    """
    spec = {
        "backend_tests": "BACKEND_TESTS_EVIDENCE",
        "alembic_roundtrip": "ALEMBIC_ROUNDTRIP_EVIDENCE",
        "contract_drift_check": "CONTRACT_DRIFT_CHECK_EVIDENCE",
        "postgresql_docker_integration": "POSTGRES_DOCKER_EVIDENCE",
        "android_gradle_build": "ANDROID_GRADLE_BUILD_EVIDENCE",
        "android_instrumentation": "ANDROID_INSTRUMENTATION_EVIDENCE",
        "static_checks": "STATIC_CHECKS_EVIDENCE",
        "portrait_golden": "PORTRAIT_GOLDEN_EVIDENCE",
        "repo_bloat_gate": "REPO_BLOAT_EVIDENCE",
    }
    return {key: value for key, env in spec.items() if (value := os.environ.get(env))}


def _git_branch() -> str:
    """branch 实测当前 git 分支（替代硬编码 "main"）。"""
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ROOT, text=True
        ).strip()
    except Exception:
        return "unknown"


def _git_commit() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip()
    except Exception:
        return "unknown"


def _git_is_dirty() -> bool:
    """P0-4：交付必须能自证"跑在干净树上"；脏树一律标 true（release-closure §15 禁 dirty）。"""
    try:
        out = subprocess.check_output(
            ["git", "status", "--porcelain"], cwd=ROOT, text=True
        )
        return bool(out.strip())
    except Exception:
        return True


def _safety_cases_count() -> int | str:
    """synthetic_safety_cases 实测语料行数（缺文件如实 not_run，不再硬编码 650）。"""
    corpus = ROOT / "safety-eval" / "red_team_corpus.v1.jsonl"
    if not corpus.is_file():
        return "not_run"
    return sum(1 for _ in corpus.open(encoding="utf-8"))


def _content_packs_count() -> int | str:
    """content_packs_validated 实测叶子包数（排除 MANIFEST.generated.json 生成物）。"""
    packs_dir = ROOT / "content-packs"
    if not packs_dir.is_dir():
        return "not_run"
    leaves = [p for p in packs_dir.rglob("*.json") if p.name != "MANIFEST.generated.json"]
    return len(leaves)


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
        "branch": _git_branch(),
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
            "synthetic_safety_cases": _safety_cases_count(),
            "content_packs_validated": _content_packs_count(),
            "alembic_roundtrip": _injected("ALEMBIC_ROUNDTRIP_RESULT"),
            "contract_drift_check": _injected("CONTRACT_DRIFT_CHECK_RESULT"),
            "android_instrumentation": _injected("ANDROID_INSTRUMENTATION_RESULT"),
            # 2026-09-13 PG 门禁解封：与 alembic 同为 pipeline 注入（未注入如实 not_run），
            # 不再硬编码 external_gate_not_run（本地 Docker PG 16 可用时该门禁可真实执行）。
            "postgresql_docker_integration": _injected("POSTGRES_DOCKER_INTEGRATION_RESULT"),
        },
        # P0-4：门禁结论 → 可复现执行证据（未执行的门禁不出现在此字典）
        "validation_evidence": _evidence_map(),
        "validation_environment": {
            "builder": os.environ.get("BUILDER_ENVIRONMENT", "local-dev"),
            "python_version": os.environ.get("BUILDER_PYTHON_VERSION") or platform.python_version(),
            "git_commit": _git_commit(),
            "git_dirty": _git_is_dirty(),
            "blocked_by": [
                item for item in (os.environ.get("DELIVERY_BLOCKED_BY") or "").split("|") if item
            ],
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
