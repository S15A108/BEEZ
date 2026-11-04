"""Select evidence, verify the model's answer against the inputs, compose (#135).

Internal interface only. The caller passes one BE-authorized question with
the search results, the approved items and the related tasks. Every citation
is checked against its evidence text, every item and task reference against
the input, and every checkable fact in each sentence of the answer (numbers,
keys, paths, identifiers, codes) against the quotes that sentence cites and
the items and tasks the answer references. With a claim verifier, a second
model call then judges each sentence against those quotes, which is what
catches a flipped subject or negation that keeps every token. A citation the
evidence does not hold, a fact nothing cited supports or a sentence the judge
calls unsupported rejects the whole output; an answer that names no
grounding at all is replaced by the fixed "not found" text instead of being
shown as a guess. Nothing is stored and nothing is decided here.
"""

import hashlib
import json
import re
from bisect import bisect_right
from collections.abc import Callable, Iterable, Iterator, Sequence
from dataclasses import dataclass
from functools import partial
from typing import Any, Literal, Self

from pydantic import ValidationError

from app.schemas.bee_chat import (
    MAX_NEXT_ACTIONS,
    MAX_UNRESOLVED,
    ApprovedDesignItem,
    BeeChatRequest,
    BeeChatResult,
    BeeCitation,
    ClaimVerification,
    DesignItemRef,
    InstanceEcho,
    RelatedTask,
    RetrievedEvidence,
    TaskRef,
    citation_markers,
    prompt_hash,
)
from app.schemas.design_drafts import ItemSource
from app.services.bee_chat.instance import render_system_prompt
from app.services.bee_chat.proposer import (
    MAX_SHARED_SUPPORT_CHARS,
    AnswerProposal,
    AnswerProposer,
    Claim,
    ClaimVerdicts,
    ClaimVerifier,
    clip_text,
    mark_last_failed,
)
from app.services.llm_client import LLMError, LLMUsage
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
UNGROUNDED_NOTE = (
    "모델 답변이 근거(원문 인용·승인 설계 항목·관련 작업)를 하나도 대지 않아 답변을 "
    "보류했습니다."
)

