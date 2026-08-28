"""工作台（console）可用性自测回归（2026-08-28）。

覆盖自测中发现并修复的两个后端缺陷：
- D5：非法 cursor（不含 "_"）此前 500，注释承诺统一 422；
- D6：请求中间件对同一 ContextVar token 双重 reset，任何未处理异常都会
  二次抛 RuntimeError，把干净 500 变成无安全头的异常穿透。
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient


def test_malformed_cursor_without_separator_returns_422(client, professional_headers):
    """D5 回归：cursor 缺少 "_" 分隔符必须 422，而不是 500。"""
    resp = client.get(
        "/v1/escalations", params={"limit": 10, "cursor": "not-a-cursor"},
        headers=professional_headers,
    )
    assert resp.status_code == 422, resp.text
    assert resp.json()["detail"] == "invalid cursor"


@pytest.mark.parametrize("cursor", ["no-separator", "___", "x_y", "2026-13-99_abc"])
def test_invalid_cursor_matrix_is_422_not_500(client, professional_headers, cursor):
    """畸形 cursor 矩阵：全部 422（值非法 / 缺分隔符 / 多段 / 非法日期）。"""
    resp = client.get(
        "/v1/escalations", params={"limit": 10, "cursor": cursor},
        headers=professional_headers,
    )
    assert resp.status_code == 422, f"cursor={cursor!r} 应 422：{resp.status_code}"
    assert resp.json()["detail"] == "invalid cursor"


def test_unhandled_exception_returns_500_with_security_headers():
    """D6 回归：未处理异常必须返回带安全头的干净 500，而非二次异常穿透。

    修复前：except 分支先 reset 一次 ContextVar token，finally 又 reset 一次
    → RuntimeError 抛出，我们构造的 500 响应被丢弃，响应缺少安全头。
    """
    from app.main import app

    with TestClient(app, raise_server_exceptions=False) as tc:
        original = None
        from app.api import escalations as esc_mod

        def _boom(*_args, **_kwargs):
            raise RuntimeError("selftest-unhandled")

        original = esc_mod.count_recent_escalations
        esc_mod.count_recent_escalations = _boom
        try:
            resp = tc.post(
                "/v1/escalations",
                json={
                    "event_id": "selftest-evt-0001",
                    "user_id": "u_demo",
                    "trigger": "schedule_help",
                    "evidence_summary": "未处理异常回归测试",
                },
                headers={"Authorization": "Bearer " + _user_token()},
            )
        finally:
            esc_mod.count_recent_escalations = original

        assert resp.status_code == 500
        # 干净 500 的标志：安全头由我们的 except 分支写入
        assert resp.headers.get("X-Request-ID"), "缺少 X-Request-ID（响应被二次异常吞掉）"
        assert resp.headers.get("X-Content-Type-Options") == "nosniff"
        assert resp.headers.get("X-Frame-Options") == "DENY"
        assert resp.headers.get("Cache-Control") == "no-store"


def _user_token() -> str:
    from app.auth import create_access_token

    return create_access_token("u_demo", "t_demo", "user")
