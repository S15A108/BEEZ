"""Select evidence, verify the model's answer against the inputs, compose (#135).

Internal interface only. The caller passes one BE-authorized question with
the search results, the approved items and the related tasks. Every citation
is checked against its evidence text, every item and task reference against
the input, and an answer that keeps no verifiable grounding is replaced by
the fixed "not found" text instead of being shown as a guess. Nothing is
stored and nothing is decided here.
"""

import re
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Literal

from pydantic import ValidationError

from app.schemas.bee_chat import (
    MAX_NEXT_ACTIONS,
    MAX_UNRESOLVED,
    BeeChatRequest,
    BeeChatResult,
    BeeCitation,
    DesignItemRef,
    InstanceEcho,
    RetrievedEvidence,
    TaskRef,
    prompt_hash,
)
from app.schemas.design_drafts import ItemSource
from app.services.bee_chat.instance import render_system_prompt
from app.services.bee_chat.proposer import AnswerProposal, AnswerProposer
from app.services.llm_client import LLMUsage
from app.services.planning_questions.rules import clip

# Fixed texts: the screen shows these instead of a guess (BEE-A01 수정안).
INSUFFICIENT_TEXT = (
    "찾지 못했어요. 지금 승인된 문서와 설계 항목에서는 이 질문에 답할 근거를 찾지 "
    "못했습니다. 짐작해서 답하지 않습니다."
)
OUT_OF_SCOPE_TEXT = (
    "이 질문은 이 프로젝트의 문서·설계 범위 밖이라 답하지 않습니다. 프로젝트 문서나 "
    "승인 설계에 대해 물어봐 주세요."
)
REVIEW_NOTICE = "미승인 문서 검토 범위의 답변입니다. 현재 승인 설계와 다를 수 있습니다."
UNVERIFIED_NOTE = "모델 답변의 인용을 원문에서 확인하지 못해 답변을 보류했습니다."

# A marker with the space before it, so a removed marker leaves no double space.
_MARKER = re.compile(r"( ?)\[(\d{1,2})\]( ?)")


class AnswerRejected(ValueError):
    """The model output contradicts the supplied inputs (ids, blank answer)."""


@dataclass(frozen=True, slots=True)
class BeeChatOutcome:
    """Result plus the common internal envelope fields (origin, model, usage)."""

    result: BeeChatResult
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


def select_evidence(
    request: BeeChatRequest,
) -> tuple[tuple[RetrievedEvidence, ...], int]:
    """Keep the BE's ranking (its semantic top-k) and cap what the model sees."""
    selected = request.retrieved_evidence[: request.max_evidence]
    return selected, len(request.retrieved_evidence) - len(selected)


def renumber_markers(text: str, mapping: dict[int, int]) -> str:
    """Rewrite `[old]` to `[new]`; a marker of a dropped citation is removed.

    The space before a removed marker goes with it (the one after it stays),
    and a marker with nothing before it takes the space after it instead, so
    the sentence keeps single spaces.
    """

    def substitute(match: re.Match[str]) -> str:
        lead, number, trail = match.groups()
        new = mapping.get(int(number))
        if new is not None:
            return f"{lead}[{new}]{trail}"
        return trail if lead else ""

    return _MARKER.sub(substitute, text)


def evidence_location(item: RetrievedEvidence) -> str:
    """00-common Source.location for a chunk: section title, then line range."""
    span = f"L{item.locator.start_line}-L{item.locator.end_line}"
    return (f"{item.section_title} / {span}" if item.section_title else span)[:1000]


def _texts(values: Sequence[str], limit: int) -> tuple[str, ...]:
    return tuple(dict.fromkeys(clip(text.strip()) for text in values if text.strip()))[
        :limit
    ]


