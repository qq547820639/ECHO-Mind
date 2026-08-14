#!/usr/bin/env python3
"""Distribution 共享库（ERA 12.8 §5–§13）：确定性归档 / 安全解包 / 清单校验。

原则：
- 所有路径一律 Unicode NFC UTF-8；ZIP 非 ASCII 条目必须置 UTF-8 标志位（EFS 0x800）。
- 归档内容 = SOURCE_MANIFEST 文件集（git 受控源文件，排除生成物）；确定性排序 + 固定时间戳。
- 解包前逐条校验条目名：禁止绝对路径 / `..` / 非 UTF-8 / 非 NFC / 符号链接。

Python 3.9 兼容（本仓库 release_preflight 使用系统 python3.9）。
"""
from __future__ import annotations

import hashlib
import io
import os
import sys
import tarfile
import tempfile
import unicodedata
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, List, Optional, Tuple

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

#: 归档内嵌清单文件名（与仓库根 SOURCE_MANIFEST.sha256 同内容）。
MANIFEST_NAME = "SOURCE_MANIFEST.sha256"

#: Source Closure 必需文件（任何分发源码归档缺失其一 → 直接 FAIL）。
REQUIRED_SOURCES = [
    "README.md",
    "android/settings.gradle.kts",
    "android/app/build.gradle.kts",
    "android/app/src/main/AndroidManifest.xml",
    "android/app/src/main/java/com/yunjue/echo/mind/EchoMindApplication.kt",
    "android/app/src/main/java/com/yunjue/echo/mind/AppContainer.kt",
    "android/app/src/main/java/com/yunjue/echo/mind/runtime/EchoRuntimeCoordinator.kt",
    "backend/pyproject.toml",
    "backend/app/main.py",
    "scripts/build_source_archive.py",
    "scripts/verify_source_archive.py",
    ".github/workflows/source-integrity.yml",
]

#: 源码归档中禁止出现的路径片段 / 后缀（§9：build 产物、缓存、本地状态、APK 等）。
FORBIDDEN_PARTS = {
    ".git", ".gradle", ".venv", "__pycache__", ".pytest_cache", ".ruff_cache",
    ".mypy_cache", ".coverage", ".pytest_report", "build", ".idea", ".vscode",
    ".DS_Store", "local.properties",
}
FORBIDDEN_SUFFIXES = {".pyc", ".db", ".apk", ".idsig", ".keystore", ".jks"}


def nfc(path: str) -> str:
    return unicodedata.normalize("NFC", path)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def commit_timestamp_utc(repo: Path) -> datetime:
    """HEAD commit time（UTC，确定性归档时间戳来源）。"""
    import subprocess
    out = subprocess.run(
        ["git", "log", "-1", "--format=%ct", "HEAD"], cwd=repo,
        capture_output=True, check=True,
    ).stdout.decode().strip()
    return datetime.fromtimestamp(int(out), tz=timezone.utc)


def parse_manifest(text: str) -> dict:
    """解析 `<sha256>  <path>` 清单 → {rel: sha256}。路径全部 NFC 校验。"""
    entries = {}
    for line_no, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        if "  " not in line:
            raise ValueError(f"清单第 {line_no} 行缺少双空格分隔：{line!r}")
        digest, rel = line.split("  ", 1)
        if len(digest) != 64:
            raise ValueError(f"清单第 {line_no} 行非法 hash：{line!r}")
        if nfc(rel) != rel:
            raise ValueError(f"清单路径非 NFC（第 {line_no} 行）：{rel!r}")
        entries[rel] = digest
    return entries


def build_manifest(repo: Path) -> str:
    """以 git 受控源文件集生成清单文本（与 update_release_metadata 同规则）。"""
    import update_release_metadata as um
    lines = []
    for path in um.collect_source_files(repo):
        rel = nfc(str(path.relative_to(repo)))
        lines.append(f"{sha256_file(path)}  {rel}")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# 确定性归档写入
# ---------------------------------------------------------------------------

def _manifest_entry(repo: Path) -> Tuple[str, str, bytes]:
    text = build_manifest(repo).encode("utf-8")
    return MANIFEST_NAME, sha256_bytes(text), text


def _sorted_source_entries(repo: Path) -> List[Tuple[str, bytes]]:
    import update_release_metadata as um
    entries = []
    for path in um.collect_source_files(repo):
        rel = nfc(str(path.relative_to(repo)))
        entries.append((rel, path.read_bytes()))
    return sorted(entries)


