import base64
import os
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from app.config import get_settings

PREFIX = "enc:v1:"


def encrypt_text(value: str | None, *, aad: str = "") -> str | None:
    if value is None:
        return None
    nonce = os.urandom(12)
    ciphertext = AESGCM(get_settings().field_encryption_key).encrypt(
        nonce, value.encode("utf-8"), aad.encode("utf-8")
    )
    return PREFIX + base64.urlsafe_b64encode(nonce + ciphertext).decode("ascii")


def decrypt_text(value: str | None, *, aad: str = "") -> str | None:
    if value is None:
        return None
    if not value.startswith(PREFIX):
        # Backward compatibility is limited to local development. Pilot/production must migrate first.
        if get_settings().environment.lower() in {"pilot", "production"}:
            raise ValueError("unencrypted sensitive field rejected outside local development")
        return value
    payload = base64.urlsafe_b64decode(value[len(PREFIX):].encode("ascii"))
    return AESGCM(get_settings().field_encryption_key).decrypt(
        payload[:12], payload[12:], aad.encode("utf-8")
    ).decode("utf-8")
