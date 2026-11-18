"""A proposal over a fixed plan and server-owned actionable validation findings."""

from typing import Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.schemas.planning_questions import Id
from app.schemas.task_plan_drafts import (
    DesignItemRef,
    Evidence,
    Excerpt,
    PlanField,
    TaskInput,
)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class EditablePlan(StrictModel):
    # Existing plans may exceed the new-draft limits. Never truncate their fields.
    goal: str
    included_scope: tuple[str, ...]
    excluded_scope: tuple[str, ...]
    approach: str
    completion_criteria: tuple[str, ...]


class CurrentDesignRef(StrictModel):
    design_item_id: Id
    design_item_version_id: Id
    relation: Literal["READS", "IMPLEMENTS", "CHANGES"]
    proposed_content: dict[str, Any] | None = None


class CurrentPlan(EditablePlan):
    title: str
    dependency_task_ids: tuple[Id, ...]
    design_refs: tuple[CurrentDesignRef, ...]
    unlinked: tuple[dict[str, Any], ...]
    no_contract_change: bool
    context_packet_id: Id | None = None


class RevisionFinding(StrictModel):
    id: Id
    kind: str = Field(min_length=1, max_length=100)
    severity: Literal["INFO", "WARNING", "BLOCKING"]
    message: str = Field(min_length=1, max_length=10000)
    resolution: str | None = None
    next_action: str | None = None
    validation_run_id: Id | None = None
    source: str | None = None
    design_item_id: Id | None = None
    design_item_version_id: Id | None = None
    related_task_id: Id | None = None
    sources: tuple[dict[str, Any], ...] = ()
    policy_need_id: str | None = None
    conflicting_design_refs: tuple[dict[str, Any], ...] | None = None
    ai_suggested_action: str | None = None


class RevisionValidation(StrictModel):
    id: Id
    status: Literal["NEEDS_INPUT", "NEEDS_REVIEW"]
    findings: tuple[RevisionFinding, ...] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def actionable(self) -> Self:
        ids = [finding.id for finding in self.findings]
        if len(ids) != len(set(ids)):
            raise ValueError("duplicate finding id")
        if any(
            finding.kind
            in {"ANALYSIS_ERROR", "EXECUTION_ERROR", "EVIDENCE_COLLECTION_FAILED"}
            or finding.resolution == "RETRY"
            for finding in self.findings
        ):
            raise ValueError("execution failures require retry, not plan changes")
        if not any(finding.resolution == "FIX_PLAN" for finding in self.findings):
            raise ValueError("no actionable plan findings")
        return self


class TaskPlanRevisionRequest(StrictModel):
    project_id: Id
    request_key: Id
    task: TaskInput
    current_plan: CurrentPlan
    validation: RevisionValidation
    excerpts: tuple[Excerpt, ...] = Field(min_length=1, max_length=12)
    design_items: tuple[DesignItemRef, ...] = Field(default=(), max_length=100)

    @model_validator(mode="after")
    def unique_sources(self) -> Self:
        excerpt_ids = [item.excerpt_id for item in self.excerpts]
        design_ids = [item.design_item_id for item in self.design_items]
        if len(set(excerpt_ids)) != len(excerpt_ids) or len(set(design_ids)) != len(
            design_ids
        ):
            raise ValueError("duplicate source id")
        return self


class RevisionChange(StrictModel):
    field: PlanField
    reason: str = Field(min_length=1, max_length=500)
    finding_ids: tuple[Id, ...] = Field(min_length=1, max_length=10)


class TaskPlanRevisionResult(StrictModel):
    status: Literal["PROPOSED", "INSUFFICIENT"]
    plan: EditablePlan
    changes: tuple[RevisionChange, ...] = Field(max_length=5)
    evidence: tuple[Evidence, ...] = Field(max_length=15)
