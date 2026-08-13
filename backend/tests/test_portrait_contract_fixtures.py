"""Portrait Core v0.7 跨端契约 fixture 校验（backend 侧）。

校验 backend/tests/fixtures/portrait_contract/ 下的 canonical JSON fixtures：
- 与 Android 侧 app/src/test/resources/portrait_contract/ 同源（变更需两端同步）；
- 结构必须满足契约：status 枚举 / dimensions 嵌套 {value, metric, z} /
  facts 四要素 / BaselineStatusOut 强类型 / 维度值禁止临床评价词。
"""
import json
from pathlib import Path

FIXTURES = Path(__file__).parent / "fixtures" / "portrait_contract"

STATUSES = {"WARMING_UP", "EARLY_BASELINE", "READY", "PARTIAL_DATA", "LOW_CONFIDENCE"}
CONFIDENCES = {"HIGH", "MEDIUM", "LOW"}
DIMENSION_KEYS = {
    "RHYTHM", "MOVEMENT", "SCREEN_AMOUNT", "SCREEN_TIMING", "DAY_STRUCTURE", "STABILITY",
}
FORBIDDEN_VALUES = {"GOOD", "BAD", "HEALTHY", "NORMAL", "ABNORMAL"}
FACT_FIELDS = {"label", "today_text", "baseline_text", "delta_text"}


def _load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def test_all_portrait_fixtures_have_valid_status_and_confidence():
    for name in ("ready_full", "warming_up", "early_baseline", "partial_data", "low_confidence",
                 "no_portrait", "dimensions_nested", "facts_explain", "timezone"):
        body = _load(f"{name}.json")
        assert body["status"] in STATUSES, f"{name}: status {body['status']} 非法"
        assert body["confidence"] in CONFIDENCES, f"{name}: confidence {body['confidence']} 非法"
        assert isinstance(body.get("date", ""), str) and body["date"], f"{name}: date 缺失"
        assert isinstance(body.get("dimensions", {}), dict), f"{name}: dimensions 应为对象"
        assert "timezone_used" in body, f"{name}: timezone_used 缺失"


def test_dimensions_keys_and_values_comply_with_contract():
    for name in ("ready_full", "partial_data", "dimensions_nested", "timezone"):
        body = _load(f"{name}.json")
        dims = body["dimensions"]
        assert set(dims.keys()) <= DIMENSION_KEYS, (
            f"{name}: 维度键 {sorted(set(dims.keys()) - DIMENSION_KEYS)} 不在契约内"
        )
        for key, entry in dims.items():
            assert set(entry.keys()) >= {"value", "metric", "z"}, f"{name}.{key}: 缺 value/metric/z"
            assert isinstance(entry["value"], str) and entry["value"], f"{name}.{key}: value 缺失"
            assert entry["value"] not in FORBIDDEN_VALUES, (
                f"{name}.{key}: value {entry['value']} 为禁止临床评价词"
            )
            assert entry["metric"] is None or isinstance(entry["metric"], str)
            assert entry["z"] is None or isinstance(entry["z"], (int, float))
            # STABILITY 额外字段 diff_count（整数）可存在，但不要求
            if "diff_count" in entry:
                assert isinstance(entry["diff_count"], int)


def test_partial_data_omits_screen_dimensions():
    body = _load("partial_data.json")
    dims = set(body["dimensions"].keys())
    assert "SCREEN_AMOUNT" not in dims and "SCREEN_TIMING" not in dims, (
        "PARTIAL_DATA 屏幕数据缺失时应省略 SCREEN_AMOUNT/SCREEN_TIMING（missing != irregular）"
    )
    assert {"RHYTHM", "MOVEMENT", "DAY_STRUCTURE", "STABILITY"} <= dims


def test_facts_four_fields():
    for name in ("ready_full", "partial_data", "facts_explain"):
        body = _load(f"{name}.json")
        for fact in body.get("facts", []):
            assert FACT_FIELDS <= set(fact.keys()), f"{name}: facts 缺四要素字段 {FACT_FIELDS - set(fact.keys())}"


def test_baseline_status_strong_types():
    body = _load("baseline_status.json")
    assert body["status"] in {"WARMING_UP", "EARLY_BASELINE", "BASELINE_READY"}
    assert isinstance(body["baseline_days"], int)
    assert body["bucket_usage"] in ("weekday", "weekend", "all_days")
    assert isinstance(body["today_coverage"], (int, float)), "today_coverage 应为 number"
    assert not isinstance(body["today_coverage"], bool)


def test_no_portrait_is_lightweight_view():
    body = _load("no_portrait.json")
    assert body["status"] == "WARMING_UP"
    assert body.get("headline", []) == []
    assert body.get("facts", []) == []
    assert body["dimensions"] == {}


def test_stability_values_follow_aggregate_enum():
    for name in ("ready_full", "partial_data", "dimensions_nested", "timezone"):
        body = _load(f"{name}.json")
        stability = body["dimensions"].get("STABILITY")
        if stability:
            assert stability["value"] in ("VERY_SIMILAR", "SLIGHTLY_DIFFERENT", "CLEARLY_DIFFERENT")
            assert stability["metric"] is None
            assert stability["z"] is None


def test_all_fixtures_are_valid_json():
    for path in FIXTURES.glob("*.json"):
        json.loads(path.read_text(encoding="utf-8"))  # 解析失败即抛异常
