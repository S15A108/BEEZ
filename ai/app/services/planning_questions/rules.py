"""Deterministic detection of explicitly undecided, placeholder and duplicated content.

These rules need no model. Each hit cites an exact substring of one parsed
block so the BE and the reviewer can open the source lines. They only catch
what the text itself says is undecided or left to a later agreement; silence
about a topic is left to the model proposal, so rule hits never claim that a
document is complete.
"""

import re
from dataclasses import dataclass

from app.schemas.documents import SourceBlock, SourceDocument
from app.schemas.planning_questions import (
    AnswerFormat,
    ChoiceAnswerFormat,
    DocumentRef,
    EssayAnswerFormat,
    EvidenceItem,
    Origin,
    QuestionType,
    ReviewCandidate,
    ShortAnswerFormat,
)
from app.services.document_parsing.pipeline import resolve_source

MAX_QUOTE_CHARS = 1000
MAX_DUPLICATE_EVIDENCE = 5
# Same bound as schemas.planning_questions.Text; composed text never exceeds it,
# whatever the length of the source cells, headings or sentences.
MAX_TEXT_CHARS = 2000
_SHORT_QUOTE_CHARS = 120
_NAME_CHARS = 80

# Statements that say a decision, scope or value is not fixed in this document.
# Past tense ("정하지 않았다") and explicit deferrals always count; present
# tense ("정의하지 않는다") only when the sentence talks about this document,
# because "서비스는 … 확정하지 않는다" is a behaviour, not an open decision.
_UNDECIDED = re.compile(
    r"(?:정하지|결정하지|정의하지|확정하지|합의하지|명시하지|규정하지)"
    r"\s*(?:않았다|못했다|않았음|않았)"
    r"|(?:결정|확정|합의|정의)되지\s*(?:않았|못했)"
    r"|미정"
    r"|\bTBD\b|\bTODO\b"
    r"|추후\s*(?:결정|확정|협의|정)"
    r"|별도로\s*(?:정한다|결정한다|확정한다|협의한다|관리한다|검토한다)"
    r"|별도(?:의)?\s*[가-힣 ]{0,12}?(?:계약|공지|협의|정책|변경|합의|문서|논의)[가-힣]*"
    r"\s*(?:에서|에|이|가|을|를|으로)?"
    r"\s*(?:정한다|따른다|검토한다|결정한다|확정한다|협의한다)"
    r"|(?:후속|나중)\s*[가-힣 ]{0,12}?(?:계약|협의|작업|논의)[가-힣]*에서"
    r"\s*(?:정한다|결정한다|확정한다)"
)
_UNDECIDED_SCOPED = re.compile(
    r"(?:정하지|결정하지|정의하지|확정하지|합의하지|명시하지|규정하지|포함하지)"
    r"\s*(?:않는다|않음|않는)"
    r"|(?:결정|확정|합의|정의)되지\s*(?:않는|않음)"
)
_SCOPE_MARKER = re.compile(
    r"이 문서|본 문서|이 명세|이 요구사항|여기(?:에서)?는|아직|현재는"
)
_PLACEHOLDER_CELLS = frozenset(
    {"", "?", "미정", "tbd", "todo", "추후", "추후 결정", "미확정"}
)
_SENTENCE = re.compile(r"[^\n.。!?]+[.。!?]?")
_LIST_MARKER = re.compile(r"^(?:[-*+]|\d{1,9}[.)]|>)\s*")
_KEY_AT_START = re.compile(
    r"^\s*(?:(?:[-*+]|\d{1,9}[.)])\s+)?(?:>\s*)?\**([A-Z]{2,8}-\d{1,5})\**"
    r"(?=[\s:：.)\]-]|$)"
)
_KEY_CELL = re.compile(r"^[A-Z]{2,8}-\d{1,5}$")
_PROSE_BLOCKS = frozenset({"paragraph", "list_item", "heading"})


@dataclass(frozen=True, slots=True)
class QuestionDraft:
    """A question before ranking; the service assigns keys and ranks."""

    question_type: QuestionType
    text: str
    answer_format: AnswerFormat
    origin: Origin


