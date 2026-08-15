"""导出 QaPortraitMirror 跨语言黄金（backend compute_dimensions = 产品真值）。

语言中立 fixture（qa/reports/mirror_goldens/fixture.json）→ 本脚本 → golden.json。
Android 侧 QaPortraitMirrorGoldenTest 用同一 fixture 对拍 Kotlin mirror；
backend 侧 test_mirror_golden.py 保证提交的 golden 与当前实现零漂移。

用法：backend/.venv/bin/python backend/scripts/export_mirror_golden.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "qa" / "reports" / "mirror_goldens" / "fixture.json"
GOLDEN = ROOT / "qa" / "reports" / "mirror_goldens" / "golden.json"

sys.path.insert(0, str(ROOT / "backend"))
from app.services.portrait.dimensions import compute_dimensions  # noqa: E402


def export_cases() -> list[dict[str, Any]]:
    """fixture → [{name, dimensions}]（确定性；键排序由 json.dumps sort_keys 保证）。"""
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    cases: list[dict[str, Any]] = []
    for case in fixture["cases"]:
        cases.append(
            {
                "name": case["name"],
                "dimensions": compute_dimensions(case["today"], case["baseline_metrics"]),
            }
        )
    return cases


def main() -> int:
    golden = {
        "golden_version": 1,
        "source": "backend/app/services/portrait/dimensions.py::compute_dimensions",
        "cases": export_cases(),
    }
    GOLDEN.write_text(
        json.dumps(golden, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {GOLDEN} ({len(golden['cases'])} cases)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
