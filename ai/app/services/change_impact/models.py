"""Minimal internal AI-A03 types; the existing API draft remains authoritative."""

from typing import Annotated, Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

Identifier = Annotated[str, Field(min_length=1, max_length=256)]
NonBlank = Annotated[str, Field(min_length=1, max_length=4000)]


class InputModel(BaseModel):
    # The backend contract contains additional fields that belong in the prompt.
    model_config = ConfigDict(extra="allow", strict=True, str_strip_whitespace=True)


class OutputModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, str_strip_whitespace=True)


class Source(OutputModel):
    document_id: Identifier
    document_version_id: Identifier
    version_no: int = Field(ge=1)
    location: NonBlank


class ChangeItem(InputModel):
    operation: Literal["CREATE", "UPDATE", "RETIRE"]
    design_item_id: Identifier | None = None
    base_design_item_version_id: Identifier | None = None
    kind: (
        Literal["REQUIREMENT", "API_CONTRACT", "DATA_CONTRACT", "POLICY", "CONSTRAINT"]
        | None
    ) = None
    schema_version: int | None = Field(default=None, ge=1, le=2147483647)
    proposed_content: dict[str, Any] | None = None

    @model_validator(mode="after")
    def validate_operation_fields(self) -> Self:
        if self.operation in {"UPDATE", "RETIRE"} and (
            self.design_item_id is None or self.base_design_item_version_id is None
        ):
            raise ValueError("changed item and base version are required")
        if self.operation == "CREATE" and (
            self.design_item_id is not None
            or self.base_design_item_version_id is not None
        ):
            raise ValueError("creation cannot specify an existing item or base version")
        if self.operation == "CREATE" and self.kind is None:
            raise ValueError("kind is required for creation")
        if self.operation in {"CREATE", "UPDATE"} and (
            self.schema_version is None or not self.proposed_content
        ):
            raise ValueError("schema version and proposed content are required")
        return self


class ApprovedItem(InputModel):
    id: Identifier
    design_item_id: Identifier
    status: Literal["APPROVED"]
    content: dict[str, Any]
    source: Source | None = None


class OpenTask(InputModel):
    id: Identifier
    project_id: Identifier
    assignee_member_id: Identifier | None = None
    validation: dict[str, Any] | None = None

    @model_validator(mode="after")
    def validate_plan_types(self) -> Self:
        revision = getattr(self, "current_plan_revision", None)
        if revision is None:
            return self
        if not isinstance(revision, dict):
            raise ValueError("invalid plan revision")  # noqa: TRY004 - Pydantic wraps ValueError
        if "id" in revision and (
            not isinstance(revision["id"], str) or not revision["id"].strip()
        ):
            raise ValueError("invalid plan revision id")
        if "plan" not in revision:
            return self
        plan = revision["plan"]
        if not isinstance(plan, dict):
            raise ValueError("invalid plan")  # noqa: TRY004 - Pydantic wraps ValueError
        for name in ("title", "goal", "approach"):
            if name in plan and not isinstance(plan[name], str):
                raise ValueError("invalid plan text")
        for name in ("included_scope", "excluded_scope", "completion_criteria"):
            if name in plan and (
                not isinstance(plan[name], list)
                or any(not isinstance(value, str) for value in plan[name])
            ):
                raise ValueError("invalid plan text list")
        return self


class ImpactInput(InputModel):
    project_id: Identifier
    request_key: Identifier
    proposal_id: Identifier
    changes: list[ChangeItem]
    approved_items: list[ApprovedItem]
    explicit_dependencies: list[dict[str, Any]]
    open_tasks: list[OpenTask]

    @model_validator(mode="after")
    def tasks_belong_to_project(self) -> Self:
        if not self.changes:
            raise ValueError("at least one change is required")
        if any(task.project_id != self.project_id for task in self.open_tasks):
            raise ValueError("task project does not match request project")
        if len({task.id for task in self.open_tasks}) != len(self.open_tasks):
            raise ValueError("duplicate task id")
        validation_ids = [
            task.validation.get("id")
            for task in self.open_tasks
            if task.validation and isinstance(task.validation.get("id"), str)
        ]
        if len(set(validation_ids)) != len(validation_ids):
            raise ValueError("duplicate validation id")
        return self


class ImpactCandidate(OutputModel):
    task_id: Identifier | None = None
    member_id: Identifier | None = None
    design_item_id: Identifier | None = None
    # Provisional extension to AI-A03; accepted only if supplied in open_tasks.
    validation_id: Identifier | None = None
    reason: NonBlank
    sources: list[Source]

    @model_validator(mode="after")
    def has_target(self) -> Self:
        if (
            self.task_id is None
            and self.member_id is None
            and self.design_item_id is None
            and self.validation_id is None
        ):
            raise ValueError("candidate must have a target")
        return self


class ImpactResult(OutputModel):
    candidates: list[ImpactCandidate]
    # Provisional extension, distinguish insufficient inputs from an empty
    # result. Unknown or missing status is rejected, not assumed sufficient.
    evidence_status: Literal["SUFFICIENT", "INSUFFICIENT"]
