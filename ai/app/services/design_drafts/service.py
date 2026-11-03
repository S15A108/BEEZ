"""Turn a model draft into verified DRAFT items and a before/after change list (#134).

Internal interface only. The caller passes one BE-authorized document version
with its stored questions, answers and existing items. Every quote is checked
against its block, every base id and answer reference against the input, and
each draft is paired with the item it would update so the effect of the
answers can be reviewed. Nothing is stored and nothing is approved here.
"""

import hashlib
import json
from dataclasses import dataclass
from typing import Any, Literal

from pydantic import ValidationError

from app.schemas.design_drafts import (
    MAX_UNRESOLVED,
    DesignDraftRequest,
    DesignDraftResult,
    DesignItemContent,
    DraftChange,
    DraftItem,
    ExistingDesignItem,
    FieldChange,
    Support,
    check_content_for_kind,
)
from app.schemas.documents import SourceDocument
from app.schemas.planning_questions import EvidenceItem
from app.services.design_drafts.proposer import DraftProposal, DraftProposer, ModelItem
from app.services.document_parsing.pipeline import parse_source, resolve_source
from app.services.llm_client import LLMUsage
from app.services.planning_questions.rules import block_location, clip, heading_titles

# Drafts a reviewer can check against something come first; unsupported last.
_SUPPORT_ORDER = {"DOCUMENT_AND_ANSWER": 0, "ANSWER": 1, "DOCUMENT": 2, "NONE": 3}


class DraftRejected(ValueError):
    """The model output contradicts the supplied inputs (ids, kinds, keys)."""


@dataclass(frozen=True, slots=True)
class DesignDraftOutcome:
    """Result plus the common internal envelope fields (origin, model, usage)."""

    result: DesignDraftResult
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


def canonical(content: DesignItemContent) -> dict[str, Any]:
    return content.model_dump(mode="json")


