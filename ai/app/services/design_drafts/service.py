"""Turn a model draft into verified DRAFT items and a before/after change list (#134).

Internal interface only. The caller passes one BE-authorized document version
with its stored questions, answers and existing items. Every quote is checked
against its block, every base id and answer reference against the input, and
each draft is paired with the item it would update so the effect of the
answers can be reviewed. Nothing is stored and nothing is approved here.
"""

import hashlib
import json
from collections.abc import Sequence
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


def _json(value: Any) -> str:
    """Canonical JSON text, so `true` and `1` (equal in Python) stay distinct."""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def apply_patch(base: dict[str, Any], patch: dict[str, Any]) -> dict[str, Any]:
    """Merge one JSON object into another, key by key at every depth.

    A key the patch omits keeps its value. An object value is merged into the
    existing object (so a response code the model does not mention stays);
    an array or scalar replaces the whole value; JSON null removes the key.
    The base is never rewritten otherwise: a null-valued field it already
    holds stays as it is.
    """
    merged = dict(base)
    for name, value in patch.items():
        if value is None:
            merged.pop(name, None)
        elif isinstance(value, dict):
            current = merged.get(name)
            merged[name] = apply_patch(
                current if isinstance(current, dict) else {}, value
            )
        else:
            merged[name] = value
    return merged


def merge_content(
    base: DesignItemContent | None, *patches: dict[str, Any]
) -> DesignItemContent:
    """Apply the model's content, in order, on top of the base item's content.

    A field the model omits keeps its existing value, so contract details the
    answers never touched (parameters, responses, …) survive an update. A
    null removes a field; on a new item it is simply dropped.
    """
    merged = canonical(base) if base is not None else {}
    for patch in patches:
        merged = apply_patch(merged, patch)
    return DesignItemContent.model_validate(merged)


def draft_key(kind: str, base_id: str | None, content: DesignItemContent) -> str:
    """Content-derived key: same kind, base and content give the same key."""
    material = _json([kind, base_id, canonical(content)])
    return "draft-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:12]


def field_changes(
    before: DesignItemContent, after: DesignItemContent
) -> tuple[FieldChange, ...]:
    """Top-level content fields that differ, in name order.

    Values are compared as JSON text, and presence counts: the BE keeps an
    absent field and a null-valued one as different JSON, so removing a
    field that was null is a REMOVED change the reviewer can see.
    """
    old, new = canonical(before), canonical(after)
    changes: list[FieldChange] = []
    for name in sorted(set(old) | set(new)):
        if name not in new:
            change = "REMOVED"
        elif name not in old:
            change = "ADDED"
        elif _json(old[name]) != _json(new[name]):
            change = "CHANGED"
        else:
            continue
        changes.append(
            FieldChange(
                field=name, change=change, before=old.get(name), after=new.get(name)
            )
        )
    return tuple(changes)


def _leaves(
    patch: dict[str, Any], prefix: tuple[str, ...] = ()
) -> dict[tuple[str, ...], Any]:
    """Every value a patch assigns, by path.

    An object contributes its keys; an empty object is a value of its own,
    since it replaces whatever scalar, array or null was there.
    """
    leaves: dict[tuple[str, ...], Any] = {}
    for name, value in patch.items():
        path = (*prefix, name)
        if isinstance(value, dict) and value:
            leaves.update(_leaves(value, path))
        else:
            leaves[path] = value
    return leaves


