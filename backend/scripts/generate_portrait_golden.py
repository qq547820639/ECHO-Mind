"""画像维度语义 golden **单一数据源**生成器（P1-1 收口，2026-08-28）。

背景：维度语义曾有三份实现（backend ``dimensions.py`` / Android ``LocalPortraitEngine`` /
Android ``QaPortraitMirror``），golden 用例散落在三个测试文件里，改一处阈值要人工同步三处。

收口后的模型：

    qa/golden/portrait_vectors.json   ← 唯一手写/重生成入口（输入 + 期望输出同源）
        │  由本脚本校验/重生成（`--update`）
        │
        ├─► qa/reports/mirror_goldens/fixture.json   （生成物，Android 侧消费）
        └─► qa/reports/mirror_goldens/golden.json    （生成物，Android 侧消费）

- 真值永远是 backend ``compute_dimensions``：``expected_dimensions`` 与实现不符即为漂移；
- 生成物是规范源的**投影**，`--check` 模式下与规范源逐字节比较，禁止手工编辑；
- Android 侧读生成物，故本轮无需改动 Kotlin（也无需 Android SDK 即可完成收敛）。

用法：
    backend/.venv/bin/python backend/scripts/generate_portrait_golden.py            # 校验 + 同步生成物
    backend/.venv/bin/python backend/scripts/generate_portrait_golden.py --update    # 语义变更后重生成期望值
    backend/.venv/bin/python backend/scripts/generate_portrait_golden.py --check     # 只校验，不写文件
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
CANONICAL = ROOT / "qa" / "golden" / "portrait_vectors.json"
MIRROR_DIR = ROOT / "qa" / "reports" / "mirror_goldens"
MIRROR_FIXTURE = MIRROR_DIR / "fixture.json"
MIRROR_GOLDEN = MIRROR_DIR / "golden.json"

sys.path.insert(0, str(ROOT / "backend"))
from app.services.portrait.dimensions import compute_dimensions  # noqa: E402

SCHEMA_VERSION = "portrait-vectors-v1"
SOURCE_OF_TRUTH = "backend/app/services/portrait/dimensions.py::compute_dimensions"

# Android 侧消费方登记。状态枚举：wired / wired_via_generated_pair / not_wired。
# not_wired 必须填写 blocked_by（否则 verify 失败），且会被 check_consumers 报告为未收口。
CONSUMERS: list[dict[str, str]] = [
    {
        "path": "backend/tests/test_mirror_golden.py",
        "role": "backend 真值零漂移门",
        "status": "wired",
    },
    {
        "path": "backend/tests/test_golden_single_source.py",
        "role": "单一数据源与消费方登记一致性门",
        "status": "wired",
    },
    {
        "path": "android/feature/qa/src/test/java/com/yunjue/echo/mind/qa/QaPortraitMirrorGoldenTest.kt",
        "role": "QaPortraitMirror 跨语言对拍",
        "status": "wired_via_generated_pair",
    },
    {
        "path": "android/app/src/test/java/com/yunjue/echo/mind/localportrait/LocalPortraitGoldenTest.kt",
        "role": "LocalPortraitEngine 端侧镜像对拍（当前用例硬编码在 Kotlin 内，未消费共享向量）",
        "status": "not_wired",
        "blocked_by": "BLOCKED_ENV_ANDROID_SDK（本工作区无 Android SDK，Kotlin 改动不可编译验证；接线补丁见 docs/architecture/2026-08-28-end-to-end-delivery.md §P1-1）",
    },
]

# 有意漂移登记：镜像与真值不一致但**经评审确认**的分支，必须在规范源里显式留痕，
# 避免后人误判为缺陷或悄悄放宽断言。
DECLARED_DIVERGENCES: list[dict[str, str]] = [
    {
        "id": "local_warming_up_extra_fact",
        "scope": "LocalPortraitEngine.kt（仅本地模式生效）",
        "description": (
            "v0.7.4 UX 有意让端侧第 1 天（WARMING_UP）在 summary 后附加一句事实句，"
            "服务端保持固定文案。两端 summary 不再逐字节一致；维度（dimensions）仍逐字段一致，"
            "故本 golden 只锁定维度语义，不锁定 summary 文案。"
        ),
        "recorded_at": "2026-08-28",
    },
    {
        "id": "screen_timing_quantity_vs_time_wording",
        "scope": "dimensions.py SCREEN_TIMING / LocalPortraitEngine.kt:318-323",
        "description": (
            "驱动指标是晚间屏幕分钟数（量），标签却用 EARLIER/LATER（时间词）。"
            "已登记为产品待裁定项（LEDGER T3-P2-4），未改语义；golden 锁定当前行为，"
            "裁定后须 --update 重生成并同步两端。"
        ),
        "recorded_at": "2026-08-28",
    },
]


def _dump(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def load_canonical() -> dict[str, Any]:
    if not CANONICAL.exists():
        raise SystemExit(
            f"规范源不存在：{CANONICAL}（首次生成请从 qa/reports/mirror_goldens/fixture.json 迁移）"
        )
    return json.loads(CANONICAL.read_text(encoding="utf-8"))


def compute_case_dimensions(case: dict[str, Any]) -> dict[str, Any]:
    return compute_dimensions(case["today"], case["baseline_metrics"])


def verify(canonical: dict[str, Any]) -> list[str]:
    """返回漂移描述列表（空 = 零漂移）。"""
    problems: list[str] = []
    if canonical.get("schema_version") != SCHEMA_VERSION:
        problems.append(f"schema_version 应为 {SCHEMA_VERSION}，实为 {canonical.get('schema_version')}")
    if canonical.get("source_of_truth") != SOURCE_OF_TRUTH:
        problems.append("source_of_truth 与生成器声明不一致")
    for consumer in canonical.get("consumers", []):
        if consumer.get("status") == "not_wired" and not consumer.get("blocked_by"):
            problems.append(f"消费方 {consumer.get('path')} 标记 not_wired 但未填写 blocked_by")
    for case in canonical.get("cases", []):
        actual = compute_case_dimensions(case)
        expected = case.get("expected_dimensions")
        if expected != actual:
            problems.append(f"case[{case.get('name')}] expected_dimensions 与 compute_dimensions 输出不一致")
    return problems


def build_mirror_pair(canonical: dict[str, Any]) -> tuple[str, str]:
    """规范源 → (fixture.json, golden.json) 投影（形状与历史一致，Android 侧零改动）。"""
    fixture_cases = []
    golden_cases = []
    for case in canonical["cases"]:
        fixture_cases.append({
            "name": case["name"],
            "$comment": case.get("comment", ""),
            "today": case["today"],
            "baseline_metrics": case["baseline_metrics"],
        })
        golden_cases.append({
            "name": case["name"],
            "dimensions": case["expected_dimensions"],
        })
    fixture = {
        "$comment": (
            "生成物，禁止手工编辑。规范源：qa/golden/portrait_vectors.json；"
            "由 backend/scripts/generate_portrait_golden.py 投影生成。"
            "today = 当日聚合字段；baseline_metrics = 后端 personal_baselines.metrics 同构。"
        ),
        "cases": fixture_cases,
    }
    golden = {
        "golden_version": 1,
        "source": SOURCE_OF_TRUTH,
        "generated_from": "qa/golden/portrait_vectors.json",
        "cases": golden_cases,
    }
    return _dump(fixture), _dump(golden)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update", action="store_true", help="按当前实现重写 expected_dimensions")
    parser.add_argument("--check", action="store_true", help="只校验，不写任何文件")
    args = parser.parse_args()

    canonical = load_canonical()

    if args.update:
        for case in canonical["cases"]:
            case["expected_dimensions"] = compute_case_dimensions(case)
        canonical["consumers"] = CONSUMERS
        canonical["declared_divergences"] = DECLARED_DIVERGENCES
        canonical.setdefault("schema_version", SCHEMA_VERSION)
        canonical["source_of_truth"] = SOURCE_OF_TRUTH
        if not args.check:
            CANONICAL.write_text(_dump(canonical), encoding="utf-8")

    problems = verify(canonical)
    if problems:
        print("画像 golden 漂移：")
        for p in problems:
            print(f"  ✗ {p}")
        print(f"修复：{Path(__file__).name} --update（语义变更须同步两端镜像）")
        return 1

    fixture_text, golden_text = build_mirror_pair(canonical)
    if args.check:
        if MIRROR_FIXTURE.read_text(encoding="utf-8") != fixture_text:
            print(f"✗ 生成物与规范源不一致：{MIRROR_FIXTURE}")
            return 1
        if MIRROR_GOLDEN.read_text(encoding="utf-8") != golden_text:
            print(f"✗ 生成物与规范源不一致：{MIRROR_GOLDEN}")
            return 1
        print(f"PORTRAIT GOLDEN OK：{len(canonical['cases'])} cases，规范源与生成物零漂移")
        return 0

    MIRROR_DIR.mkdir(parents=True, exist_ok=True)
    MIRROR_FIXTURE.write_text(fixture_text, encoding="utf-8")
    MIRROR_GOLDEN.write_text(golden_text, encoding="utf-8")
    print(
        f"PORTRAIT GOLDEN OK：{len(canonical['cases'])} cases；"
        f"已同步 {MIRROR_FIXTURE.name} / {MIRROR_GOLDEN.name}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
