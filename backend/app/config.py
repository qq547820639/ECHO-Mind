from functools import lru_cache
import hashlib
from pydantic_settings import BaseSettings, SettingsConfigDict

#: 对外发布版本（与 README / DELIVERY_MANIFEST / RELEASE_NOTES 保持一致）。
APP_VERSION = "0.11.0"


class Settings(BaseSettings):
    environment: str = "local"
    database_url: str = "sqlite:///./echo_mind.db"
    jwt_secret: str = "dev-secret-change-me-please-32-bytes"
    jwt_issuer: str = "echo-mind-local"
    jwt_audience: str = "echo-mind-api"
    access_token_minutes: int = 60
    cors_origins: str = "http://localhost:8000,http://127.0.0.1:8000"
    bootstrap_key: str = "local-bootstrap-only"
    field_encryption_secret: str = "dev-field-encryption-secret-change-me"
    consent_required_version: str = "path-a-consent-2026.07"
    ack_sla_seconds: int = 60
    takeover_sla_seconds: int = 180
    # 机构负责人层级超时（自事件创建起算），超过则标记机构链路失效。
    org_lead_sla_seconds: int = 600
    sandbox_timeout_seconds: int = 120
    sandbox_max_concurrent: int = 4
    sandbox_rate_limit_per_hour: int = 10
    # v0.6.1：激活码默认 TTL（秒，默认 30 天）。
    activation_code_ttl_seconds: int = 30 * 24 * 3600
    # ERA 32 R25：刷新令牌有效期（天，默认 30 天；过期后需机构重新发放激活码）。
    refresh_token_days: int = 30

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    @property
    def cors_origin_list(self) -> list[str]:
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]

    @property
    def field_encryption_key(self) -> bytes:
        # Stable 256-bit key derived from deployment secret. Production must use a KMS-injected secret.
        return hashlib.sha256(self.field_encryption_secret.encode("utf-8")).digest()

    def validate_production_secrets(self) -> None:
        # ERA 32 R25：任何环境（含 local）都拒绝仓库内默认 dev 秘密——未覆盖环境变量
        # 就启动 = 公开已知密钥 = 可伪造任意角色 JWT / 解密密文字段。fail-closed：
        # 本地开发也必须在 .env 里显式设置非默认值。
        weak = {
            "dev-secret-change-me-please-32-bytes",
            "dev-field-encryption-secret-change-me",
            "local-bootstrap-only",
        }
        values = {self.jwt_secret, self.field_encryption_secret, self.bootstrap_key}
        if values & weak:
            raise RuntimeError(
                "default dev secrets detected; set non-default JWT_SECRET, "
                "FIELD_ENCRYPTION_SECRET and BOOTSTRAP_KEY in every environment"
            )


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    settings.validate_production_secrets()
    return settings
