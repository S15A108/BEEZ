"""Internal task validation contract. Provenance is checked by the service."""

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, StrictStr


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Source(ContractModel):
    document_id: StrictStr = Field(min_length=1)
    document_version_id: StrictStr = Field(min_length=1)
    version_no: StrictInt = Field(ge=1)
    location: StrictStr = Field(min_length=1)


class DesignRef(ContractModel):
    design_item_id: StrictStr = Field(min_length=1)
    design_item_version_id: StrictStr = Field(min_length=1)
    relation: Literal["READS", "IMPLEMENTS", "CHANGES"]
    proposed_content: dict[str, Any] | None = None


class Plan(ContractModel):
    title: StrictStr
    goal: StrictStr
    included_scope: list[StrictStr]
    excluded_scope: list[StrictStr]
    approach: StrictStr
    completion_criteria: list[StrictStr]
    dependency_task_ids: list[StrictStr]
    design_refs: list[DesignRef]
    unlinked: list[dict[str, Any]]
    no_contract_change: StrictBool
    context_packet_id: StrictStr | None = None


class PlanRevision(ContractModel):
    id: StrictStr = Field(min_length=1)
    task_id: StrictStr = Field(min_length=1)
    revision_no: StrictInt = Field(ge=1)
    plan: Plan
    created_at: StrictStr
    created_by: StrictStr = Field(min_length=1)
    agent_token_id: StrictStr | None = None


class DesignVersion(ContractModel):
    id: StrictStr = Field(min_length=1)
    design_item_id: StrictStr = Field(min_length=1)
    key: StrictStr = Field(min_length=1)
    kind: StrictStr
    version_no: StrictInt = Field(ge=1)
    status: StrictStr
    schema_version: StrictInt = Field(ge=1)
    content: dict[str, Any]
    source: Source | None = None
    content_hash: StrictStr | None = None


class Check(ContractModel):
    key: StrictStr = Field(min_length=1)
    kind: Literal["PLAN_COMPLETENESS", "DESIGN_ALIGNMENT", "RELATED_PLAN_CONFLICT"]
    design_item_version_ids: list[StrictStr]
    related_plan_revision_ids: list[StrictStr]


class Locator(ContractModel):
    type: Literal["text_lines"]
    start_line: StrictInt = Field(ge=1)
    end_line: StrictInt = Field(ge=1)


class Evidence(ContractModel):
    evidence_id: StrictStr = Field(min_length=1)
    design_item_version_id: StrictStr = Field(min_length=1)
    source: Source
    locator: Locator
    text: StrictStr = Field(min_length=1)
    text_hash: StrictStr = Field(pattern=r"^[0-9a-f]{64}$")
    normalization_version: Literal["utf8-lf-v1"]


class ValidateRequest(ContractModel):
    project_id: StrictStr = Field(min_length=1)
    request_key: StrictStr = Field(min_length=1)
    validation_id: StrictStr = Field(min_length=1)
    attempt: StrictInt = Field(ge=1)
    plan_revision: PlanRevision
    approved_items: list[DesignVersion]
    related_plans: list[PlanRevision]
    rule_findings: list[dict[str, Any]]
    unresolved: list[StrictStr]
    input_snapshot_hash: StrictStr = Field(min_length=1)
    checks: list[Check] = Field(min_length=1)
    evidence: list[Evidence]
    execution_budget_ms: StrictInt = Field(ge=1)


class Unassessed(ContractModel):
    check_key: StrictStr = Field(min_length=1)
    reason: Literal["EVIDENCE_INSUFFICIENT", "UNSUPPORTED_CONTENT"]
    message: StrictStr = Field(min_length=1)


class Finding(ContractModel):
    check_key: StrictStr = Field(min_length=1)
    kind: Literal[
        "MISMATCH", "MISSING_INPUT", "MISSING_POLICY", "CONFLICT", "STALE_REF"
    ]
    severity: Literal["INFO", "WARNING", "BLOCKING"]
    message: StrictStr = Field(min_length=1)
    design_item_id: StrictStr | None = None
    design_item_version_id: StrictStr | None = None
    related_task_id: StrictStr | None = None
    sources: list[Source]
    evidence_ids: list[StrictStr]
    suggested_action: StrictStr = Field(min_length=1)


class ValidateResult(ContractModel):
    analysis_status: Literal["COMPLETE", "PARTIAL"]
    checked_keys: list[StrictStr]
    unassessed: list[Unassessed]
    findings: list[Finding]
