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
    validation_response_format: Literal["json_object", "json_schema"] = "json_object"
    gms_embedding_model: Literal["text-embedding-3-small"] = "text-embedding-3-small"
    gms_embedding_dimension: int = Field(default=1024, ge=1, le=1536)
    # Generation is implemented separately; no automatic fallback call in #129.
    bee_default_model: Literal["gpt-6-luna"] = "gpt-6-luna"
    bee_complex_model: Literal["gpt-6.1-sol"] = "gpt-6.1-sol"
    # Disabled until the BE usage feature/DB contract for #185 is deployed.
    design_recommendation_enabled: bool = False
    # Provisional #131 transport is disabled until explicitly configured.
    context_selection_enabled: bool = False
    # No quality threshold or candidate limit is adopted by default.
    context_selection_min_similarity: str | None = None
    context_selection_max_items: str | None = None
    context_selection_max_evidence: str | None = None
    context_selection_total_timeout_seconds: str | None = None

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

    # Provisional #133 transport is disabled until explicitly configured. The
    # model call reuses gms_key and bee_default_model; limits are parsed per
    # feature so a bad value here cannot disable other routes.
    planning_questions_enabled: bool = False
    planning_questions_max_body_bytes: str = "2097152"
    planning_questions_llm_timeout_seconds: str = "30"
    planning_questions_llm_output_token_limit: str = "2048"
    planning_questions_llm_output_token_parameter: str = "max_completion_tokens"


@lru_cache
def get_settings() -> Settings:
    return Settings()
