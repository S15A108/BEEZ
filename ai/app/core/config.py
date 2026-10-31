from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=Path(__file__).resolve().parents[2] / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    gms_key: SecretStr | None = None
    ai_internal_token: SecretStr | None = None
    gms_embedding_model: Literal["text-embedding-3-small"] = "text-embedding-3-small"
    gms_embedding_dimension: int = Field(default=1024, ge=1, le=1536)
    # Generation is implemented separately; no automatic fallback call in #129.
    bee_default_model: Literal["gpt-6-luna"] = "gpt-6-luna"
    bee_complex_model: Literal["gpt-6.1-sol"] = "gpt-6.1-sol"
    # Disabled until the BE usage feature/DB contract for #185 is deployed.
    design_recommendation_enabled: bool = False


@lru_cache
def get_settings() -> Settings:
    return Settings()