def write_zip(archive_path: Path, prefix: str, files: Iterable[Tuple[str, bytes]],
              mtime: Optional[datetime] = None) -> None:
    """确定性 ZIP：NFC 路径、非 ASCII 条目置 UTF-8 标志（0x800）、固定时间戳。"""
    if mtime is None:
        mtime = datetime.now(timezone.utc)
    dt = mtime.timetuple()[:6]
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for rel, data in sorted(files):
            name = f"{prefix}/{nfc(rel)}"
            info = zipfile.ZipInfo(filename=name, date_time=dt)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3  # Unix
            info.external_attr = 0o100644 << 16
            if any(ord(ch) > 0x7F for ch in name):
                info.flag_bits |= 0x800  # UTF-8 filename flag（§7 关键）
            zf.writestr(info, data)


def write_tar_gz(archive_path: Path, prefix: str, files: Iterable[Tuple[str, bytes]],
                 mtime: Optional[datetime] = None) -> None:
    """确定性 tar.gz：PAX 格式（长路径/Unicode 安全）、固定时间戳、uid/gid=0。"""
    if mtime is None:
        mtime = datetime.now(timezone.utc)
    ts = int(mtime.timestamp())
    with tarfile.open(archive_path, "w:gz", format=tarfile.PAX_FORMAT) as tf:
        for rel, data in sorted(files):
            info = tarfile.TarInfo(name=f"{prefix}/{nfc(rel)}")
            info.size = len(data)
            info.mtime = ts
            info.mode = 0o644
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            tf.addfile(info, io.BytesIO(data))


# ---------------------------------------------------------------------------
# 安全解包
# ---------------------------------------------------------------------------

def _validate_entry_name(name: str, what: str) -> str:
    if name.startswith("/") or "\\" in name:
        raise ValueError(f"{what} 条目为绝对路径或含反斜杠：{name!r}")
    parts = name.split("/")
    if ".." in parts or any(not p or p in {".", ".."} for p in parts) or name in {"", "."}:
        raise ValueError(f"{what} 条目路径非法：{name!r}")
    try:
        name.encode("utf-8").decode("utf-8")
    except UnicodeError:
        raise ValueError(f"{what} 条目名非 UTF-8：{name!r}")
    if nfc(name) != name:
        raise ValueError(f"{what} 条目名非 NFC：{name!r}")
    return name


def extract_archive(archive_path: Path, dest: Path) -> List[str]:
    """解包 zip / tar.gz 到 dest，返回条目相对路径列表（安全校验，拒绝符号链接）。"""
    dest.mkdir(parents=True, exist_ok=True)
    entries: List[str] = []
    if archive_path.suffix == ".zip":
        with zipfile.ZipFile(archive_path) as zf:
            for info in zf.infolist():
                raw = info.filename
                if not (info.flag_bits & 0x800) and any(ord(ch) > 0x7F for ch in raw):
                    raise ValueError(
                        f"ZIP 条目未置 UTF-8 标志（#U/乱码路径根因）：{raw!r}")
                name = _validate_entry_name(raw, "ZIP")
                if info.is_dir():
                    (dest / name).mkdir(parents=True, exist_ok=True)
                    continue
                mode = (info.external_attr >> 16) & 0o170000
                if mode == 0o120000:
                    raise ValueError(f"ZIP 含符号链接，拒绝解包：{name}")
                out = dest / name
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(zf.read(info))
                entries.append(name)
    else:
        with tarfile.open(archive_path, "r:*") as tf:
            for member in tf.getmembers():
                name = _validate_entry_name(member.name, "TAR")
                if member.isdir():
                    (dest / name).mkdir(parents=True, exist_ok=True)
                    continue
                if member.issym() or member.islnk() or not member.isreg():
                    raise ValueError(f"TAR 含非普通文件条目，拒绝解包：{name}")
                payload = tf.extractfile(member)
                if payload is None:
                    raise ValueError(f"TAR 条目无法读取：{name}")
                out = dest / name
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(payload.read())
                entries.append(name)
    return entries


def safe_extract_to_temp(archive_path: Path) -> Path:
    return Path(tempfile.mkdtemp(prefix="echo-verify-"))


def find_top_prefix(entries: List[str]) -> str:
    """归档顶层目录名（如 echo-mind-portrait-core）。"""
    tops = {e.split("/", 1)[0] for e in entries if "/" in e}
    if len(tops) != 1:
        raise ValueError(f"归档顶层目录不唯一：{sorted(tops)}")
    return tops.pop()
