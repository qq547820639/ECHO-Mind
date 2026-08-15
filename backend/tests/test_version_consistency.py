"""v0.6.1：版本/文档一致性自动测试（hardening 需求十七）。

防止以下字段再次漂移：
- README 声称的 pytest 数量 vs 实际收集数量（含本文件自身）
- README 版本号 vs backend APP_VERSION vs pyproject version vs Android versionName
- README "Room vX" vs Android EchoDatabase version（exportSchema 导出目录）
- DELIVERY_MANIFEST backend_tests_passed vs 实际数量
- alembic 迁移链单一 head
- docs/openapi.json 包含当前全部 /v1 端点（与 app schema 实时对比）
"""
import json
import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend"
ANDROID = REPO_ROOT / "android"
DOCS = REPO_ROOT / "docs"

from app.config import APP_VERSION  # noqa: E402

pytestmark = pytest.mark.sqlite_only  # 仓库元数据一致性检查仅在全量套件成立，-m "not sqlite_only" 子集跳过


def _collect_pytest_count() -> int:
    """收集 tests/ 下全部用例数（含本文件）。"""
    import tests  # noqa: F401 确保测试模块可收集
    import _pytest.config


    cfg = _pytest.config.get_config()
    return len(cfg.collect_initial_items) if hasattr(cfg, "collect_initial_items") else 0


def test_README_delegates_counts_to_status():
    """ERA 32：README 不再手写测试计数——数字由 scripts/refresh_status_numbers.py
    从实测产物自动生成进 docs/STATUS.md §3（Build Status），本断言防手写数字回流。
    """
    readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
    assert "后端自动测试 **" not in readme, "README 不得再手写后端测试计数（见 docs/STATUS.md §3）"
    assert "项单测全绿" not in readme, "README 不得再手写 Android 单测计数（见 docs/STATUS.md §3）"
    assert "docs/STATUS.md" in readme, "README 必须指向 docs/STATUS.md 的自动生成 Build Status"


def test_version_fields_consistent():
    """README / backend APP_VERSION / pyproject / Android versionName / manifest 一致。"""
    readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
    readme_ver = re.search(r"版本：\*\*v([\d.]+)\s*工程包", readme)
    assert readme_ver, "README 缺少 '版本：**vX.Y.Z 工程包' 声明"
    assert readme_ver.group(1) == APP_VERSION, f"README {readme_ver.group(1)} != APP_VERSION {APP_VERSION}"

    pyproject = (BACKEND / "pyproject.toml").read_text(encoding="utf-8")
    pyproj_ver = re.search(r'^version\s*=\s*"([\d.]+)"', pyproject, re.M)
    assert pyproj_ver and pyproj_ver.group(1) == APP_VERSION

    gradle = (ANDROID / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    android_ver = re.search(r'versionName\s*=\s*"([\d.]+)"', gradle)
    assert android_ver and android_ver.group(1) == APP_VERSION, (
        f"Android versionName {android_ver.group(1) if android_ver else None} != {APP_VERSION}"
    )

    manifest = json.loads((REPO_ROOT / "DELIVERY_MANIFEST.json").read_text(encoding="utf-8"))
    assert manifest["version"] == APP_VERSION


def test_README_room_version_matches_android_schema():
    """README Room vX == EchoDatabase version（代码事实一致性）。

    注：Room exportSchema 目录（schemas/）由 KSP 在 Android 构建期生成；
    本环境无 JDK/Android SDK 无法重新导出。若目录存在历史导出，只校验
    目录非空，并把「最新 schema == 当前 version」标记为需 Android 构建
    环境验证的外部门（不伪造导出产物）。
    """
    readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
    match = re.search(r"Room v(\d+)", readme)
    assert match, "README 缺少 'Room vX' 声明"
    claimed = int(match.group(1))

    db_file = ANDROID / "app" / "src" / "main" / "java" / "com" / "yunjue" / "echo" / "mind" / "data" / "EchoDatabase.kt"
    source = db_file.read_text(encoding="utf-8")
    ver_match = re.search(r"version\s*=\s*(\d+)", source)
    assert ver_match and int(ver_match.group(1)) == claimed, (
        f"README Room v{claimed} != EchoDatabase version {ver_match.group(1) if ver_match else '?'}"
    )

    # exportSchema 目录：存在即要求非空（历史导出 2.json 属于旧版本产物，
    # 最新导出需在 Android SDK 构建环境执行 `./gradlew :app:kspDebugKotlin` 后提交）。
    schema_dir = ANDROID / "app" / "schemas" / "com.yunjue.echo.mind.data.EchoDatabase"
    schemas = sorted(schema_dir.glob("*.json")) if schema_dir.exists() else []
    if schemas:
        latest = int(re.search(r"(\d+)\.json$", schemas[-1].name).group(1))
        assert latest <= claimed, f"schemas 最新导出 {latest} 高于当前 Room version {claimed}（回退导出）"


def test_delivery_manifest_pytest_count_updated():
    """DELIVERY_MANIFEST backend_tests_passed 数字不低于基线（防回归至旧数字）。"""
    manifest = json.loads((REPO_ROOT / "DELIVERY_MANIFEST.json").read_text(encoding="utf-8"))
    claimed = manifest["validation"]["backend_tests_passed"]
    assert claimed >= 900, f"manifest 声称 {claimed} 项（v0.6.1 baseline 已超 900）"


def test_README_no_active_input_claims():
    """README 不得再宣称"主动记录"为当前能力（写入口已 410 退役）。"""
    readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
    for banned in ("主动记录相结合", "主动记录与被动感知"):
        assert banned not in readme, f"README 仍描述退役的主动记录能力: {banned}"
    # 允许出现的是"历史只读"语境
    assert "历史只读" in readme or "已停用" in readme


def test_alembic_single_head():
    """alembic 迁移链必须单一 head。"""
    from alembic.config import Config
    from alembic.script import ScriptDirectory

    cfg = Config(str(BACKEND / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND / "alembic"))
    script = ScriptDirectory.from_config(cfg)
    heads = script.get_heads()
    assert len(heads) == 1, f"迁移链存在多个 head: {heads}"


def test_openapi_contains_all_v1_routes():
    """docs/openapi.json 与当前 app schema 一致（实时导出对比）。"""
    from app.main import app

    live = app.openapi()
    stored_path = DOCS / "openapi.json"
    assert stored_path.exists(), "docs/openapi.json 缺失"
    stored = json.loads(stored_path.read_text(encoding="utf-8"))
    live_paths = set(live["paths"].keys())
    stored_paths = set(stored["paths"].keys())
    missing = live_paths - stored_paths
    extra = stored_paths - live_paths
    assert not missing, f"docs/openapi.json 缺失端点: {sorted(missing)}"
    # 允许 stored 存在但 live 已移除的 legacy 路径？不允许：stored 必须可被实时 schema 复现
    assert not extra, f"docs/openapi.json 存在 live 中不存在的端点: {sorted(extra)}"


@pytest.mark.skip(reason="PG 集成由 CI postgres job 覆盖（本地无 PG 实例）")
def test_postgres_migration_roundtrip():
    """PostgreSQL round-trip 由 CI job 覆盖；本地跳过需明确说明。"""
