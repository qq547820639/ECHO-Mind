"""画像 golden 消费方登记一致性门（P1-1）。

规范源 `qa/golden/portrait_vectors.json` 里的 `consumers` 是**机器可读的收口台账**：
- `wired` / `wired_via_generated_pair`：文件必须存在，且必须在内容里引用 golden（防"声明已接线、实际没接线"）；
- `not_wired`：必须填写 `blocked_by`（未收口不能是无声的），测试打印提醒但不失败——
  一旦把状态改成 `wired*` 而文件未真正引用，本门立即变红。

这样"镜像漂移"从祈祷变成红灯：改阈值 → 重生成规范源 → 所有已接线消费方同 commit 转红/转绿。
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
ROOT = BACKEND_ROOT.parent

_SCRIPT = BACKEND_ROOT / "scripts" / "generate_portrait_golden.py"
_spec = importlib.util.spec_from_file_location("generate_portrait_golden", _SCRIPT)
assert _spec and _spec.loader
gen = importlib.util.module_from_spec(_spec)
sys.modules["generate_portrait_golden"] = gen
_spec.loader.exec_module(gen)

WIRED_STATUSES = ("wired", "wired_via_generated_pair")
ALL_STATUSES = WIRED_STATUSES + ("not_wired",)

# 消费方文件里必须出现的引用锚点（任一命中即视为接线）
REFERENCE_ANCHORS = (
    "qa/golden/portrait_vectors.json",
    "qa/reports/mirror_goldens",
    "generate_portrait_golden",
)


def _canonical() -> dict:
    return json.loads(gen.CANONICAL.read_text(encoding="utf-8"))


def test_consumer_registry_is_coherent() -> None:
    canonical = _canonical()
    consumers = canonical.get("consumers")
    assert consumers, "规范源必须登记消费方（否则单一数据源无从约束）"

    seen: set[str] = set()
    pending: list[str] = []
    for entry in consumers:
        path = entry.get("path", "")
        status = entry.get("status", "")
        assert path, f"消费方登记缺 path：{entry}"
        assert path not in seen, f"消费方重复登记：{path}"
        seen.add(path)
        assert status in ALL_STATUSES, f"{path} 状态非法：{status}（允许 {ALL_STATUSES}）"
        assert entry.get("role"), f"{path} 缺 role 说明"

        target = ROOT / path
        assert target.exists(), f"登记为 {status} 的消费方文件不存在：{path}"

        if status in WIRED_STATUSES:
            body = target.read_text(encoding="utf-8")
            assert any(anchor in body for anchor in REFERENCE_ANCHORS), (
                f"{path} 声明已接线，但内容未引用 golden（锚点之一：{REFERENCE_ANCHORS}）"
            )
        else:
            assert entry.get("blocked_by"), (
                f"{path} 标记 not_wired 必须填写 blocked_by（未收口不得静默）"
            )
            pending.append(f"{path} → {entry['blocked_by']}")

    if pending:
        # 非失败：让"未收口"在每个 CI 运行里可见，但不阻塞主干。
        print("\n[portrait-golden] 尚未接线的消费方：")
        for item in pending:
            print(f"  · {item}")
