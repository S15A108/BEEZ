"""Compose rule hits and source-checked model proposals into ranked questions (#133).

Internal interface only. The caller passes one BE-authorized document version;
this module parses it, detects candidates, verifies every model quote against
the referenced block, orders by priority and caps the questions. It never
stores anything and never calls a model unless a proposer is supplied.
"""

import hashlib
from dataclasses import dataclass
from typing import Any

from app.schemas.documents import SourceDocument
from app.schemas.planning_questions import (
    MAX_CANDIDATES,
    AnswerFormat,
    ChoiceAnswerFormat,
    DocumentRef,
    EssayAnswerFormat,
    EvidenceItem,
    Origin,
    PlanningQuestionRequest,
    PlanningQuestionResult,
    ReviewCandidate,
    ShortAnswerFormat,
    SupplementQuestion,
    UnverifiedReason,
)
from app.services.document_parsing.pipeline import parse_source, resolve_source
from app.services.llm_client import LLMUsage
from app.services.planning_questions.proposer import (
    CandidateProposer,
    ModelCandidate,
    ModelQuestion,
)
from app.services.planning_questions.rules import (
    QuestionDraft,
    block_location,
    detect_rule_candidates,
    heading_titles,
)

# Priority: verified evidence first, then conflicts over gaps over wording,
# then more evidence, then source order. Documented in the package README.
_STATUS_WEIGHT = {"REVIEW_CANDIDATE": 100, "UNVERIFIED": 0}
_KIND_WEIGHT = {"CONFLICT": 30, "MISSING_CANDIDATE": 20, "CLARIFICATION": 10}
_NO_POSITION = 10**9

Item = tuple[ReviewCandidate, QuestionDraft]


@dataclass(frozen=True, slots=True)
class PlanningQuestionOutcome:
    """Result plus the common internal envelope fields (origin, model, usage)."""

    result: PlanningQuestionResult
    origin: Origin
    model: str | None
    usage: tuple[LLMUsage, ...]

    def to_api_dict(self) -> dict[str, Any]:
        body: dict[str, Any] = {
            "result": self.result.model_dump(mode="json"),
            "origin": self.origin,
        }
        if self.model is not None:
            body["model"] = self.model
        if self.usage:
            body["usage"] = [item.to_api_dict() for item in self.usage]
        return body


def priority_score(candidate: ReviewCandidate) -> int:
    return (
        _STATUS_WEIGHT[candidate.status]
        + _KIND_WEIGHT[candidate.kind]
        + 2 * min(len(candidate.evidence), 3)
    )


def _position(candidate: ReviewCandidate) -> int:
    return min(
        (item.locator.start_line for item in candidate.evidence), default=_NO_POSITION
    )


def order_items(items: list[Item]) -> list[Item]:
    return sorted(
        items,
        key=lambda item: (
            -priority_score(item[0]),
            _position(item[0]),
            item[0].candidate_key,
        ),
    )


def _answer_format(question: ModelQuestion) -> AnswerFormat:
    if question.type == "CHOICE":
        return ChoiceAnswerFormat(
            options=tuple(option.strip() for option in question.options),
            multiple=question.multiple,
            allow_other=True,
        )
    if question.type == "SHORT_ANSWER":
        return ShortAnswerFormat()
    return EssayAnswerFormat()


def model_candidate_key(
    kind: str, evidence: tuple[EvidenceItem, ...], summary: str
) -> str:
    """Content-derived key: the same kind and verified quotes give the same key.

    Output order never affects it, so re-analysing the same document version
    yields the same key for the same anchored gap. Without evidence the
    normalized summary is used, which is only as stable as the model's wording.
    """
    if evidence:
        anchors = sorted((item.block_id, item.quote) for item in evidence)
        material = "\n".join(f"{kind}\t{block}\t{quote}" for block, quote in anchors)
    else:
        material = f"{kind}\t{' '.join(summary.split())}"
    return "model-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:12]


