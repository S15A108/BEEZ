"""Inputs and ticket candidates for the approved design task breakdown."""

from graphlib import CycleError, TopologicalSorter
from typing import Annotated, Literal, Self

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    model_validator,
)

MAX_TASKS = 24
MAX_CONTENT_LENGTH = 12_000
MAX_TOTAL_CONTENT_LENGTH = 100_000


def _storable_text(value: str) -> str:
    if "\0" in value:
        raise ValueError("text contains a null character")
    value.encode("utf-8")
    return value


StorableText = AfterValidator(_storable_text)
ProjectId = Annotated[str, StringConstraints(pattern=r"^[1-9][0-9]{0,18}$")]
TaskKey = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$")]
SourceTitle = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=300)
]
SourceContent = Annotated[str, StringConstraints(max_length=MAX_CONTENT_LENGTH)]
Area = Annotated[
    str,
    StringConstraints(strip_whitespace=True, min_length=1, max_length=100),
    StorableText,
]
PlanLine = Annotated[
    str,
    StringConstraints(strip_whitespace=True, min_length=1, max_length=500),
    StorableText,
]


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class ApprovedDocument(ContractModel):
    title: SourceTitle
    content: SourceContent


class ApprovedDesignItem(ContractModel):
    design_item_id: ProjectId
    design_item_version_id: ProjectId
    title: SourceTitle
    kind: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=60)
    ]
    content: SourceContent


class TaskBreakdownRequest(ContractModel):
    project_id: ProjectId
    request_key: Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9._:-]{1,200}$")]
    documents: list[ApprovedDocument] = Field(max_length=8)
    design_items: list[ApprovedDesignItem] = Field(min_length=1, max_length=80)
    assignment_areas: list[Area] = Field(max_length=60)

    @model_validator(mode="after")
    def validate_snapshot(self) -> Self:
        if len({item.design_item_id for item in self.design_items}) != len(
            self.design_items
        ):
            raise ValueError("duplicate approved design item")
        if len(set(self.assignment_areas)) != len(self.assignment_areas):
            raise ValueError("duplicate assignment area")
        content_length = sum(
            len(source.content) for source in [*self.documents, *self.design_items]
        )
        if content_length > MAX_TOTAL_CONTENT_LENGTH:
            raise ValueError("approved source content exceeds the aggregate limit")
        return self


class TicketDesignRef(ContractModel):
    design_item_id: ProjectId
    design_item_version_id: ProjectId
    relation: Literal["READS", "IMPLEMENTS"]


class TaskCandidate(ContractModel):
    key: TaskKey
    title: Annotated[
        str,
        StringConstraints(strip_whitespace=True, min_length=1, max_length=200),
        StorableText,
    ]
    goal: Annotated[
        str,
        StringConstraints(strip_whitespace=True, min_length=1, max_length=3000),
        StorableText,
    ]
    included_scope: list[PlanLine] = Field(min_length=1, max_length=12)
    excluded_scope: list[PlanLine] = Field(max_length=12)
    approach: Annotated[
        str,
        StringConstraints(strip_whitespace=True, min_length=1, max_length=6000),
        StorableText,
    ]
    completion_criteria: list[PlanLine] = Field(min_length=1, max_length=12)
    design_refs: list[TicketDesignRef] = Field(min_length=1, max_length=20)
    dependency_keys: list[TaskKey] = Field(max_length=MAX_TASKS - 1)
    assignment_area: Area | None


class TaskBreakdownResult(ContractModel):
    tasks: list[TaskCandidate] = Field(min_length=1, max_length=MAX_TASKS)

    def validate_against(self, snapshot: TaskBreakdownRequest) -> None:
        versions = {
            item.design_item_id: item.design_item_version_id
            for item in snapshot.design_items
        }
        areas = set(snapshot.assignment_areas)
        keys = {task.key for task in self.tasks}
        if len(keys) != len(self.tasks):
            raise ValueError("duplicate ticket key")

        for task in self.tasks:
            if task.assignment_area is not None and task.assignment_area not in areas:
                raise ValueError("unknown assignment area")
            if any(
                versions.get(ref.design_item_id) != ref.design_item_version_id
                for ref in task.design_refs
            ):
                raise ValueError("reference outside the approved snapshot")
            if len({ref.design_item_id for ref in task.design_refs}) != len(
                task.design_refs
            ):
                raise ValueError("duplicate design reference")
            dependencies = set(task.dependency_keys)
            if not dependencies <= keys or len(dependencies) != len(
                task.dependency_keys
            ):
                raise ValueError("unknown or duplicate predecessor")

        try:
            TopologicalSorter(
                {task.key: task.dependency_keys for task in self.tasks}
            ).prepare()
        except CycleError:
            raise ValueError("cyclic ticket dependencies") from None
