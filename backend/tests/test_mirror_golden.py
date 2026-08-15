"""跨语言黄金门（backend 侧）：提交的 golden.json 必须等于当前 compute_dimensions 输出。

backend 维度语义是产品真值；Android 的 QaPortraitMirror 用它逐字段对拍。
若 backend 语义**有意**变更：
1. 重跑 `backend/.venv/bin/python backend/scripts/export_mirror_golden.py` 更新 golden.json；
2. 同步 QaPortraitMirror（android/feature/qa）并让 QaPortraitMirrorGoldenTest 转绿。
禁止只改一侧（同源演化，禁止分叉）。
"""

from __future__ import annotations

import json
from pathlib import Path

from app.services.portrait.dimensions import compute_dimensions

FIXTURE = Path(__file__).resolve().parents[2] / "qa" / "reports" / "mirror_goldens" / "fixture.json"
GOLDEN = Path(__file__).resolve().parents[2] / "qa" / "reports" / "mirror_goldens" / "golden.json"


def test_committed_golden_matches_backend() -> None:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    golden = json.loads(GOLDEN.read_text(encoding="utf-8"))
    cases = [
        {"name": case["name"], "dimensions": compute_dimensions(case["today"], case["baseline_metrics"])}
        for case in fixture["cases"]
    ]
    assert golden["golden_version"] == 1
    assert golden["cases"] == cases, (
        "mirror_goldens/golden.json 与 backend compute_dimensions 漂移："
        "重跑 backend/scripts/export_mirror_golden.py 并同步 QaPortraitMirror"
    )