def draft_key(kind: str, base_id: str | None, content: DesignItemContent) -> str:
    """Content-derived key: same kind, base and content give the same key."""
    material = json.dumps(
        [kind, base_id, canonical(content)],
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return "draft-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:12]


def field_changes(
    before: DesignItemContent, after: DesignItemContent
) -> tuple[FieldChange, ...]:
    """Top-level content fields whose JSON value differs, in name order."""
    old, new = canonical(before), canonical(after)
    return tuple(
        FieldChange(field=name, before=old.get(name), after=new.get(name))
        for name in sorted(set(old) | set(new))
        if old.get(name) != new.get(name)
    )


def _support(sources: list[EvidenceItem], refs: list[str]) -> Support:
    if sources and refs:
        return "DOCUMENT_AND_ANSWER"
    if sources:
        return "DOCUMENT"
    if refs:
        return "ANSWER"
    return "NONE"


def verify_item(
    document: SourceDocument,
    request: DesignDraftRequest,
    proposed: ModelItem,
    *,
    existing: dict[str, ExistingDesignItem],
    answered: frozenset[str],
) -> DraftItem:
    """Reject contradictions of the inputs; drop only unverifiable quotes."""
    try:
        content = DesignItemContent.model_validate(proposed.content)
        check_content_for_kind(proposed.kind, content)
    except (ValidationError, ValueError, TypeError):
        raise DraftRejected("malformed item content") from None
    base = None
    if proposed.base_design_item_version_id is not None:
        base = existing.get(proposed.base_design_item_version_id)
        if base is None or base.kind != proposed.kind:
            raise DraftRejected("unknown or mismatched base item")
    refs: list[str] = []
    for ref in proposed.answer_refs:
        if ref not in answered:
            raise DraftRejected("unknown answer reference")
        if ref not in refs:
            refs.append(ref)
    blocks = {block.block_id: block for block in document.blocks}
    titles = heading_titles(document)
    sources: list[EvidenceItem] = []
    seen: set[tuple[str, str]] = set()
    for source in proposed.sources:
        block = blocks.get(source.block_id)
        quote = source.quote.strip()
        if (
            block is None
            or not quote
            or quote not in resolve_source(document, block.locator)
            or (block.block_id, quote) in seen
        ):
            continue
        seen.add((block.block_id, quote))
        sources.append(
            EvidenceItem(
                document_id=request.document.document_id,
                document_version_id=request.document.document_version_id,
                version_no=request.document.version_no,
                block_id=block.block_id,
                locator=block.locator,
                location=block_location(document, block, titles),
                quote=quote,
            )
        )
    return DraftItem(
        candidate_key=draft_key(
            proposed.kind, proposed.base_design_item_version_id, content
        ),
        kind=proposed.kind,
        content=content,
        base_design_item_version_id=proposed.base_design_item_version_id,
        base_status=base.status if base is not None else None,
        sources=tuple(sources),
        answer_refs=tuple(refs),
        support=_support(sources, refs),
        rationale=clip(proposed.rationale.strip()),
    )


def compose_result(
    request: DesignDraftRequest, document: SourceDocument, proposal: DraftProposal
) -> DesignDraftResult:
    """Verify, diff, drop unchanged repeats, order by support and cap."""
    existing = {item.design_item_version_id: item for item in request.existing_items}
    answered = frozenset(
        entry.question.question_key
        for entry in request.questions
        if entry.status == "ANSWERED"
    )
    pairs: list[tuple[DraftItem, DraftChange]] = []
    seen_keys: set[str] = set()
    used_bases: set[str] = set()
    for proposed in proposal.items:
        item = verify_item(
            document, request, proposed, existing=existing, answered=answered
        )
        base_id = item.base_design_item_version_id
        if base_id is not None:
            changed = field_changes(existing[base_id].content, item.content)
            # An unchanged restatement is not a draft, and one draft per base.
            if not changed or base_id in used_bases:
                continue
            change = DraftChange(
                candidate_key=item.candidate_key,
                change="UPDATE",
                base_design_item_version_id=base_id,
                before=existing[base_id].content,
                after=item.content,
                changed_fields=changed,
                answer_refs=item.answer_refs,
            )
        else:
            change = DraftChange(
                candidate_key=item.candidate_key,
                change="CREATE",
                after=item.content,
                answer_refs=item.answer_refs,
            )
        if item.candidate_key in seen_keys:
            continue
        seen_keys.add(item.candidate_key)
        if base_id is not None:
            used_bases.add(base_id)
        pairs.append((item, change))
    # Stable: model order is kept inside each support level.
    pairs.sort(key=lambda pair: _SUPPORT_ORDER[pair[0].support])
    omitted = max(0, len(pairs) - request.max_items)
    pairs = pairs[: request.max_items]
    touched = {
        item.base_design_item_version_id
        for item, _ in pairs
        if item.base_design_item_version_id is not None
    }
    unresolved = tuple(
        dict.fromkeys(
            clip(text.strip()) for text in proposal.unresolved if text.strip()
        )
    )[:MAX_UNRESOLVED]
    return DesignDraftResult(
        document=request.document,
        kind=document.kind,
        text_hash=document.text_hash,
        parser_profile=document.parser_profile,
        max_items=request.max_items,
        items=tuple(item for item, _ in pairs),
        changes=tuple(change for _, change in pairs),
        untouched_existing_ids=tuple(
            item_id for item_id in existing if item_id not in touched
        ),
        unanswered_question_keys=tuple(
            entry.question.question_key
            for entry in request.questions
            if entry.status != "ANSWERED"
        ),
        unresolved=unresolved,
        omitted_items=omitted,
    )


async def generate_design_drafts(
    request: DesignDraftRequest, proposer: DraftProposer
) -> DesignDraftOutcome:
    """Parse, propose, verify and diff; raise parser and LLM errors as is."""
    document = parse_source(request.to_source_input())
    proposal = await proposer.propose(request, document)
    try:
        result = compose_result(request, document, proposal)
    except (DraftRejected, ValidationError):
        # The model contradicted the inputs: keep the real usage, mark it failed.
        raise proposal.invalid_output_error() from None
    return DesignDraftOutcome(result, "MODEL", proposal.model, proposal.usage)
