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
    gms_chat_endpoint: str = (
        "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
    )
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
    # Measured need and bounds: app.services.planning_questions.proposer
    # (S15P31A108-388). Keep the BE beez.planning.question-read-timeout longer.
    planning_questions_llm_timeout_seconds: str = "90"
    planning_questions_llm_output_token_limit: str = "8192"
    planning_questions_llm_output_token_parameter: str = "max_completion_tokens"

    # Provisional #134 transport is disabled until explicitly configured; the
    # model call reuses gms_key and bee_default_model like #133.
    design_drafts_enabled: bool = False
    design_drafts_max_body_bytes: str = "2097152"
    # Measured need and bounds: app.services.design_drafts.proposer
    # (S15P31A108-388). Keep the BE beez.planning.draft-read-timeout longer.
    design_drafts_llm_timeout_seconds: str = "150"
    design_drafts_llm_output_token_limit: str = "16384"
    design_drafts_llm_output_token_parameter: str = "max_completion_tokens"

    # Provisional #135 transport is disabled until explicitly configured; the
    # model call reuses gms_key and bee_default_model like #133 and #134.
    # Document review (AI-A10) model-call limits, parsed per request (0 < value <= 120).
    # Review/RERUN is called by the BE worker outside any HTTP request; the BE worker
    # read limit (DOCUMENT_REVIEW_WORKER_READ_TIMEOUT, 120s) must stay longer (S15P31A108-391).
    # A 6,800-character API spec took 31-38 s and a RERUN 40 s (S15P31A108-383).
    document_review_llm_timeout_seconds: str = "110"
    # DISCUSS/REQUEST is still answered inside the user's request: keep it below the BE
    # chat read limit (DOCUMENT_REVIEW_READ_TIMEOUT, 55s) and nginx /api/ (60s).
    document_review_chat_llm_timeout_seconds: str = "50"

    bee_chat_enabled: bool = False
    bee_chat_max_body_bytes: str = "2097152"
    bee_chat_llm_timeout_seconds: str = "30"
    bee_chat_llm_output_token_limit: str = "2048"
    bee_chat_llm_output_token_parameter: str = "max_completion_tokens"
    # "entailment": a second, smaller model call judges every answer
    # sentence against the quotes it cites (catches a flipped subject or
    # negation); "tokens": the deterministic checks only, one call.
    bee_chat_claim_verification: str = "entailment"

    # #136 is opt-in until the BE has agreed to pass workload snapshots and
    # handle the proposed alternative-candidate fields.
    assignment_recommendation_enabled: bool = False
    assignment_recommendation_max_body_bytes: str = "2097152"
    assignment_recommendation_timeout_seconds: str = "15"
    assignment_recommendation_output_token_limit: str = "4096"


@lru_cache
def get_settings() -> Settings:
    return Settings()