def verify_answer(
    request: BeeChatRequest,
    evidence: Sequence[RetrievedEvidence],
    omitted: int,
    proposal: AnswerProposal,
) -> BeeChatResult:
    """Check the answer against the inputs and compose the result.

    Unknown evidence, item or task ids contradict the input and reject the
    whole output. A quote that is not an exact substring of its evidence is
    dropped and the `[n]` markers are renumbered. An ANSWERED answer that
    keeps neither a citation nor an approved item is not shown: it becomes
    INSUFFICIENT_EVIDENCE with the fixed text, so the screen never shows a
    guess. For the two non-answer statuses the model's text, citations and
    references are not used at all; only its unresolved and next_actions are.
    """
    answer = proposal.answer
    by_evidence = {item.evidence_id: item for item in evidence}
    by_item = {item.id: item for item in request.approved_items}
    by_task = {task.id: task for task in request.related_tasks}
    if any(c.evidence_id not in by_evidence for c in answer.citations):
        raise AnswerRejected("unknown evidence id")
    if any(ref not in by_item for ref in answer.design_refs):
        raise AnswerRejected("unknown design item version")
    if any(ref not in by_task for ref in answer.task_refs):
        raise AnswerRejected("unknown task")

    status = answer.status
    citations: list[BeeCitation] = []
    design_refs: list[DesignItemRef] = []
    task_refs: list[TaskRef] = []
    extra_unresolved: list[str] = []
    text = ""
    if status == "ANSWERED":
        if not answer.text.strip():
            raise AnswerRejected("blank answer")
        mapping: dict[int, int] = {}
        seen: set[tuple[str, str]] = set()
        for index, cited in enumerate(answer.citations, start=1):
            item = by_evidence[cited.evidence_id]
            quote = cited.quote.strip()
            if not quote or quote not in item.text or (item.evidence_id, quote) in seen:
                continue
            seen.add((item.evidence_id, quote))
            mapping[index] = len(citations) + 1
            claim = (cited.claim or "").strip()
            citations.append(
                BeeCitation(
                    n=len(citations) + 1,
                    evidence_id=item.evidence_id,
                    document_id=item.document_id,
                    document_version_id=item.document_version_id,
                    version_no=item.version_no,
                    chunk_key=item.chunk_key,
                    locator=item.locator,
                    quote=quote,
                    title=item.title,
                    section_title=item.section_title,
                    claim=clip(claim) if claim else None,
                )
            )
        for ref in dict.fromkeys(answer.design_refs):
            item = by_item[ref]
            design_refs.append(
                DesignItemRef(
                    design_item_id=item.design_item_id,
                    design_item_version_id=item.id,
                    key=item.key,
                    kind=item.kind,
                    version_no=item.version_no,
                    source=item.source,
                )
            )
        for ref in dict.fromkeys(answer.task_refs):
            task = by_task[ref]
            task_refs.append(TaskRef(task_id=task.id, key=task.key, status=task.status))
        if citations or design_refs:
            text = renumber_markers(answer.text.strip(), mapping)
        else:
            status = "INSUFFICIENT_EVIDENCE"
            design_refs, task_refs = [], []
            extra_unresolved.append(UNVERIFIED_NOTE)
    if status == "INSUFFICIENT_EVIDENCE":
        text = INSUFFICIENT_TEXT
    elif status == "OUT_OF_SCOPE":
        text = OUT_OF_SCOPE_TEXT

    sources: list[ItemSource] = []
    for cited in citations:
        source = ItemSource(
            document_id=cited.document_id,
            document_version_id=cited.document_version_id,
            version_no=cited.version_no,
            location=evidence_location(by_evidence[cited.evidence_id]),
        )
        if source not in sources:
            sources.append(source)
    for ref in design_refs:
        if ref.source is not None and ref.source not in sources:
            sources.append(ref.source)

    notices: list[str] = []
    if request.scope.mode == "DOCUMENT_REVIEW":
        notices.append(REVIEW_NOTICE)
    if omitted:
        notices.append(
            f"검색 결과 {omitted}건은 상한({request.max_evidence}건)으로 모델에 "
            "전달하지 않았습니다."
        )
    project = request.instance.project if request.instance is not None else None
    return BeeChatResult(
        answer_status=status,
        grounded=bool(citations or design_refs),
        text=text,
        citations=tuple(citations),
        sources=tuple(sources),
        design_refs=tuple(design_refs),
        task_refs=tuple(task_refs),
        unresolved=_texts(
            [*request.unresolved, *answer.unresolved, *extra_unresolved],
            MAX_UNRESOLVED,
        ),
        next_actions=_texts(answer.next_actions, MAX_NEXT_ACTIONS),
        notices=tuple(notices),
        scope=request.scope,
        embedding_profile_id=request.embedding_profile_id,
        evidence_considered=len(evidence),
        evidence_omitted=omitted,
        instance=None
        if request.instance is None
        else InstanceEcho(
            profile_version=request.instance.profile_version,
            system_prompt_hash=prompt_hash(render_system_prompt(project)),
        ),
    )


async def generate_bee_chat(
    request: BeeChatRequest, proposer: AnswerProposer
) -> BeeChatOutcome:
    """Select, propose, verify; raise LLM errors as is."""
    evidence, omitted = select_evidence(request)
    proposal = await proposer.propose(request, evidence)
    try:
        result = verify_answer(request, evidence, omitted, proposal)
    except (AnswerRejected, ValidationError):
        # The model contradicted the inputs: keep the real usage, mark it failed.
        raise proposal.invalid_output_error() from None
    return BeeChatOutcome(result, "MODEL", proposal.model, proposal.usage)