def heading_titles(document: SourceDocument) -> dict[str, str]:
    return {
        block.block_id: (block.heading_text or "").strip()
        for block in document.blocks
        if block.block_type == "heading"
    }


def block_location(
    document: SourceDocument, block: SourceBlock, titles: dict[str, str] | None = None
) -> str:
    """Display path: enclosing heading texts, then the 1-based line range."""
    titles = heading_titles(document) if titles is None else titles
    names = [
        titles[block_id]
        for block_id in block.heading_path
        if block_id != block.block_id and titles.get(block_id)
    ]
    span = f"L{block.locator.start_line}-L{block.locator.end_line}"
    return " / ".join([*names, span])[:1000]


def shorten(quote: str) -> str:
    return (
        quote if len(quote) <= _SHORT_QUOTE_CHARS else quote[:_SHORT_QUOTE_CHARS] + "…"
    )


def _name(value: str) -> str:
    """Display form of a row or column label; long cells are cut, not dropped."""
    value = " ".join(value.split())
    return value if len(value) <= _NAME_CHARS else value[:_NAME_CHARS] + "…"


def clip(text: str) -> str:
    """Last guard so a composed summary or question always fits the schema."""
    return text if len(text) <= MAX_TEXT_CHARS else text[: MAX_TEXT_CHARS - 1] + "…"


def _evidence(
    ref: DocumentRef, block: SourceBlock, location: str, quote: str
) -> EvidenceItem:
    return EvidenceItem(
        document_id=ref.document_id,
        document_version_id=ref.document_version_id,
        version_no=ref.version_no,
        block_id=block.block_id,
        locator=block.locator,
        location=location,
        quote=quote,
    )


def _sentence_quote(sentence: str, match: re.Match[str]) -> str:
    if len(sentence) > MAX_QUOTE_CHARS:
        start = max(0, match.start() - 400)
        sentence = sentence[start : start + MAX_QUOTE_CHARS]
    return _LIST_MARKER.sub("", sentence.strip()).strip()


def _undecided(
    ref: DocumentRef, block: SourceBlock, text: str, location: str
) -> list[tuple[ReviewCandidate, QuestionDraft]]:
    hits = []
    for sentence in _SENTENCE.finditer(text):
        match = _UNDECIDED.search(sentence.group())
        if match is None and _SCOPE_MARKER.search(sentence.group()):
            match = _UNDECIDED_SCOPED.search(sentence.group())
        if match is None:
            continue
        quote = _sentence_quote(sentence.group(), match)
        if not quote or quote not in text:
            continue
        ordinal = len(hits) + 1
        short = shorten(quote)
        candidate = ReviewCandidate(
            candidate_key=f"rule-undecided-L{block.locator.start_line}-{ordinal}",
            kind="MISSING_CANDIDATE",
            status="REVIEW_CANDIDATE",
            signal="UNDECIDED_STATEMENT",
            summary=clip(
                f"문서가 아직 정하지 않았거나 이 문서 범위 밖이라고 밝힌 내용입니다: 「{short}」"
            ),
            evidence=(_evidence(ref, block, location, quote),),
        )
        draft = QuestionDraft(
            "ESSAY",
            clip(
                f"문서에 「{short}」라고 적혀 있습니다. 이 사항이 이후에 결정됐다면 결정 "
                "내용과 근거를 적어 주세요. 아직 정해지지 않았다면 '미정'이라고 적어 주세요."
            ),
            EssayAnswerFormat(),
            "TEMPLATE",
        )
        hits.append((candidate, draft))
    return hits


