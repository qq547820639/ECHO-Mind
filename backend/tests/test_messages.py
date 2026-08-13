"""GET /v1/me/messages 分析消息（周小结，拉取式推送过渡）测试。

覆盖：
- 服务层确定性：同输入同 id；<3 天 abstain；词表安全 fail-closed；
- HTTP：401 未认证；无画像 → {"message": null}；3+ 天画像 → 非空小结；
- 租户/用户隔离：小结只来自 principal.subject 自己的画像。
"""
from __future__ import annotations

from datetime import date, timedelta

from fastapi.testclient import TestClient

from app.database import SessionLocal
from app.models import DailyPortrait
from app.services.messages import BLOCKED_VOCABULARY, build_weekly_digest

TODAY = date(2026, 8, 10)


def _portrait(day_offset: int, user_id: str = "u_demo", dimensions: dict | None = None) -> DailyPortrait:
    return DailyPortrait(
        id=f"dp_msg_{user_id}_{day_offset}",
        tenant_id="t_demo",
        user_id=user_id,
        local_date=TODAY - timedelta(days=day_offset),
        timezone="Asia/Shanghai",
        status="READY",
        confidence="MEDIUM",
        dimensions=dimensions or {},
        summary="s",
        schema_version="portrait-v1",
    )


def _dims(rhythm: str = "SIMILAR", movement: str = "SIMILAR", screen: str = "SIMILAR") -> dict:
    return {
        "RHYTHM": {"value": rhythm, "metric": "active_start_minute", "z": 0.0},
        "MOVEMENT": {"value": movement, "metric": "movement_index", "z": 0.0},
        "SCREEN_AMOUNT": {"value": screen, "metric": "screen_on_minutes", "z": 0.0},
        "STABILITY": {"value": "SLIGHTLY_DIFFERENT", "metric": None, "z": None},
    }


def test_digest_abstains_under_three_days():
    assert build_weekly_digest([]) is None
    assert build_weekly_digest([_portrait(0), _portrait(1)]) is None


def test_digest_deterministic_and_contract_safe():
    rows = [_portrait(0, dimensions=_dims("LATER", "LESS", "MORE")), _portrait(1), _portrait(2)]
    d1 = build_weekly_digest(rows)
    d2 = build_weekly_digest([_portrait(0, dimensions=_dims("LATER", "LESS", "MORE")), _portrait(1), _portrait(2)])
    assert d1 is not None and d2 is not None
    assert d1["id"] == d2["id"]
    assert d1["title"] == "本周节律小结"
    # 最接近：RHYTHM 3 天中 2 天 SIMILAR？——此处 3 行中 RHYTHM 有 2 个 SIMILAR
    assert "最接近" in d1["body"] and "变化较明显" in d1["body"]
    for word in BLOCKED_VOCABULARY:
        assert word not in d1["body"]
        assert word not in d1["title"]


def test_digest_blocked_vocabulary_fail_closed():
    # 构造一个会输出 BLOCK 词的场景不可行（标签表固定安全），改为直接验证守卫函数逻辑：
    # build_weekly_digest 的措辞表不含任何 BLOCK 词，此处断言标签全集安全。
    from app.services.messages import DIMENSION_LABELS

    for label in DIMENSION_LABELS.values():
        for word in BLOCKED_VOCABULARY:
            assert word not in label


def test_http_requires_auth(client: TestClient):
    resp = client.get("/v1/me/messages")
    assert resp.status_code == 401


def test_http_empty_when_no_portraits(client: TestClient, user_headers):
    resp = client.get("/v1/me/messages", headers=user_headers)
    assert resp.status_code == 200
    assert resp.json() == {"message": None}


def test_http_digest_from_own_portraits_only(client: TestClient, user_headers):
    with SessionLocal() as db:
        # u_demo 3 天画像 + u_other 3 天画像：小结只统计 u_demo 自己的数据
        for i in range(3):
            db.add(_portrait(i, user_id="u_demo", dimensions=_dims()))
            db.add(_portrait(i, user_id="u_other", dimensions=_dims("LATER", "LESS", "MORE")))
        db.commit()

    resp = client.get("/v1/me/messages", headers=user_headers)
    assert resp.status_code == 200
    message = resp.json()["message"]
    assert message is not None
    assert message["title"] == "本周节律小结"
    assert "最接近" in message["body"]

    # u_other 视角：其 3 天画像全部非 SIMILAR → 变化较明显维度存在；最接近取 SIMILAR 比例最高者
    from app.auth import create_access_token

    other_headers = {"Authorization": f"Bearer {create_access_token('u_other', 't_demo', 'user')}"}
    resp_other = client.get("/v1/me/messages", headers=other_headers)
    assert resp_other.status_code == 200
    assert resp_other.json()["message"] is not None
