"""Provisional internal transport; the BE must authorize its snapshot."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.services.retrieval.context_selection import (
    ApprovedEvidence,
    ApprovedItem,
    DesignRelation,
    Id,
    ShortText,
    TaskDesignRef,
)


class ContextSelectionRequest(BaseModel):
    # The HTTP boundary accepts actual JSON arrays; internal models use tuples.
    model_config = ConfigDict(extra="forbid", strict=True)

    project_id: Id
    baseline_id: Id
    request: ShortText
    task_id: Id | None = None
    document_version_ids: list[Id] = Field(max_length=1000)
    items: list[ApprovedItem] = Field(max_length=200)
    evidence: list[ApprovedEvidence] = Field(max_length=200)
    relations: list[DesignRelation] = Field(max_length=1000)
    task_design_refs: list[TaskDesignRef] = Field(max_length=200)
    search_status: Literal["COMPLETE", "NOT_READY", "FAILED"]
