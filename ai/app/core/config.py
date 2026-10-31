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

    # Impact analysis has its own opt-in provider configuration. No network
    # call is made when any required value is absent.
    impact_llm_endpoint: str | None = None
    impact_llm_api_key: SecretStr | None = None
    impact_llm_model: str | None = None
    # Parse per feature so a bad impact-only setting cannot disable retrieval.
    impact_llm_timeout_seconds: str = "15"
    impact_llm_allowed_response_models: str = ""
    impact_llm_output_token_limit: str = "1024"
    impact_llm_output_token_parameter: str = "max_completion_tokens"
    impact_llm_response_format: str = "json_object"
    impact_max_body_bytes: str = "262144"
    impact_max_items_per_list: str = "100"


@lru_cache
def get_settings() -> Settings:
    return Settings()
