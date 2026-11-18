"""Provisional internal transport; the BE must authorize its snapshot."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.schemas.context_packet import PacketMetadata
from app.services.retrieval.context_selection import (
    MAX_CANDIDATES_PER_KIND,
    ApprovedEvidence,
    ApprovedItem,
    DesignRelation,
    Id,
    ShortText,
    TaskDesignRef,
)
from app.services.retrieval.context_semantic_selector import validate_correlation_key


class ContextSelectionRequest(BaseModel):
    # The HTTP boundary accepts actual JSON arrays; internal models use tuples.
    model_config = ConfigDict(extra="forbid", strict=True)

    request_key: str = Field(min_length=1, max_length=128)
    project_id: Id
    baseline_id: Id
    request: ShortText
    task_id: Id | None = None
    document_version_ids: list[Id] = Field(max_length=1000)
    items: list[ApprovedItem] = Field(max_length=MAX_CANDIDATES_PER_KIND)
    evidence: list[ApprovedEvidence] = Field(max_length=MAX_CANDIDATES_PER_KIND)
    relations: list[DesignRelation] = Field(max_length=1000)
    task_design_refs: list[TaskDesignRef] = Field(max_length=MAX_CANDIDATES_PER_KIND)
    search_status: Literal["COMPLETE", "NOT_READY", "FAILED"]
    # #132: when the BE supplies packet metadata, the response also carries the
    # composed MCP-01 packet content; without it the route stays selection-only.
    metadata: PacketMetadata | None = None

    @field_validator("request_key")
    @classmethod
    def valid_request_key(cls, value: str) -> str:
        return validate_correlation_key(value)
