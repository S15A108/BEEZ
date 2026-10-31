"""Provisional BE-to-AI recommendation input. BE owns authorization and search."""

import json
from typing import Annotated, Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

TextId = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=128)
]
Description = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)
]


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Source(ContractModel):
    document_id: TextId
    document_version_id: TextId
    version_no: int = Field(ge=1)
    location: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)
    ]


class DesignItemVersion(ContractModel):
    id: TextId
    design_item_id: TextId
    key: TextId
    kind: TextId
    version_no: int = Field(ge=1)
    status: Literal["APPROVED"]
    schema_version: int = Field(ge=1)
    content: dict[str, Any]
    source: Source | None = None

    @model_validator(mode="after")
    def bounded_content(self) -> Self:
        try:
            encoded = json.dumps(self.content, ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError, RecursionError) as exc:
            raise ValueError("invalid design content") from exc
        if len(encoded.encode("utf-8")) > 16_000:
            raise ValueError("design content exceeds the limit")
        return self


class RecommendationCandidate(ContractModel):
    design_item_version: DesignItemVersion


class RecommendationRequest(ContractModel):
    project_id: TextId
    request_key: TextId
    call_key: TextId
    request: Description
    task_id: TextId | None = None
    search_status: Literal["READY", "NOT_READY", "FAILED"]
    search_truncated: bool
    mapping_complete: bool
    execution_budget_ms: int = Field(ge=1, le=60_000)
    candidates: list[RecommendationCandidate] = Field(max_length=20)

    @model_validator(mode="after")
    def distinct_versions(self) -> Self:
        versions = [item.design_item_version.id for item in self.candidates]
        items = [item.design_item_version.design_item_id for item in self.candidates]
        if len(set(versions)) != len(versions) or len(set(items)) != len(items):
            raise ValueError("duplicate candidate")
        if self.search_status != "READY" and self.candidates:
            raise ValueError("failed or unavailable search cannot provide candidates")
        return self


class ModelRecommendation(ContractModel):
    design_item_version_id: TextId
    reason: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)
    ]


class ModelOutput(ContractModel):
    recommendations: list[ModelRecommendation] = Field(max_length=20)
