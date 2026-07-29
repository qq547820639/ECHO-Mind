from functools import lru_cache
import hashlib
from pydantic_settings import BaseSettings, SettingsConfigDict


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

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    @property
    def cors_origin_list(self) -> list[str]:
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]

    @property
    def field_encryption_key(self) -> bytes:
        # Stable 256-bit key derived from deployment secret. Production must use a KMS-injected secret.
        return hashlib.sha256(self.field_encryption_secret.encode("utf-8")).digest()

    def validate_production_secrets(self) -> None:
        if self.environment.lower() in {"production", "pilot"}:
            weak = {
                "dev-secret-change-me-please-32-bytes",
                "dev-field-encryption-secret-change-me",
                "local-bootstrap-only",
            }
            values = {self.jwt_secret, self.field_encryption_secret, self.bootstrap_key}
            if values & weak:
                raise RuntimeError("pilot/production requires externally managed secrets")


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    settings.validate_production_secrets()
    return settings
