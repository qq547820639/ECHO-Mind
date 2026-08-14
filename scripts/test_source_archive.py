#!/usr/bin/env python3
"""Distribution Integrity Test Suite（ERA 12.8 §7/§100）。

覆盖：
  - Unicode 路径（中文 / emoji / 空格 / 长路径）打包→解包逐字节一致，全 NFC；
  - ZIP 非 ASCII 条目置 UTF-8 标志（0x800）；
  - 确定性：同 commit 两次构建字节一致；
  - 负例：hash 篡改 / 清单外文件 / 缺失 runtime / NFD 条目 / 无 UTF-8 标志 → FAIL。

运行：python3 -m pytest scripts/test_source_archive.py -q
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import unicodedata
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import distribution as dist  # noqa: E402
import verify_source_archive as vsa  # noqa: E402

SCRIPTS = Path(__file__).resolve().parent


@pytest.fixture()
def fixture_repo(tmp_path: Path) -> Path:
    """临时 git 仓库：中文/emoji/空格/长路径 fixture + 必需骨架。"""
    repo = tmp_path / "repo"
    (repo / "scripts").mkdir(parents=True)
    (repo / "scripts" / "version_source.json").write_text(
        json.dumps({"release_version": "0.9.9", "android_version_name": "0.9.9",
                    "android_version_code": 1, "backend_package_version": "0.9.9",
                    "release_notes_file": "RELEASE_NOTES.md"}), encoding="utf-8")
    (repo / "README.md").write_text("fixture", encoding="utf-8")
    # Unicode fixture：中文名 + 空格 + 长路径（>100 字节，PAX 场景）
    (repo / "docs").mkdir()
    (repo / "docs" / "中文测试文档.txt").write_text("中文内容", encoding="utf-8")
    (repo / "dir with space").mkdir()
    (repo / "dir with space" / "空格 文件.txt").write_text("space", encoding="utf-8")
    long_dir = repo / "long" / ("段" * 40)
    long_dir.mkdir(parents=True)
    (long_dir / ("长文件名" + "续" * 30 + ".kt")).write_text("kotlin fixture", encoding="utf-8")
    (repo / "emoji").mkdir()
    (repo / "emoji" / "🎉.md").write_text("emoji", encoding="utf-8")
    # 必需 runtime 骨架（required-source 断言对象）
    runtime = repo / "android" / "app" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind" / "runtime"
    runtime.mkdir(parents=True)
    (runtime / "EchoRuntimeCoordinator.kt").write_text("class EchoRuntimeCoordinator", encoding="utf-8")
    (repo / "required.txt").write_text(
        "android/app/src/main/java/com/yunjue/echo/mind/runtime/EchoRuntimeCoordinator.kt\n"
        "README.md\n", encoding="utf-8")
    subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.email", "t@example.invalid"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=repo, check=True)
    subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
    subprocess.run(["git", "commit", "-qm", "fixture"], cwd=repo, check=True)
    return repo


def build_archive(repo: Path, out: Path) -> Path:
    subprocess.run(
        [sys.executable, str(SCRIPTS / "build_source_archive.py"),
         "--repo", str(repo), "--out-dir", str(out)],
        check=True, capture_output=True, text=True,
    )
    return out / "ECHO_Mind_PortraitCore_v0.9.9.zip"


def verify(repo: Path, archive: Path) -> int:
    return subprocess.run(
        [sys.executable, str(SCRIPTS / "verify_source_archive.py"),
         str(archive), "--repo", str(repo), "--required", str(repo / "required.txt")],
        capture_output=True, text=True,
    ).returncode


def test_unicode_roundtrip_nfc_and_utf8_flag(fixture_repo: Path, tmp_path: Path) -> None:
    out = tmp_path / "out"
    archive = build_archive(fixture_repo, out)
    with zipfile.ZipFile(archive) as zf:
        names = zf.namelist()
    chinese = [n for n in names if "中文" in n]
    assert chinese, "中文条目应存在"
    for n in chinese:
        assert unicodedata.normalize("NFC", n) == n, f"条目名非 NFC：{n!r}"
    non_ascii = [n for n in names if any(ord(c) > 0x7F for c in n)]
    assert non_ascii
    with zipfile.ZipFile(archive) as zf:
        for n in non_ascii:
            assert zf.getinfo(n).flag_bits & 0x800, f"缺少 UTF-8 标志：{n!r}"
    assert verify(fixture_repo, archive) == 0


def test_roundtrip_content_and_long_paths(fixture_repo: Path, tmp_path: Path) -> None:
    out = tmp_path / "out"
    archive = build_archive(fixture_repo, out)
    tmp = dist.safe_extract_to_temp(archive)
    try:
        dist.extract_archive(archive, tmp)
        top = tmp / "echo-mind-portrait-core"
        assert (top / "docs" / "中文测试文档.txt").read_text(encoding="utf-8") == "中文内容"
        assert (top / "dir with space" / "空格 文件.txt").read_text(encoding="utf-8") == "space"
        long_files = [p for p in top.rglob("*.kt") if p.name.startswith("长文件名")]
        assert long_files and long_files[0].read_text(encoding="utf-8") == "kotlin fixture"
        assert (top / "emoji" / "🎉.md").is_file()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_deterministic_same_commit(fixture_repo: Path, tmp_path: Path) -> None:
    a = build_archive(fixture_repo, tmp_path / "a")
    b = build_archive(fixture_repo, tmp_path / "b")
    assert a.read_bytes() == b.read_bytes(), "同 commit 两次构建应字节一致"


def test_hash_tamper_fails(fixture_repo: Path, tmp_path: Path) -> None:
    archive = build_archive(fixture_repo, tmp_path / "out")
    tampered = tmp_path / "tampered.zip"
    with zipfile.ZipFile(archive) as zin, zipfile.ZipFile(tampered, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info)
            if info.filename.endswith("README.md"):
                data = b"tampered content"
            zout.writestr(info, data)
    assert verify(fixture_repo, tampered) == 1


def test_unexpected_file_fails(fixture_repo: Path, tmp_path: Path) -> None:
    archive = build_archive(fixture_repo, tmp_path / "out")
    tampered = tmp_path / "extra.zip"
    with zipfile.ZipFile(archive) as zin, zipfile.ZipFile(tampered, "w") as zout:
        for info in zin.infolist():
            zout.writestr(info, zin.read(info))
        zout.writestr("echo-mind-portrait-core/unexpected.bin", b"x")
    assert verify(fixture_repo, tampered) == 1


def test_missing_runtime_fails(fixture_repo: Path, tmp_path: Path) -> None:
    archive = build_archive(fixture_repo, tmp_path / "out")
    tampered = tmp_path / "no-runtime.zip"
    with zipfile.ZipFile(archive) as zin, zipfile.ZipFile(tampered, "w") as zout:
        for info in zin.infolist():
            if "EchoRuntimeCoordinator.kt" in info.filename:
                continue
            zout.writestr(info, zin.read(info))
    assert verify(fixture_repo, tampered) == 1


def test_nfd_entry_name_fails(fixture_repo: Path, tmp_path: Path) -> None:
    archive = build_archive(fixture_repo, tmp_path / "out")
    nfd = tmp_path / "nfd.zip"
    with zipfile.ZipFile(archive) as zin, zipfile.ZipFile(nfd, "w") as zout:
        for info in zin.infolist():
            if "中文" in info.filename:
                # 改写为 NFD（é 组合形式：U+0065 U+0301），保留 UTF-8 标志
                new_name = info.filename.replace("中文", "中\u0301文")
                info.filename = new_name
                info.flag_bits |= 0x800
            zout.writestr(info, zin.read(info))
    assert verify(fixture_repo, nfd) == 1


def _clear_utf8_flag(src: Path, dst: Path, needle: str) -> None:
    """模拟历史坏 ZIP：对含 needle 的条目清除 local/central header 的 UTF-8 标志（0x800）。

    stdlib zipfile 写入时会自动重设该标志，因此直接在字节层面改写两个 header 的
    flag 字段（local: PK\\x03\\x04 +6；central: PK\\x01\\x02 +8）。
    """
    data = bytearray(src.read_bytes())
    name_bytes = needle.encode("utf-8")
    with zipfile.ZipFile(src) as zf:
        offsets = [i.header_offset for i in zf.infolist() if needle in i.filename]
    for off in offsets:
        if data[off:off + 4] == b"PK\x03\x04":
            flags = int.from_bytes(data[off + 6:off + 8], "little")
            data[off + 6:off + 8] = (flags & ~0x800).to_bytes(2, "little")
    pos = 0
    while True:
        pos = data.find(b"PK\x01\x02", pos)
        if pos == -1:
            break
        nlen = int.from_bytes(data[pos + 28:pos + 30], "little")
        if data[pos + 46:pos + 46 + nlen] == name_bytes:
            flags = int.from_bytes(data[pos + 8:pos + 10], "little")
            data[pos + 8:pos + 10] = (flags & ~0x800).to_bytes(2, "little")
        pos += 4
    dst.write_bytes(bytes(data))


def test_missing_utf8_flag_fails(fixture_repo: Path, tmp_path: Path) -> None:
    archive = build_archive(fixture_repo, tmp_path / "out")
    noflag = tmp_path / "noflag.zip"
    _clear_utf8_flag(archive, noflag, "中文测试文档")
    # 无 UTF-8 标志 → 校验必须拒绝（cp437 乱码 / #Uxxxx 路径变异的根因）
    assert verify(fixture_repo, noflag) == 1


def test_manifest_excludes_build_and_artifacts(fixture_repo: Path, tmp_path: Path) -> None:
    out = tmp_path / "out"
    build_archive(fixture_repo, out)
    manifest = dist.build_manifest(fixture_repo)
    for line in manifest.splitlines():
        rel = line.split("  ", 1)[1]
        assert "build" not in Path(rel).parts
        assert Path(rel).suffix not in {".apk", ".db", ".pyc", ".idsig"}


def test_verify_standalone_mode(fixture_repo: Path, tmp_path: Path) -> None:
    """离线验证：不需要 git checkout，仅依赖归档内嵌清单。"""
    archive = build_archive(fixture_repo, tmp_path / "out")
    rc = subprocess.run(
        [sys.executable, str(SCRIPTS / "verify_source_archive.py"),
         str(archive), "--standalone", "--required", str(fixture_repo / "required.txt")],
        capture_output=True, text=True,
    ).returncode
    assert rc == 0
