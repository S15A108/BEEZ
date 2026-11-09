"""Provisional AI-A07 inputs, limited to a BE-authorized project snapshot."""

from typing import Annotated, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

TextId = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=128)
]
ShortText = Annotated[str, StringConstraints(min_length=1)]
MemberText = Annotated[str, StringConstraints(min_length=1, max_length=256)]
TaskStatus = Literal[
    "DRAFT", "READY", "IN_PROGRESS", "WAITING", "COMPLETED", "CANCELLED"
]
ACTIVE_STATUSES = frozenset({"DRAFT", "READY", "IN_PROGRESS", "WAITING"})


class InputModel(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True, frozen=True)


class Member(InputModel):
    """An active project member; membership is checked by the caller."""

    id: TextId
    role: Literal["LEADER", "MEMBER"]
    domain: MemberText | None = None
    role_label: MemberText | None = None

    @model_validator(mode="after")
    def nonblank_labels(self) -> Self:
        if any(
            value is not None and not value.strip()
            for value in (self.domain, self.role_label)
        ):
            raise ValueError("blank member domain or role label")
        return self


class Plan(InputModel):
    title: ShortText
    goal: ShortText | None = None
    included_scope: list[str] | None = None
    approach: ShortText | None = None
    # None means not supplied; [] means the current plan declares no dependencies.
    dependency_task_ids: list[TextId] | None = Field(default=None, max_length=100)

    @model_validator(mode="after")
    def nonblank_and_distinct(self) -> Self:
        if not self.title.strip() or any(
            value is not None and not value.strip()
            for value in (self.goal, self.approach)
        ):
            raise ValueError("blank plan text")
        if self.dependency_task_ids is not None and len(
            set(self.dependency_task_ids)
        ) != len(self.dependency_task_ids):
            raise ValueError("duplicate dependency")
        return self


class PlanRevision(InputModel):
    task_id: TextId | None = None
    plan: Plan


class TaskSnapshot(InputModel):
    id: TextId
    project_id: TextId
    status: TaskStatus
    assignee_member_id: TextId | None = None
    current_plan_revision: PlanRevision | None = None

    @model_validator(mode="after")
    def consistent_plan(self) -> Self:
        if self.current_plan_revision is not None:
            revision = self.current_plan_revision
            if revision.task_id is not None and revision.task_id != self.id:
                raise ValueError("plan belongs to another task")
            if self.id in (revision.plan.dependency_task_ids or []):
                raise ValueError("task depends on itself")
        return self


class AssignmentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)

    project_id: TextId
    request_key: TextId
    tasks: list[TaskSnapshot] = Field(min_length=1, max_length=20)
    members: list[Member] = Field(max_length=100)
    # Absent is unknown, not an empty team workload. This is a proposed AI-A07
    # extension; a caller must explicitly attest completeness before the
    # service reports a total workload.
    current_tasks: list[TaskSnapshot] | None = Field(default=None, max_length=500)
    current_tasks_complete: bool = False

    @model_validator(mode="after")
    def consistent_snapshot(self) -> Self:
        if self.current_tasks_complete and self.current_tasks is None:
            raise ValueError("complete workload requires current_tasks")
        targets = {task.id: task for task in self.tasks}
        if len(targets) != len(self.tasks):
            raise ValueError("duplicate target task")
        if len({member.id for member in self.members}) != len(self.members):
            raise ValueError("duplicate member")
        current = {task.id: task for task in self.current_tasks or []}
        if len(current) != len(self.current_tasks or []):
            raise ValueError("duplicate current task")
        if any(
            task.project_id != self.project_id
            for task in [*self.tasks, *current.values()]
        ):
            raise ValueError("task belongs to another project")
        if any(task.status in {"COMPLETED", "CANCELLED"} for task in self.tasks):
            raise ValueError("closed task cannot be a recommendation target")
        for task_id in targets.keys() & current.keys():
            target, observed = targets[task_id], current[task_id]
            if (target.status, target.assignee_member_id) != (
                observed.status,
                observed.assignee_member_id,
            ):
                raise ValueError("target and current task snapshots differ")
        if self.current_tasks_complete:
            for task in self.tasks:
                if (
                    task.status in ACTIVE_STATUSES
                    and task.assignee_member_id is not None
                    and task.id not in current
                ):
                    raise ValueError("complete workload omitted an assigned target")
        return self
