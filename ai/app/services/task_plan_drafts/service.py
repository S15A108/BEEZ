"""Verify a model plan draft against the supplied excerpts and design items.

Quotes must occur verbatim in the excerpt they name, design item ids must be
among the supplied ones, and a new design item candidate needs valid content
and at least one verified quote. What cannot be verified is dropped, never
repaired. Nothing is stored and nothing is approved here.
"""

from dataclasses import dataclass
from typing import Any, Literal

from pydantic import ValidationError

from app.schemas.task_plan_drafts import (
    Evidence,
    LinkCandidate,
    NewItemCandidate,
    NewItemEvidence,
    PlanFields,
    TaskPlanDraftRequest,
    TaskPlanDraftResult,
    Unlinked,
)
from app.services.llm_client import LLMUsage
from app.services.task_plan_drafts.proposer import (
    ModelDraft,
    PlanProposal,
    PlanProposer,
)

_PLAN_FIELDS = (
    "goal",
    "included_scope",
    "excluded_scope",
    "approach",
    "completion_criteria",
)


@dataclass(frozen=True, slots=True)
class TaskPlanDraftOutcome:
    """Result plus the common internal envelope fields (origin, model, usage)."""

    result: TaskPlanDraftResult
    origin: Literal["MODEL"]
    model: str
    usage: tuple[LLMUsage, ...]

    def to_api_dict(self) -> dict[str, Any]:
        return {
            "result": self.result.model_dump(mode="json"),
            "origin": self.origin,
            "model": self.model,
            "usage": [item.to_api_dict() for item in self.usage],
        }


def _clean_list(values: list[str]) -> tuple[str, ...]:
    return tuple(text.strip() for text in values if text.strip())


def _has_text(plan: PlanFields, field: str) -> bool:
    value = getattr(plan, field)
    return bool(value.strip()) if isinstance(value, str) else bool(value)


def compose_result(
    request: TaskPlanDraftRequest, draft: ModelDraft
) -> TaskPlanDraftResult:
    excerpts = {item.excerpt_id: item for item in request.excerpts}
    items = {item.design_item_id for item in request.design_items}
    plan = PlanFields(
        goal=draft.plan.goal.strip(),
        included_scope=_clean_list(draft.plan.included_scope),
        excluded_scope=_clean_list(draft.plan.excluded_scope),
        approach=draft.plan.approach.strip(),
        completion_criteria=_clean_list(draft.plan.completion_criteria),
    )

    evidence: list[Evidence] = []
    seen: set[tuple[str, str, str]] = set()
    for entry in draft.evidence:
        excerpt = excerpts.get(entry.excerpt_id)
        quote = entry.quote.strip()
        key = (entry.field, entry.excerpt_id, quote)
        if (
            excerpt is None
            or not quote
            or quote not in excerpt.text
            or key in seen
            or not _has_text(plan, entry.field)
        ):
            continue
        seen.add(key)
        evidence.append(
            Evidence(
                field=entry.field,
                document_id=excerpt.document_id,
                document_type=excerpt.document_type,
                document_version_id=excerpt.document_version_id,
                version_no=excerpt.version_no,
                location=excerpt.location,
                quote=quote,
            )
        )
    supported = {entry.field for entry in evidence}
    unsupported = tuple(
        field
        for field in _PLAN_FIELDS
        if _has_text(plan, field) and field not in supported
    )

    links: list[LinkCandidate] = []
    linked: set[str] = set()
    for link in draft.design_ref_candidates:
        reason = link.reason.strip()
        if (
            link.design_item_id in items
            and link.design_item_id not in linked
            and reason
        ):
            linked.add(link.design_item_id)
            links.append(
                LinkCandidate(design_item_id=link.design_item_id, reason=reason)
            )

    new_items: list[NewItemCandidate] = []
    for item in draft.new_design_items:
        quotes: list[NewItemEvidence] = []
        for entry in item.evidence:
            excerpt = excerpts.get(entry.excerpt_id)
            quote = entry.quote.strip()
            if excerpt is None or not quote or quote not in excerpt.text:
                continue
            quotes.append(
                NewItemEvidence(
                    document_id=excerpt.document_id,
                    document_type=excerpt.document_type,
                    document_version_id=excerpt.document_version_id,
                    version_no=excerpt.version_no,
                    location=excerpt.location,
                    quote=quote,
                )
            )
        if not quotes:
            continue
        try:
            new_items.append(
                NewItemCandidate(
                    kind=item.kind,
                    content=item.content,
                    rationale=item.rationale.strip() or "문서에 있는 내용이에요.",
                    evidence=tuple(quotes),
                )
            )
        except (ValidationError, ValueError):
            continue

    unlinked = tuple(
        Unlinked(text=entry.text.strip())
        for entry in draft.unlinked
        if entry.text.strip()
    )
    drafted = any(_has_text(plan, field) for field in _PLAN_FIELDS)
    return TaskPlanDraftResult(
        status="DRAFTED" if drafted else "INSUFFICIENT",
        plan=plan,
        evidence=tuple(evidence),
        unsupported_fields=unsupported,
        design_ref_candidates=tuple(links),
        new_design_item_candidates=tuple(new_items),
        unlinked=unlinked,
    )


async def generate_task_plan_draft(
    request: TaskPlanDraftRequest, proposer: PlanProposer
) -> TaskPlanDraftOutcome:
    """Propose and verify; raise LLM errors as is."""
    proposal: PlanProposal = await proposer.propose(request)
    try:
        result = compose_result(proposal.selected_request or request, proposal.draft)
    except (ValidationError, ValueError):
        # The model contradicted the inputs: keep the real usage, mark it failed.
        raise proposal.invalid_output_error() from None
    return TaskPlanDraftOutcome(result, "MODEL", proposal.model, proposal.usage)
