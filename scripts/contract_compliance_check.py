#!/usr/bin/env python3
"""CONTRACT_COMPLIANCE 锚点存在性校验（ERA 84 / ADR-070）。

职责：解析 docs/contracts/CONTRACT_COMPLIANCE.md 中所有反引号路径
（.kt/.py/.md 结尾），逐项断言仓库内文件存在。
任一缺失 → 打印 FAIL 清单并 exit 1（CI source-integrity 执行，
防止对照表成为过期文档——禁止文档完成主义）。

用法：python scripts/contract_compliance_check.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
COMPLIANCE_PATH = REPO_ROOT / "docs" / "contracts" / "CONTRACT_COMPLIANCE.md"

# 表格内锚点路径（反引号包裹；路径以 .kt/.py/.md/.json/.yml 结尾才视为文件锚点）
_ANCHOR_RE = re.compile(r"`([A-Za-z0-9_./\-]+\.(?:kt|py|md|json|yml|yaml))`")


def main() -> int:
    text = COMPLIANCE_PATH.read_text(encoding="utf-8")
    candidates = sorted(set(_ANCHOR_RE.findall(text)))
    # 允许省略前缀的相对路径（如 `JourneyYearViewTest`）与无扩展名的引号文本跳过
    anchors = [c for c in candidates if "/" in c or c.endswith((".md", ".json"))]
    missing = []
    for rel in anchors:
        candidate = REPO_ROOT / rel
        if not candidate.exists():
            missing.append(rel)
    if missing:
        print("CONTRACT_COMPLIANCE 锚点缺失：")
        for rel in missing:
            print(f"  ✗ {rel}")
        return 1
    print(f"CONTRACT_COMPLIANCE 锚点校验 PASS：{len(anchors)} 个文件锚点全部存在")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