def verify_model_candidate(
    document: SourceDocument, ref: DocumentRef, proposed: ModelCandidate
) -> Item:
    """Keep only quotes found in their block; anything less is UNVERIFIED."""
    blocks = {block.block_id: block for block in document.blocks}
    titles = heading_titles(document)
    verified: list[EvidenceItem] = []
    reason: UnverifiedReason | None = None
    seen: set[tuple[str, str]] = set()
    for item in proposed.evidence:
        block = blocks.get(item.block_id)
        if block is None:
            reason = reason or "BLOCK_NOT_FOUND"
            continue
        quote = item.quote.strip()
        if not quote or quote not in resolve_source(document, block.locator):
            reason = reason or "QUOTE_NOT_IN_BLOCK"
            continue
        if (block.block_id, quote) in seen:
            continue
        seen.add((block.block_id, quote))
        verified.append(
            EvidenceItem(
                document_id=ref.document_id,
                document_version_id=ref.document_version_id,
                version_no=ref.version_no,
                block_id=block.block_id,
                locator=block.locator,
                location=block_location(document, block, titles),
                quote=quote,
            )
        )
    if not verified:
        reason = reason or "NO_EVIDENCE"
    elif (
        reason is None
        and proposed.kind == "CONFLICT"
        and len({item.block_id for item in verified}) < 2
    ):
        reason = "INSUFFICIENT_EVIDENCE"
    candidate = ReviewCandidate(
        candidate_key=model_candidate_key(
            proposed.kind, tuple(verified), proposed.summary
        ),
        kind=proposed.kind,
        status="REVIEW_CANDIDATE" if reason is None else "UNVERIFIED",
        signal="MODEL_PROPOSAL",
        summary=proposed.summary.strip(),
        evidence=tuple(verified),
        unverified_reason=reason,
    )
    draft = QuestionDraft(
        proposed.question.type,
        proposed.question.text.strip(),
        _answer_format(proposed.question),
        "MODEL",
    )
    return candidate, draft


def _covered_by(rule: ReviewCandidate, model: ReviewCandidate) -> bool:
    # A verified model candidate that quotes the same passage replaces the
    # template question for that rule hit; its question is written for it.
    if rule.kind != model.kind or model.status != "REVIEW_CANDIDATE":
        return False
    return any(
        r.block_id == m.block_id and (r.quote in m.quote or m.quote in r.quote)
        for r in rule.evidence
        for m in model.evidence
    )


def merge_items(rule_items: list[Item], model_items: list[Item]) -> list[Item]:
    kept = [
        item
        for item in rule_items
        if not any(_covered_by(item[0], model[0]) for model in model_items)
    ]
    return [*kept, *model_items]


def compose_result(
    document: SourceDocument,
    ref: DocumentRef,
    items: list[Item],
    *,
    max_questions: int,
) -> PlanningQuestionResult:
    ordered = order_items(items)
    omitted = max(0, len(ordered) - MAX_CANDIDATES)
    ordered = ordered[:MAX_CANDIDATES]
    questions: list[SupplementQuestion] = []
    truncated: list[str] = []
    for rank, (candidate, draft) in enumerate(ordered, start=1):
        if rank > max_questions:
            truncated.append(candidate.candidate_key)
            continue
        questions.append(
            SupplementQuestion(
                question_key=f"q-{candidate.candidate_key}",
                candidate_key=candidate.candidate_key,
                rank=rank,
                question_type=draft.question_type,
                text=draft.text,
                answer_format=draft.answer_format,
                origin=draft.origin,
                basis=candidate.status,
            )
        )
    return PlanningQuestionResult(
        document=ref,
        kind=document.kind,
        text_hash=document.text_hash,
        parser_profile=document.parser_profile,
        max_questions=max_questions,
        candidates=tuple(candidate for candidate, _ in ordered),
        questions=tuple(questions),
        truncated_candidate_keys=tuple(truncated),
        omitted_candidates=omitted,
    )


async def generate_planning_questions(
    request: PlanningQuestionRequest, proposer: CandidateProposer | None
) -> PlanningQuestionOutcome:
    """Parse, detect, verify, rank and cap; raise parser and LLM errors as is."""
    if request.mode == "MODEL" and proposer is None:
        raise ValueError("MODEL mode needs a candidate proposer")
    document = parse_source(request.to_source_input())
    ref = request.document
    items = detect_rule_candidates(document, ref)
    origin: Origin = "TEMPLATE"
    model: str | None = None
    usage: tuple[LLMUsage, ...] = ()
    if request.mode == "MODEL":
        proposal = await proposer.propose(document)
        model_items: list[Item] = []
        seen_keys: set[str] = set()
        for proposed in proposal.candidates:
            item = verify_model_candidate(document, ref, proposed)
            # The same kind and quotes describe the same gap; keep the first.
            if item[0].candidate_key in seen_keys:
                continue
            seen_keys.add(item[0].candidate_key)
            model_items.append(item)
        items = merge_items(items, model_items)
        origin, model, usage = "MODEL", proposal.model, proposal.usage
    result = compose_result(document, ref, items, max_questions=request.max_questions)
    return PlanningQuestionOutcome(result, origin, model, usage)
