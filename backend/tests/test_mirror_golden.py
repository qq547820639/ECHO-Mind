"""跨语言黄金门（backend 侧）：规范源 == backend compute_dimensions，且生成物是规范源的真投影。

P1-1 收口（2026-08-28）：维度语义的金数据源收敛为**一份**
`qa/golden/portrait_vectors.json`（输入 + 期望输出同源），
`qa/reports/mirror_goldens/{fixture,golden}.json` 降级为生成物（Android 侧消费）。

本测试锁定三件事：
1. 规范源里的 expected_dimensions 必须等于当前 compute_dimensions 输出（backend = 产品真值）；
2. 生成物必须与规范源逐字节一致（禁止手工编辑生成物造成两端镜像分叉）；
3. 有意漂移必须在规范源里显式登记（否则后人无从判断"不一致"是缺陷还是设计）。

若 backend 语义**有意**变更：
1. 改 `backend/app/services/portrait/dimensions.py`；
2. 跑 `backend/.venv/bin/python backend/scripts/generate_portrait_golden.py --update`；
3. 同步两端镜像实现（QaPortraitMirror / LocalPortraitEngine）并让各自测试转绿。
禁止只改一侧（同源演化，禁止分叉）。
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

from app.services.portrait.dimensions import compute_dimensions

# 生成器不是包内模块（backend/scripts/），按文件路径加载。
_BACKEND_ROOT = Path(__file__).resolve().parents[1]
_SCRIPT = _BACKEND_ROOT / "scripts" / "generate_portrait_golden.py"
_spec = importlib.util.spec_from_file_location("generate_portrait_golden", _SCRIPT)
assert _spec and _spec.loader
gen = importlib.util.module_from_spec(_spec)
sys.modules["generate_portrait_golden"] = gen
_spec.loader.exec_module(gen)

CANONICAL = gen.CANONICAL


def _canonical() -> dict:
    return json.loads(CANONICAL.read_text(encoding="utf-8"))


def test_canonical_is_single_source_of_truth() -> None:
    canonical = _canonical()
    assert canonical["schema_version"] == gen.SCHEMA_VERSION
    assert canonical["source_of_truth"] == gen.SOURCE_OF_TRUTH
    assert canonical["cases"], "规范源必须至少包含一个 case"


def test_committed_golden_matches_backend() -> None:
    canonical = _canonical()
    for case in canonical["cases"]:
        actual = compute_dimensions(case["today"], case["baseline_metrics"])
        assert case["expected_dimensions"] == actual, (
            f"case[{case['name']}] 与 backend compute_dimensions 漂移："
            "跑 backend/scripts/generate_portrait_golden.py --update 并同步两端镜像"
        )


def test_generated_mirror_pair_matches_canonical() -> None:
    """生成物必须是规范源的真投影——防止有人手改 fixture/golden 绕过单一数据源。"""
    canonical = _canonical()
    fixture_text, golden_text = gen.build_mirror_pair(canonical)
    assert gen.MIRROR_FIXTURE.read_text(encoding="utf-8") == fixture_text, (
        f"{gen.MIRROR_FIXTURE} 与规范源不一致：跑 generate_portrait_golden.py 重新同步"
    )
    assert gen.MIRROR_GOLDEN.read_text(encoding="utf-8") == golden_text, (
        f"{gen.MIRROR_GOLDEN} 与规范源不一致：跑 generate_portrait_golden.py 重新同步"
    )


def test_declared_divergences_are_documented() -> None:
    """有意漂移必须留痕：id / scope / description / recorded_at 齐全，且 id 唯一。"""
    canonical = _canonical()
    entries = canonical.get("declared_divergences", [])
    assert entries, "若确已无有意漂移，请显式改为空数组并在评审记录中说明"
    ids = [e["id"] for e in entries]
    assert len(ids) == len(set(ids)), f"declared_divergences id 重复：{ids}"
    for entry in entries:
        for field in ("id", "scope", "description", "recorded_at"):
            assert entry.get(field), f"declared_divergences[{entry.get('id')}] 缺 {field}"


def test_golden_files_live_only_in_declared_locations() -> None:
    """单一数据源硬门：维度语义 golden 只允许存在于规范源 + 其生成物两处。"""
    root = CANONICAL.parents[2]
    allowed = {
        CANONICAL.resolve(),
        gen.MIRROR_FIXTURE.resolve(),
        gen.MIRROR_GOLDEN.resolve(),
    }
    strays = [
        p for p in (root / "qa").rglob("*golden*.json")
        if p.resolve() not in allowed and "build" not in p.parts
    ]
    assert not strays, f"发现未登记的维度 golden 文件（应并入规范源或删除）：{[str(p) for p in strays]}"
