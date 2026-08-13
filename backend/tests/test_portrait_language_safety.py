"""Milestone D 产品契约测试：所有生成文案不得包含被禁止词。

禁止词：焦虑、抑郁、孤独、压力过大、心理异常、风险、精神疾病、社交退缩、不健康。
遍历模板句子常量 + 各状态生成的实际文案（summary + highlights + facts）。
"""

from app.database import SessionLocal
from app.services.aggregates.timezone import local_day_window
from app.services.portrait import narrative as narrative_mod
from app.services.portrait.engine import generate_portrait

from test_portrait_golden import TODAY, make_baseline_rows, make_today

FORBIDDEN = ["焦虑", "抑郁", "孤独", "压力过大", "心理异常", "风险", "精神疾病", "社交退缩", "不健康"]


def _assert_clean(text: str) -> None:
    for term in FORBIDDEN:
        assert term not in text, f"文案包含禁止词「{term}」: {text}"


def test_template_sentences_are_clean():
    """遍历 narrative 模板句子与 headline 标签。"""
    for mapping in (
        narrative_mod.RHYTHM_SENTENCES,
        narrative_mod.MOVEMENT_SENTENCES,
        narrative_mod.SCREEN_AMOUNT_SENTENCES,
        narrative_mod.SCREEN_TIMING_SENTENCES,
        narrative_mod.DAY_STRUCTURE_SENTENCES,
        narrative_mod.STABILITY_SENTENCES,
    ):
        for sentence in mapping.values():
            _assert_clean(sentence)
    for tag in narrative_mod.HEADLINE_MAP.values():
        _assert_clean(tag)
    _assert_clean(narrative_mod.WARMING_UP_TEXT)
    _assert_clean(narrative_mod.LOW_CONFIDENCE_TEXT)


def _collect_rendered_texts(row) -> list[str]:
    texts = [row.summary or ""]
    texts.extend(row.highlights or [])
    for fact in row.facts or []:
        for value in fact.values():
            if isinstance(value, str):
                texts.append(value)
    return texts


def _run_scenario(db, *, baseline_days: int, pad: int, kwargs: dict):
    today = TODAY
    db.add_all(make_baseline_rows(today, days=baseline_days))
    utc_start, _ = local_day_window("Asia/Shanghai", today)
    db.add_all(make_today(utc_start, pad=pad, **kwargs))
    db.flush()
    return generate_portrait(db, tenant_id="t_demo", user_id="u_demo", local_date=today)


def test_rendered_ready_portrait_is_clean():
    with SessionLocal() as db:
        row = _run_scenario(db, baseline_days=28, pad=240,
                            kwargs=dict(active_start=540, movement=0.5, screen_minutes=300.0, late_minutes=120.0))
        for text in _collect_rendered_texts(row):
            _assert_clean(text)


def test_rendered_partial_data_is_clean():
    with SessionLocal() as db:
        row = _run_scenario(db, baseline_days=28, pad=95, kwargs=dict(active_start=540, movement=0.5))
        assert row.status == "PARTIAL_DATA"
        for text in _collect_rendered_texts(row):
            _assert_clean(text)


def test_rendered_early_baseline_is_clean():
    with SessionLocal() as db:
        row = _run_scenario(db, baseline_days=5, pad=240, kwargs=dict(active_start=495, movement=1.06))
        assert row.status == "EARLY_BASELINE"
        for text in _collect_rendered_texts(row):
            _assert_clean(text)


def test_rendered_warming_up_is_clean():
    with SessionLocal() as db:
        row = _run_scenario(db, baseline_days=0, pad=50, kwargs={})
        assert row.status == "WARMING_UP"
        for text in _collect_rendered_texts(row):
            _assert_clean(text)


def test_rendered_low_confidence_is_clean():
    with SessionLocal() as db:
        row = _run_scenario(db, baseline_days=28, pad=0, kwargs=dict(active_start=540, movement=0.5))
        assert row.status == "LOW_CONFIDENCE"
        for text in _collect_rendered_texts(row):
            _assert_clean(text)


def test_dimension_values_avoid_clinical_terms():
    """维度取值禁止 GOOD/BAD/HEALTHY/NORMAL/ABNORMAL（含 UNHEALTHY）。"""
    banned = {"GOOD", "BAD", "HEALTHY", "UNHEALTHY", "NORMAL", "ABNORMAL"}
    with SessionLocal() as db:
        row = _run_scenario(db, baseline_days=28, pad=240,
                            kwargs=dict(active_start=540, movement=0.5, screen_minutes=300.0, late_minutes=120.0))
        for entry in row.dimensions.values():
            assert entry["value"] not in banned, f"维度取值违规: {entry['value']}"