# A marker is any bracketed integer (so `[100]` is seen and rejected, not left
# in the text), with the space before and after it, so a removed marker
# leaves no double space.
_MARKER = re.compile(r"( ?)\[(\d+)\]( ?)")
# Tokens that state a checkable fact: a path (never ending in a dot, so the
# sentence's full stop stays out), a dotted or snake_case or camelCase
# identifier, a display key (TASK-001), an all-caps code (RESTRICT, POST,
# HTTP) or a number. A comma inside a number is a thousands separator only
# between groups of three digits (`1,000`); `10,20` is two numbers. ASCII
# only, so a Korean particle glued to a token is not part of it; two-letter
# units like MB are left to the quote itself. The same pattern tokenizes the
# grounding text, and tokens are compared whole.
_FACT = re.compile(
    r"/[A-Za-z0-9_{}./-]*[A-Za-z0-9_{}/-]"
    r"|[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*"
    r"|[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+"
    r"|[a-z][a-z0-9]*(?:[A-Z][a-z0-9]+)+"
    r"|[A-Z][A-Z0-9]*-\d+"
    r"|[A-Z]{3,}"
    r"|\d{1,3}(?:,\d{3})+(?:\.\d+)?"
    r"|\d+(?:\.\d+)?"
)
# The pattern backtracks across a run of letters, digits and underscores (an
# atom), so a word costs the square of its longest atom. Words are tokenized
# one at a time, and an atom longer than this is folded to a 16-letter digest
# of itself before the pattern runs: the check stays linear, the rest of the
# word (a path's other segments, a dotted identifier's other part) is still
# checked exactly, and the folded atom still counts, whole, through its
# digest. A path may be 2,000 chars (ApiPath) but its segments are short, so
# a long path is one token compared whole.
MAX_TOKEN_ATOM_CHARS = 128
_ATOM = re.compile(r"[A-Za-z0-9_]+")
_NUMBER = re.compile(r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?")
# A list ordinal or bullet at the start of a line is layout, not a fact.
_LIST_MARKER = re.compile(r"^[ \t]*(?:\d{1,3}[.)]|[-*•·])[ \t]+", re.MULTILINE)
# Within a line a sentence ends at a full stop, question or exclamation mark
# followed by spaces; "1.5초" and "e.g" do not break. Lines break on their own.
_SENTENCE_BREAK = re.compile(r"(?<=[.!?。])[ \t]+")
_LEADING_MARKERS = re.compile(r"^[ \t]*((?:\[\d+\][ \t]*)+)")


class AnswerRejected(ValueError):
    """The model output contradicts the supplied inputs.

    Unknown ids, a quote the evidence does not hold, a `[n]` outside 1..k,
    a fact nothing cited supports, a sentence the judge calls unsupported or
    a blank ANSWERED text: the answer cannot be shown and the caller may
    retry with a fresh request key.
    """


class VerdictsRejected(ValueError):
    """The judge did not return exactly one verdict per claim."""


@dataclass(frozen=True, slots=True)
class BeeChatOutcome:
    """Result plus the common internal envelope fields (origin, model, usage).

    `usage` holds the answer call first and, when a verifier ran, the judge
    call after it.
    """

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


def _canonical(token: str) -> str:
    """Numbers without thousands separators; every other token letter for letter.

    A server may tell `/api/Users` from `/api/users` and JSON tells
    `user.userId` from `user.userid`, so paths and identifiers must match
    exactly; codes and display keys are upper case by their patterns.
    """
    if _NUMBER.fullmatch(token):
        return token.replace(",", "")
    return token


def _fold_atom(match: re.Match[str]) -> str:
    """An atom as it is, or a 16-letter digest of it when it is over the cap."""
    atom = match.group()
    if len(atom) <= MAX_TOKEN_ATOM_CHARS:
        return atom
    digest = hashlib.blake2b(atom.encode("ascii"), digest_size=8).hexdigest()
    return "".join(chr(ord("A") + int(char, 16)) for char in digest)


def _word_tokens(text: str) -> list[str]:
    """Fact tokens found word by word, long atoms folded, in linear time."""
    found: list[str] = []
    for word in text.split():
        if len(word) > MAX_TOKEN_ATOM_CHARS:
            word = _ATOM.sub(_fold_atom, word)
        found.extend(_FACT.findall(word))
    return found


def fact_tokens(text: str) -> list[str]:
    """Checkable facts in an answer, in order, without markers or list numbers."""
    body = _MARKER.sub(" ", _LIST_MARKER.sub("", text))
    return list(dict.fromkeys(_word_tokens(body)))


def support_tokens(texts: Iterable[str]) -> set[str]:
    """The canonical fact tokens a set of grounding texts states."""
    return {_canonical(token) for text in texts for token in _word_tokens(text)}


def unsupported_facts(text: str, support: str) -> list[str]:
    """Facts the text states that the support text does not state.

    Both sides are tokenized the same way and compared token for token, so
    `50` is not covered by `150`, `/api/users` is not covered by
    `/api/users/{id}`, `1,000` equals `1000` and `10,20` is `10` and `20`.
    """
    known = support_tokens([support])
    return [token for token in fact_tokens(text) if _canonical(token) not in known]


def claim_segments(text: str) -> list[tuple[str, list[int]]]:
    """Sentences of an answer with the citation numbers each one carries.

    Lines are segments of their own, then each line is split at sentence
    ends. A marker that opens a later sentence of the same line belongs to
    the sentence before it ("… GET /api/users. [1]"); a marker that opens a
    line stays with that line ("[2] GET /api/list"). List numbers and
    bullets at the start of a line are layout and are dropped.
    """
    segments: list[tuple[str, list[int]]] = []
    for line in _LIST_MARKER.sub("", text).split("\n"):
        line_start = len(segments)
        for raw in _SENTENCE_BREAK.split(line):
            if not raw.strip():
                continue
            markers = citation_markers(raw)
            leading = _LEADING_MARKERS.match(raw)
            if leading and len(segments) > line_start:
                moved = citation_markers(leading.group(1))
                previous_text, previous_markers = segments[-1]
                segments[-1] = (previous_text, previous_markers + moved)
                markers = markers[len(moved) :]
                raw = raw[leading.end() :]
                if not raw.strip():
                    continue
            segments.append((raw, markers))
    return segments


# For the judge an item's content is cut field by field, shortest values
# first, so an API item's `method` and `path` survive a long `description`,
# and a plan's title, references and goal are cut to this length each; the
# whole text then fits `MAX_PAYLOAD_TEXT_CHARS` without losing a short field.
MAX_JUDGE_FIELD_CHARS = 500
MAX_JUDGE_LIST_ITEMS = 20
# A sentence longer than this is cut into pieces of at most this many chars
# at word boundaries, so a long clause is ranked piece by piece.
JUDGE_PIECE_CHARS = 120
# The least worth showing of a neighbouring sentence that does not fit whole.
MIN_CONTEXT_CHARS = 40
# A string of a content, or a word of the answer, this long or longer can
# point the judge's cut at the field, list entry or passage the answer leans on.
MIN_MENTION_CHARS = 2
_EDGE_PUNCTUATION = ".,;:!?()[]{}<>\"'`«»「」『』（）、。·…"
# Trailing Korean particles: "비밀번호는" in the answer must find "비밀번호를"
# in a description, so a word is reduced to its stem before it is looked up.
_PARTICLES = (
    "으로서",
    "에게서",
    "으로",
    "에서",
    "까지",
    "부터",
    "에게",
    "이나",
    "이든",
    "은",
    "는",
    "이",
    "가",
    "을",
    "를",
    "의",
    "에",
    "로",
    "와",
    "과",
    "도",
    "만",
    "께",
)


def _stem(word: str) -> str:
    """A word without one trailing particle, when a stem of two chars remains."""
    for particle in _PARTICLES:
        if word.endswith(particle) and len(word) - len(particle) >= MIN_MENTION_CHARS:
            return word[: -len(particle)]
    return word


def _strings(value: Any) -> Iterator[str]:
    """Every string of a content value: field names and string values."""
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from _strings(item)
    elif isinstance(value, dict):
        for name, item in value.items():
            yield name
            yield from _strings(item)


@dataclass(frozen=True, slots=True)
class Mention:
    """What the answer says, for keeping the judge's cut of a content relevant.

    `text` is the answer lower-cased and `words` its words without edge
    punctuation or a trailing particle. A content value is mentioned when
    one of its strings (a field name, an entry's text, a key) occurs in the
    answer, whatever the fact-token rules make of it, so a plain field name
    like `email` counts as much as `field_21`.
    """

    text: str
    words: tuple[str, ...]

    @classmethod
    def of(cls, answer: str) -> Self:
        body = _MARKER.sub(" ", answer).lower()
        words = {
            _stem(word)
            for word in (raw.strip(_EDGE_PUNCTUATION) for raw in body.split())
            if len(word) >= MIN_MENTION_CHARS
        }
        return cls(body, tuple(sorted(words, key=lambda word: (-len(word), word))))

    def score(self, value: Any) -> int:
        """How many distinct strings of a content value the answer contains."""
        return sum(
            1
            for leaf in set(_strings(value))
            if len(leaf) >= MIN_MENTION_CHARS and leaf.lower() in self.text
        )

    def occurrences(self, text: str) -> list[tuple[int, str]]:
        """Every place a word of the answer occurs in a text, as (position, word)."""
        lowered = text.lower()
        found: list[tuple[int, str]] = []
        for word in self.words:
            at = lowered.find(word)
            while at >= 0:
                found.append((at, word))
                at = lowered.find(word, at + len(word))
        return found


NO_MENTION = Mention("", ())

# A sentence ends at a full stop, question or exclamation mark followed by
# spaces, or at a line break.
_SENTENCE_END = re.compile(r"(?<=[.!?。])[ \t]+|\n+")


def _sentence_spans(text: str) -> list[tuple[int, int]]:
    """Start and end of each non-blank sentence of a text."""
    spans: list[tuple[int, int]] = []
    start = 0
    for match in _SENTENCE_END.finditer(text):
        spans.append((start, match.start()))
        start = match.end()
    spans.append((start, len(text)))
    return [(a, b) for a, b in spans if text[a:b].strip()]


_WORD = re.compile(r"\S+")


def _segment_spans(text: str) -> list[tuple[int, int]]:
    """Sentences, with any sentence over `JUDGE_PIECE_CHARS` cut into pieces.

    A piece ends at a space, so a clause stays readable; a single word
    longer than a piece is a piece of its own.
    """
    spans: list[tuple[int, int]] = []
    for a, b in _sentence_spans(text):
        if b - a <= JUDGE_PIECE_CHARS:
            spans.append((a, b))
            continue
        start = a
        for match in _WORD.finditer(text, a, b):
            if match.end() - start > JUDGE_PIECE_CHARS and match.start() > start:
                spans.append((start, match.start()))
                start = match.start()
        spans.append((start, b))
    return [(a, b) for a, b in spans if text[a:b].strip()]


def _judge_text(
    text: str, mention: Mention = NO_MENTION, limit: int = MAX_JUDGE_FIELD_CHARS
) -> str:
    """A text for the judge: its head, or the passages the answer leans on.

    A long description or goal is cut to `limit` chars (`MAX_JUDGE_FIELD_CHARS`
    unless the call's whole shared support must shrink). The text is read as
    segments (sentences; a long sentence in pieces of `JUDGE_PIECE_CHARS` at
    word boundaries). Each segment holding a word of the answer is kept
    together with the segment after it and the one before it (a condition
    or an exception usually sits there: "사용자는 게시글을 즉시 삭제할 수
    있다. 관리자 승인이 선행돼야 한다"), the segments sharing the most with
    the answer first, as many as fit, in their original order with `…`
    where segments were skipped. So an obligation at the end of one long
    clause is kept beside the clause's start, and a word repeated many
    times does not crowd the rare one out. The best match is kept at any
    limit, cut to it if need be, so a limit below `MIN_CONTEXT_CHARS` plus
    two (the fit may go down to `MIN_CONTEXT_CHARS`) still yields a piece.
    Without any such word the head of the text is kept.
    """
    if len(text) <= limit:
        return text
    spans = _segment_spans(text)
    hits = mention.occurrences(text) if mention.words else []
    starts = [a for a, _ in spans]
    words_in: list[set[str]] = [set() for _ in spans]
    for at, word in hits:
        k = bisect_right(starts, at) - 1
        if k >= 0 and at < spans[k][1]:
            words_in[k].add(word)
    scores = [sum(len(word) for word in words) for words in words_in]
    ranked = sorted(
        (i for i, s in enumerate(scores) if s), key=lambda i: (-scores[i], i)
    )
    if not ranked:
        return text[: limit - 1] + "…"
    budget = limit - 2
    pieces: dict[int, str] = {}
    used = 0
    for i in ranked:
        # The match itself, then the segment after it, then the one before.
        for j in (i, i + 1, i - 1):
            if j < 0 or j >= len(spans) or j in pieces:
                continue
            segment = text[spans[j][0] : spans[j][1]].strip()
            room = budget - used - (3 if pieces else 0)
            # The first piece is the best match and is always kept, cut to
            # whatever the limit allows; a neighbour needs real room.
            if pieces and room < MIN_CONTEXT_CHARS:
                break
            room = max(room, 2)
            if len(segment) <= room:
                piece = segment
            elif j == i - 1:
                piece = "…" + segment[-(room - 1) :]
            else:
                piece = segment[: room - 1] + "…"
            pieces[j] = piece
            used += len(piece) + (3 if len(pieces) > 1 else 0)
    if not pieces:
        return text[: limit - 1] + "…"
    order = sorted(pieces)
    out: list[str] = []
    previous = order[0]
    for j in order:
        if out:
            out.append(" " if j == previous + 1 else " … ")
        out.append(pieces[j])
        previous = j
    head = "…" if order[0] > 0 else ""
    tail = "…" if order[-1] < len(spans) - 1 else ""
    result = head + "".join(out) + tail
    if len(result) > limit:
        result = result[: limit - 1] + "…"
    return result


def _judge_value(
    value: Any, mention: Mention = NO_MENTION, limit: int = MAX_JUDGE_FIELD_CHARS
) -> Any:
    """Item content for the judge: cut, with what the answer mentions kept.

    Strings are cut to `limit` (the passages the answer leans on first). A
    list longer than `MAX_JUDGE_LIST_ITEMS` keeps the entries the answer
    mentions most, then its leading entries, in their order, so the 21st
    field of a data contract the answer names, `field_21` or `email`, is
    not dropped. An object lists the fields the answer mentions first and
    then its shortest values, so the final length cut only ever trims a
    long text the answer does not lean on.
    """
    if isinstance(value, str):
        return _judge_text(value, mention, limit)
    if isinstance(value, list):
        kept = list(range(len(value)))
        if len(value) > MAX_JUDGE_LIST_ITEMS:
            ranked = sorted(kept, key=lambda i: (-mention.score(value[i]), i))
            kept = sorted(ranked[:MAX_JUDGE_LIST_ITEMS])
        items = [_judge_value(value[i], mention, limit) for i in kept]
        if len(kept) < len(value):
            items.append(f"… 외 {len(value) - len(kept)}개")
        return items
    if isinstance(value, dict):
        cut = {name: _judge_value(item, mention, limit) for name, item in value.items()}
        return dict(
            sorted(
                cut.items(),
                key=lambda pair: (
                    -mention.score({pair[0]: value[pair[0]]}),
                    len(json.dumps(pair[1], ensure_ascii=False)),
                ),
            )
        )
    return value


def _judge_item_text(item: ApprovedDesignItem, mention: Mention, limit: int) -> str:
    """One referenced item for the judge, its fields cut to `limit` each."""
    content = _judge_value(item.content.model_dump(mode="json"), mention, limit)
    return clip_text(
        f"{item.key} ({item.kind}): {json.dumps(content, ensure_ascii=False)}"
    )


def _judge_task_text(task: RelatedTask, mention: Mention, limit: int) -> str:
    """One referenced task for the judge: key, status, assignee, plan fields."""
    parts = [f"{task.key} {task.status}"]
    if task.assignee_member_id:
        parts.append(f"담당 {task.assignee_member_id}")
    if task.current_plan_revision is not None:
        plan = task.current_plan_revision.plan
        parts.append(f"계획 {_judge_text(plan.title, mention, limit)}")
        if plan.design_refs:
            refs = " ".join(ref.design_item_version_id for ref in plan.design_refs)
            parts.append(f"참조 {_judge_text(refs, mention, limit)}")
        if plan.goal:
            parts.append(f"목표 {_judge_text(plan.goal, mention, limit)}")
    return clip_text(" / ".join(parts))


def reference_support(
    items: Sequence[ApprovedDesignItem],
    tasks: Sequence[RelatedTask],
    *,
    for_judge: bool = False,
    mention: Mention = NO_MENTION,
) -> list[str]:
    """One text per referenced item and task: what the whole answer may state.

    For the token check an item keeps its full content and a plan's title
    and goal are cut to what the answer model saw (`MAX_PAYLOAD_TEXT_CHARS`).
    For the judge every text must fit `MAX_PAYLOAD_TEXT_CHARS`: an item's
    content is cut field by field, keeping first what the answer mentions
    (a string of it occurs in the answer) and then its short fields (an
    API's `method` and `path` come before a long `description`), and a
    plan's title, references and goal are cut to `MAX_JUDGE_FIELD_CHARS`
    each, so the final cut never removes a short field or one the answer
    mentions. `claims_from` shrinks these further, field by field, when a
    call's shared support exceeds `MAX_SHARED_SUPPORT_CHARS` in total.
    """
    if for_judge:
        limit = MAX_JUDGE_FIELD_CHARS
        return [_judge_item_text(item, mention, limit) for item in items] + [
            _judge_task_text(task, mention, limit) for task in tasks
        ]
    texts: list[str] = []
    for item in items:
        content = json.dumps(item.content.model_dump(mode="json"), ensure_ascii=False)
        texts.append(f"{item.key} ({item.kind}): {content}")
    for task in tasks:
        parts = [f"{task.key} {task.status}"]
        if task.assignee_member_id:
            parts.append(f"담당 {task.assignee_member_id}")
        if task.current_plan_revision is not None:
            plan = task.current_plan_revision.plan
            parts.append(f"계획 {clip_text(plan.title)}")
            if plan.design_refs:
                refs = " ".join(ref.design_item_version_id for ref in plan.design_refs)
                parts.append(f"참조 {refs}")
            if plan.goal:
                parts.append(f"목표 {clip_text(plan.goal)}")
        texts.append(" / ".join(parts))
    return texts


def _fair_cap(lengths: Sequence[int], budget: int) -> int:
    """The largest per-text length such that the texts together fit the budget."""
    ordered = sorted(lengths)
    remaining = budget
    for index, length in enumerate(ordered):
        share = remaining // (len(ordered) - index)
        if length > share:
            return share
        remaining -= length
    return ordered[-1] if ordered else budget


def _fit_shared(builders: Sequence[Callable[[int], str]], budget: int) -> list[str]:
    """Shared support texts that together fit the budget.

    Each builder makes one reference text for a field limit. When the texts
    built with `MAX_JUDGE_FIELD_CHARS` exceed the budget together, the ones
    over their fair share are rebuilt with the limit halved until they fit,
    so a plan's goal keeps the passages the answer leans on instead of
    losing its tail; a text is cut outright only when no limit fits.
    """
    texts = [build(MAX_JUDGE_FIELD_CHARS) for build in builders]
    if sum(len(text) for text in texts) <= budget:
        return texts
    cap = _fair_cap([len(text) for text in texts], budget)
    fitted: list[str] = []
    for build, text in zip(builders, texts, strict=True):
        limit = MAX_JUDGE_FIELD_CHARS
        while len(text) > cap and limit > MIN_CONTEXT_CHARS:
            limit = max(MIN_CONTEXT_CHARS, limit // 2)
            text = build(limit)
        if len(text) > cap:
            text = text[: cap - 1] + "…"
        fitted.append(text)
    return fitted


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

    Rejected as contradicting the input: unknown evidence, item or task ids;
    a quote that is not an exact substring of its evidence; a `[n]` outside
    1..k (so `[0]` and `[100]` too); a blank ANSWERED text; and, sentence by
    sentence, a fact (number, key, path, identifier, code) that neither the
    quotes that sentence cites nor a referenced item or task states as the
    same whole token, so "50 MB" over a 30 MB (or 150 MB) quote never
    passes and a number from another sentence of the same chunk is no
    support. A citation no `[n]` refers to backs nothing and is dropped;
    repeated citations collapse to one number. An ANSWERED answer that then
    keeps no citation, approved item or related task is not shown: it
    becomes INSUFFICIENT_EVIDENCE with the fixed text. For the two
    non-answer statuses the model's text, citations and references are not
    used at all; only its unresolved and next_actions are. The result's
    verification level is TOKENS; `generate_bee_chat` raises it to
    ENTAILMENT after the judge call.
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
        referenced = set(citation_markers(answer.text))
        if any(not 1 <= number <= len(answer.citations) for number in referenced):
            raise AnswerRejected("marker without citation")
        mapping: dict[int, int] = {}
        numbers: dict[tuple[str, str], int] = {}
        quotes: dict[int, str] = {}
        for index, cited in enumerate(answer.citations, start=1):
            item = by_evidence[cited.evidence_id]
            quote = cited.quote.strip()
            if not quote or quote not in item.text:
                # The evidence does not say this: the claim it backs would
                # stay in the text, so the whole answer is unusable.
                raise AnswerRejected("unverifiable citation")
            quotes[index] = quote
            if index not in referenced:
                # Listed but never used by the text: it backs no claim.
                continue
            key = (item.evidence_id, quote)
            if key in numbers:
                mapping[index] = numbers[key]
                continue
            numbers[key] = mapping[index] = len(citations) + 1
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
        if citations or design_refs or task_refs:
            shared = support_tokens(
                reference_support(
                    [by_item[ref.design_item_version_id] for ref in design_refs],
                    [by_task[ref.task_id] for ref in task_refs],
                )
            )
            for segment, markers in claim_segments(answer.text):
                parts: list[str] = []
                for index in dict.fromkeys(markers):
                    chunk = by_evidence[answer.citations[index - 1].evidence_id]
                    parts.extend(
                        [quotes[index], chunk.title or "", chunk.section_title or ""]
                    )
                known = shared | support_tokens(parts)
                if any(_canonical(t) not in known for t in fact_tokens(segment)):
                    # The sentence states something its own citations and the
                    # referenced items and tasks do not say.
                    raise AnswerRejected("unsupported fact")
            text = renumber_markers(answer.text.strip(), mapping)
        else:
            status = "INSUFFICIENT_EVIDENCE"
            extra_unresolved.append(UNGROUNDED_NOTE)
    if status == "INSUFFICIENT_EVIDENCE":
        text = INSUFFICIENT_TEXT
    elif status == "OUT_OF_SCOPE":
        text = OUT_OF_SCOPE_TEXT

    # 00-common Source rows: the citations, then the stored source of each
    # referenced item when it lies inside this scope. A source outside the
    # scope (an older document version the BE preserved) stays on
    # design_refs[].source only, as the #132 packet does.
    allowed = set(request.scope.document_version_ids)
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
        source = ref.source
        if (
            source is not None
            and source.document_version_id in allowed
            and source not in sources
        ):
            sources.append(source)

    notices: list[str] = []
    if request.scope.mode == "DOCUMENT_REVIEW":
        notices.append(REVIEW_NOTICE)
    if omitted:
        notices.append(
            f"검색 결과 {omitted}건은 상한({request.max_evidence}건)으로 모델에 "
            "전달하지 않았습니다."
        )
    answered = status == "ANSWERED"
    return BeeChatResult(
        answer_status=status,
        grounded=bool(citations or design_refs or task_refs),
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
            system_prompt_hash=prompt_hash(render_system_prompt()),
        ),
        verification=ClaimVerification(
            level="TOKENS" if answered else "NONE",
            claims=len(claim_segments(text)) if answered else 0,
        ),
    )


def claims_from(
    result: BeeChatResult, request: BeeChatRequest
) -> tuple[list[Claim], list[str]]:
    """One claim per sentence of the verified answer, plus the shared references.

    A sentence with `[n]` markers cites those quotes; a sentence without
    markers may lean on every quote of the answer. The referenced approved
    items and related tasks apply to every sentence and are returned once,
    cut for the judge's payload.
    """
    by_item = {item.id: item for item in request.approved_items}
    by_task = {task.id: task for task in request.related_tasks}
    mention = Mention.of(result.text)
    builders: list[Callable[[int], str]] = [
        partial(_judge_item_text, by_item[ref.design_item_version_id], mention)
        for ref in result.design_refs
    ] + [
        partial(_judge_task_text, by_task[ref.task_id], mention)
        for ref in result.task_refs
    ]
    shared = [
        text for text in _fit_shared(builders, MAX_SHARED_SUPPORT_CHARS) if text.strip()
    ]
    every_quote = tuple(range(1, len(result.citations) + 1))
    claims: list[Claim] = []
    for segment, markers in claim_segments(result.text):
        # Markers go with their leading space, so "413입니다 [1]." stays "…413입니다."
        text = " ".join(renumber_markers(segment, {}).split())
        if not text:
            continue
        own = tuple(dict.fromkeys(markers))
        claims.append(
            Claim(index=len(claims) + 1, text=text, quotes=own or every_quote)
        )
    return claims, shared


def check_verdicts(claims: Sequence[Claim], verdicts: ClaimVerdicts) -> None:
    """Exactly one verdict per claim, and none of them NOT_SUPPORTED."""
    indexes = [verdict.index for verdict in verdicts.verdicts]
    if sorted(indexes) != list(range(1, len(claims) + 1)):
        raise VerdictsRejected("verdicts do not cover the claims once each")
    if any(verdict.verdict == "NOT_SUPPORTED" for verdict in verdicts.verdicts):
        raise AnswerRejected("claim not supported by its citations")


async def generate_bee_chat(
    request: BeeChatRequest,
    proposer: AnswerProposer,
    verifier: ClaimVerifier | None = None,
) -> BeeChatOutcome:
    """Select, propose, verify, and with a verifier judge; raise LLM errors as is.

    The usage of both calls is kept in order. A judge verdict of
    NOT_SUPPORTED rejects the answer and marks the answer call failed; a
    judge output that does not cover the claims marks the judge call failed.
    """
    evidence, omitted = select_evidence(request)
    proposal = await proposer.propose(request, evidence)
    try:
        result = verify_answer(request, evidence, omitted, proposal)
    except (AnswerRejected, ValidationError):
        # The model contradicted the inputs: keep the real usage, mark it failed.
        raise proposal.invalid_output_error() from None
    usage = proposal.usage
    if verifier is not None and result.answer_status == "ANSWERED":
        claims, shared = claims_from(result, request)
        quotes = [cited.quote for cited in result.citations]
        try:
            verdicts = await verifier.verify(request.question, quotes, shared, claims)
        except LLMError as error:
            # The answer call happened and must stay on record before the judge's.
            raise LLMError(error.code, (*proposal.usage, *error.usage)) from None
        try:
            check_verdicts(claims, verdicts)
        except VerdictsRejected:
            raise verdicts.invalid_output_error(proposal.usage) from None
        except AnswerRejected:
            raise LLMError(
                "invalid_output", (*mark_last_failed(proposal.usage), *verdicts.usage)
            ) from None
        result = result.model_copy(
            update={
                "verification": ClaimVerification(
                    level="ENTAILMENT", claims=len(claims), model=verdicts.model
                )
            }
        )
        usage = (*proposal.usage, *verdicts.usage)
    return BeeChatOutcome(result, "MODEL", proposal.model, usage)
