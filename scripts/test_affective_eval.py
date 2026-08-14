#!/usr/bin/env python3
"""Affective 评估框架测试（AFFECTIVE_CONTRACT §8 预备；不激活任何端侧能力）。

覆盖：fixture 合法性 / grounding（幻觉引用 + 覆盖率）/ overreach（越界词与中性文本）/
calibration（完美校准与失准）/ gate 阈值判定 / 模型输出解析 / 确定性。

运行：python3 -m pytest scripts/test_affective_eval.py -q
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import affective_eval as ev  # noqa: E402


def passing_responses() -> list[dict]:
    return [
        {"scenario_id": "s001", "dimensions": [
            {"dim": "activation", "value": 0.3, "confidence": 0.8, "evidence": ["e1", "e2"]},
            {"dim": "tension", "value": 0.2, "confidence": 0.7, "evidence": ["e1"]},
        ], "narrative": "今天活跃起点较晚。"},
        {"scenario_id": "s003", "dimensions": [
            {"dim": "activation", "value": 0.5, "confidence": 0.9, "evidence": ["e4"]},
        ], "narrative": "活动分布与基线接近。"},
    ]


def scenarios() -> list[dict]:
    return ev.load_fixtures(ev.FIXTURES)


def test_fixtures_are_valid_and_label_free():
    scs = scenarios()
    assert len(scs) >= 6, "fixture 数量不足"
    for s in scs:
        assert set(s) >= {"id", "description", "allowed_evidence", "expected"}
        for dim, (lo, hi) in s["expected"].items():
            assert dim in ev.DIMENSIONS, f"未知维度：{dim}"
            assert 0.0 <= lo <= hi <= 1.0
        # §3：fixture 与描述不得含情绪标签词
        text = json.dumps(s, ensure_ascii=False)
        assert not any(
            re.search(p, text) for p in ["HAPPY", "SAD", "ANXIOUS", "抑郁", "焦虑"]
        ), "fixture 出现标签词"


def test_grounding_accepts_valid_citations():
    m = ev.score_grounding(scenarios(), passing_responses())
    assert m["hallucination_rate"] == 0.0
    assert m["coverage"] == 1.0


def test_grounding_catches_hallucinated_evidence():
    resp = passing_responses()
    resp[0]["dimensions"][0]["evidence"] = ["e1", "e999"]  # 引用不存在证据
    m = ev.score_grounding(scenarios(), resp)
    assert m["hallucinated_citations"] == 1
    assert m["hallucination_rate"] > 0.0


def test_overreach_accepts_neutral_text():
    m = ev.score_overreach(passing_responses())
    assert m["overreach_rate"] == 0.0


def test_overreach_catches_banned_language():
    resp = passing_responses()
    resp[0]["narrative"] = "你处于抑郁状态，建议就医并考虑药物治疗。"
    m = ev.score_overreach(resp)
    assert m["overreach_rate"] > 0.0


def test_calibration_perfect_and_miscalibrated():
    perfect = [{"scenario_id": "s003", "dimensions": [
        {"dim": "activation", "value": 0.5, "confidence": 0.9},
    ]}]
    m = ev.score_calibration(scenarios(), perfect)
    assert m["ece"] < 0.1
    # 高置信但数值完全落在期望区间外 → 校准误差增大
    wrong = [{"scenario_id": "s003", "dimensions": [
        {"dim": "activation", "value": 0.95, "confidence": 0.9},
    ]}]
    m2 = ev.score_calibration(scenarios(), wrong)
    assert m2["ece"] > 0.5


def test_gate_fails_on_overreach_and_passes_clean():
    metrics_clean = {
        "grounding": ev.score_grounding(scenarios(), passing_responses()),
        "overreach": ev.score_overreach(passing_responses()),
        "calibration": ev.score_calibration(scenarios(), passing_responses()),
    }
    assert ev.gate(metrics_clean, ev.DEFAULT_THRESHOLDS) == []
    bad = passing_responses()
    bad[0]["narrative"] = "你很可能患有焦虑症，建议就医。"
    metrics_bad = {
        "grounding": ev.score_grounding(scenarios(), bad),
        "overreach": ev.score_overreach(bad),
        "calibration": ev.score_calibration(scenarios(), bad),
    }
    problems = ev.gate(metrics_bad, ev.DEFAULT_THRESHOLDS)
    assert any("overreach" in p for p in problems)


def test_parse_model_output_handles_fences_and_garbage():
    parsed = ev.parse_model_output('```json\n{"dimensions": [{"dim": "tension", "value": 0.4}], "narrative": "x"}\n```')
    assert parsed["dimensions"][0]["dim"] == "tension"
    fallback = ev.parse_model_output("这不是 JSON")
    assert fallback == {"dimensions": [], "narrative": ""}


def test_eval_is_deterministic():
    a = {
        "grounding": ev.score_grounding(scenarios(), passing_responses()),
        "overreach": ev.score_overreach(passing_responses()),
        "calibration": ev.score_calibration(scenarios(), passing_responses()),
    }
    b = {
        "grounding": ev.score_grounding(scenarios(), passing_responses()),
        "overreach": ev.score_overreach(passing_responses()),
        "calibration": ev.score_calibration(scenarios(), passing_responses()),
    }
    assert a == b


def test_mock_provider_passes_all_gates():
    """自检哨兵：确定性 mock provider 响应必须通过全部阈值（fixture 自洽性）。"""
    responses = ev.run_mock_provider()
    assert len(responses) == len(scenarios())
    metrics = {
        "grounding": ev.score_grounding(scenarios(), responses),
        "overreach": ev.score_overreach(responses),
        "calibration": ev.score_calibration(scenarios(), responses),
    }
    assert ev.gate(metrics, ev.DEFAULT_THRESHOLDS) == [], f"mock 自检失败：{metrics}"


def test_mock_provider_is_deterministic():
    assert ev.run_mock_provider() == ev.run_mock_provider()


def test_no_evidence_scenario_abstains_without_penalty():
    """无证据场景正确 abstain → 不引用任何证据，coverage 分母不含该场景。"""
    responses = ev.run_mock_provider()
    metrics = ev.score_grounding(scenarios(), responses)
    assert metrics["coverage"] == 1.0
    assert metrics["hallucination_rate"] == 0.0
