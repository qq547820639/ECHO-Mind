"""app.services.crypto 字段加密直接测试（T8-P2-8 补缺：decrypt_text 此前仅经路由间接覆盖）。

生产调用形态（AAD 绑定 tenant:user:field）：
- app/api/legacy.py / escalations.py 以 aad=f"{tenant_id}:{user_id}:journal|checkin" 读回；
- 错 AAD / 错 key / 密文被篡改 → AES-GCM 认证失败必须抛 InvalidTag（fail-closed），
  绝不回退明文或吞异常；
- 无前缀明文仅 local 开发向后兼容；pilot/production 拒绝（ValueError）。
"""
import base64

import pytest
from cryptography.exceptions import InvalidTag

from app.config import get_settings
from app.services.crypto import PREFIX, decrypt_text, encrypt_text

AAD = "t_demo:u_demo:journal"  # 与 app/api/legacy.py journal 读回的生产 AAD 形态一致


def test_roundtrip_with_production_aad_shape():
    secret = "周末的屏幕使用比工作日少 42 分钟。"
    token = encrypt_text(secret, aad=AAD)
    assert token is not None and token.startswith(PREFIX)
    assert decrypt_text(token, aad=AAD) == secret


def test_none_passthrough():
    assert encrypt_text(None, aad=AAD) is None
    assert decrypt_text(None, aad=AAD) is None


def test_wrong_aad_fails_closed():
    token = encrypt_text("只属于 u_demo 的秘密", aad=AAD)
    with pytest.raises(InvalidTag):
        decrypt_text(token, aad="t_demo:u_other:journal")


def test_wrong_key_fails_closed(monkeypatch):
    token = encrypt_text("秘密", aad=AAD)
    monkeypatch.setattr(get_settings(), "field_encryption_secret", "rotated-field-secret-32-bytes")
    with pytest.raises(InvalidTag):
        decrypt_text(token, aad=AAD)


def test_tampered_ciphertext_fails_closed():
    token = encrypt_text("秘密", aad=AAD)
    raw = bytearray(base64.urlsafe_b64decode(token[len(PREFIX):]))
    raw[-1] ^= 0xFF
    tampered = PREFIX + base64.urlsafe_b64encode(bytes(raw)).decode("ascii")
    with pytest.raises(InvalidTag):
        decrypt_text(tampered, aad=AAD)


def test_unprefixed_plaintext_local_dev_passthrough_pilot_rejects(monkeypatch):
    assert get_settings().environment == "local"
    assert decrypt_text("legacy-plaintext", aad=AAD) == "legacy-plaintext"
    monkeypatch.setattr(get_settings(), "environment", "pilot")
    with pytest.raises(ValueError):
        decrypt_text("legacy-plaintext", aad=AAD)