def _placeholder_cells(
    ref: DocumentRef,
    block: SourceBlock,
    header: SourceBlock | None,
    text: str,
    location: str,
) -> list[tuple[ReviewCandidate, QuestionDraft]]:
    hits = []
    row_text = text.strip()[:MAX_QUOTE_CHARS].strip()
    if not row_text:
        return hits
    row_label = (_name(block.cells[0]) if block.cells else "") or (
        f"L{block.locator.start_line}"
    )
    where = shorten(location)
    for index, cell in enumerate(block.cells):
        if cell.strip().lower() not in _PLACEHOLDER_CELLS:
            continue
        column = (
            _name(header.cells[index])
            if header is not None and index < len(header.cells)
            else ""
        ) or f"{index + 1}번째 열"
        candidate = ReviewCandidate(
            candidate_key=f"rule-placeholder-L{block.locator.start_line}-c{index + 1}",
            kind="MISSING_CANDIDATE",
            status="REVIEW_CANDIDATE",
            signal="PLACEHOLDER_CELL",
            summary=clip(
                f"표의 '{row_label}' 행에서 '{column}' 값이 비어 있거나 미정입니다."
            ),
            evidence=(_evidence(ref, block, location, row_text),),
        )
        draft = QuestionDraft(
            "SHORT_ANSWER",
            clip(
                f"표({where})의 '{row_label}' 행에서 '{column}' 값이 비어 있거나 "
                "미정입니다. 정해진 값이 있다면 적어 주세요. 아직 없다면 '미정'이라고 "
                "적어 주세요."
            ),
            ShortAnswerFormat(),
            "TEMPLATE",
        )
        hits.append((candidate, draft))
    return hits


def _identifier(block: SourceBlock, text: str) -> str | None:
    if block.block_type == "table_row":
        first = block.cells[0].strip() if block.cells else ""
        key = first if _KEY_CELL.fullmatch(first) else None
    elif block.block_type in {"paragraph", "list_item"}:
        match = _KEY_AT_START.match(text)
        key = match.group(1) if match else None
    else:
        key = None
    return key if key and key in text else None


def _duplicates(
    ref: DocumentRef,
    entries: dict[str, list[tuple[SourceBlock, str, str]]],
) -> list[tuple[ReviewCandidate, QuestionDraft]]:
    hits = []
    for key, blocks in entries.items():
        if len(blocks) < 2 or len({text.strip() for _, text, _ in blocks}) < 2:
            # Identical repeats are noise, not a conflict between two statements.
            continue
        evidence = tuple(
            _evidence(ref, block, location, key)
            for block, _, location in blocks[:MAX_DUPLICATE_EVIDENCE]
        )
        count = len(blocks)
        candidate = ReviewCandidate(
            candidate_key=f"rule-duplicate-{key}",
            kind="CONFLICT",
            status="REVIEW_CANDIDATE",
            signal="DUPLICATE_KEY",
            summary=f"식별자 {key}가 서로 다른 내용으로 {count}곳에 나타납니다.",
            evidence=evidence,
        )
        draft = QuestionDraft(
            "CHOICE",
            f"식별자 {key}가 서로 다른 내용으로 {count}곳에 나타납니다. 어떻게 정리할까요?",
            ChoiceAnswerFormat(
                options=(
                    "같은 항목이므로 하나로 합친다",
                    "서로 다른 항목이므로 식별자를 나눈다",
                ),
                multiple=False,
                allow_other=True,
            ),
            "TEMPLATE",
        )
        hits.append((candidate, draft))
    return hits


def detect_rule_candidates(
    document: SourceDocument, ref: DocumentRef
) -> list[tuple[ReviewCandidate, QuestionDraft]]:
    """Return rule hits in source order; every quote is resolvable to its block."""
    titles = heading_titles(document)
    headers = {
        block.block_id: block
        for block in document.blocks
        if block.block_type == "table_header"
    }
    hits: list[tuple[ReviewCandidate, QuestionDraft]] = []
    identifiers: dict[str, list[tuple[SourceBlock, str, str]]] = {}
    for block in document.blocks:
        text = resolve_source(document, block.locator)
        location = block_location(document, block, titles)
        if block.block_type in _PROSE_BLOCKS:
            hits.extend(_undecided(ref, block, text, location))
        elif block.block_type == "table_row":
            header = headers.get(block.table_header_id or "")
            hits.extend(_placeholder_cells(ref, block, header, text, location))
        key = _identifier(block, text)
        if key is not None:
            identifiers.setdefault(key, []).append((block, text, location))
    hits.extend(_duplicates(ref, identifiers))
    return hits