def conflicting_fields(patches: Sequence[dict[str, Any]]) -> tuple[str, ...]:
    """Top-level fields that two proposals for one item set differently.

    A conflict is the same path given two values, or one proposal replacing
    a whole value that another sets a key inside; an empty object leaves
    room for keys set inside it, so it clashes only with a value at its own
    path. The later proposal wins in the merge, so the reviewer is told
    which fields to settle.
    """
    assigned: dict[tuple[str, ...], str] = {}
    conflicts: set[str] = set()
    for patch in patches:
        leaves = {path: _json(value) for path, value in _leaves(patch).items()}
        for path, encoded in leaves.items():
            for earlier, earlier_encoded in assigned.items():
                shorter = min(len(path), len(earlier))
                if path[:shorter] != earlier[:shorter]:
                    continue
                if path == earlier:
                    clash = encoded != earlier_encoded
                else:
                    whole = encoded if len(path) < len(earlier) else earlier_encoded
                    clash = whole != "{}"
                if clash:
                    conflicts.add(path[0])
        assigned.update(leaves)
    return tuple(sorted(conflicts))


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
    proposals: Sequence[ModelItem],
    *,
    existing: dict[str, ExistingDesignItem],
    answered: frozenset[str],
) -> DraftItem:
    """Fold the proposals for one item into a draft.

    Several proposals share a base item when the model split its changes (a
    path change and a response change, say); they are applied in order and
    every verified source, every answer reference and every rationale are
    kept, so no answer's effect loses its trace. Contradictions of the inputs
    are rejected; only unverifiable quotes are dropped.
    """
    first = proposals[0]
    base_id = first.base_design_item_version_id
    base = existing.get(base_id) if base_id is not None else None
    if base_id is not None and base is None:
        raise DraftRejected("unknown base item")
    kind = base.kind if base is not None else first.kind
    if any(
        item.kind != kind or item.base_design_item_version_id != base_id
        for item in proposals
    ):
        raise DraftRejected("mismatched base item or kind")
    try:
        content = merge_content(
            base.content if base is not None else None,
            *(item.content for item in proposals),
        )
        check_content_for_kind(kind, content)
    except (ValidationError, ValueError, TypeError):
        raise DraftRejected("malformed item content") from None
    refs: list[str] = []
    for ref in (ref for item in proposals for ref in item.answer_refs):
        if ref not in answered:
            raise DraftRejected("unknown answer reference")
        if ref not in refs:
            refs.append(ref)
    blocks = {block.block_id: block for block in document.blocks}
    titles = heading_titles(document)
    sources: list[EvidenceItem] = []
    seen: set[tuple[str, str]] = set()
    for source in (source for item in proposals for source in item.sources):
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
    rationale = " ".join(text for item in proposals if (text := item.rationale.strip()))
    return DraftItem(
        candidate_key=draft_key(kind, base_id, content),
        kind=kind,
        content=content,
        base_design_item_version_id=base_id,
        base_status=base.status if base is not None else None,
        sources=tuple(sources),
        answer_refs=tuple(refs),
        support=_support(sources, refs),
        rationale=clip(rationale),
    )


def group_proposals(items: Sequence[ModelItem]) -> list[list[ModelItem]]:
    """One group per new item, and one per base item in first-seen order."""
    groups: list[list[ModelItem]] = []
    by_base: dict[str, list[ModelItem]] = {}
    for proposed in items:
        base_id = proposed.base_design_item_version_id
        if base_id is not None and base_id in by_base:
            by_base[base_id].append(proposed)
            continue
        group = [proposed]
        if base_id is not None:
            by_base[base_id] = group
        groups.append(group)
    return groups


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
    for group in group_proposals(proposal.items):
        item = verify_item(
            document, request, group, existing=existing, answered=answered
        )
        base_id = item.base_design_item_version_id
        if base_id is not None:
            changed = field_changes(existing[base_id].content, item.content)
            conflicts = conflicting_fields([proposed.content for proposed in group])
            # An unchanged restatement is not a draft, unless the proposals
            # disagreed on the way there: the reviewer must see that.
            if not changed and not conflicts:
                continue
            change = DraftChange(
                candidate_key=item.candidate_key,
                change="UPDATE",
                base_design_item_version_id=base_id,
                before=existing[base_id].content,
                after=item.content,
                changed_fields=changed,
                conflicting_fields=conflicts,
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
