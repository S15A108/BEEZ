"""CommonMark + pipe tables, with fail-closed structural validation.

No HTML renderer, code execution, URL retrieval, or semantic fact extraction.
"""

import re
from importlib.metadata import version

from markdown_it import MarkdownIt
from markdown_it.rules_block.fence import fence
from markdown_it.rules_block.state_block import StateBlock
from markdown_it.rules_block.table import escapedSplit, getLine, table
from markdown_it.token import Token

from app.schemas.documents import (
    ExcludedRange,
    SourceContainer,
    SourceDiagnostic,
    SourceLocator,
)
from app.services.document_parsing.adapters.blocks import ParsedBlock
from app.services.document_parsing.errors import SourceParsingError

PARSER_PROFILE = "markdown-v1"
PARSER_VERSION = f"beez-markdown/1.1.0+markdown-it-py/{version('markdown-it-py')}"
MAX_AST_NODES = 10_000
MAX_AST_DEPTH = 32
MAX_TABLE_COLUMNS = 128


def _locator(token: Token) -> SourceLocator:
    if token.map is None or token.map[0] >= token.map[1]:
        raise SourceParsingError("invalid_document_syntax", "Missing source range.")
    return SourceLocator(start_line=token.map[0] + 1, end_line=token.map[1])


def _guard_block(state: StateBlock, start: int, end: int, silent: bool) -> bool:
    if state.level >= MAX_AST_DEPTH or len(state.tokens) > MAX_AST_NODES:
        raise SourceParsingError(
            "document_limit_exceeded",
            "Markdown structure exceeds the parser budget.",
            SourceLocator(start_line=start + 1, end_line=start + 1),
        )
    return False


def _cells(line: str) -> list[str]:
    # Reuse the pinned parser's GFM escaping rules, including pipes in code spans.
    cells = escapedSplit(line.strip())
    if cells and cells[0] == "":
        cells.pop(0)
    if cells and cells[-1] == "":
        cells.pop()
    return cells


def _strict_table(state: StateBlock, start: int, end: int, silent: bool) -> bool:
    matched = table(state, start, end, silent)
    if not matched or silent:
        return matched
    # Run while list/blockquote offsets are still active. Ordinary table parsing
    # silently drops extra cells and pads missing cells; neither is safe evidence.
    width = len(_cells(getLine(state, start)))
    if width > MAX_TABLE_COLUMNS:
        raise SourceParsingError(
            "document_limit_exceeded",
            "Too many table columns.",
            SourceLocator(start_line=start + 1, end_line=start + 1),
        )
    for line_no in range(start + 2, state.line):
        if len(_cells(getLine(state, line_no))) != width:
            raise SourceParsingError(
                "invalid_document_syntax",
                "Table row must match its header width.",
                SourceLocator(start_line=line_no + 1, end_line=line_no + 1),
            )
    return True


def _validate_tokens(tokens: list[Token]) -> None:
    count = 0
    pending = [(token, 0, token.map) for token in reversed(tokens)]
    while pending:
        token, depth, source_map = pending.pop()
        count += 1
        if (
            count > MAX_AST_NODES
            or depth > MAX_AST_DEPTH
            or token.level > MAX_AST_DEPTH
        ):
            raise SourceParsingError("document_limit_exceeded", "AST budget exceeded.")
        if token.type in {"html_block", "html_inline", "image"}:
            locator = (
                SourceLocator(start_line=source_map[0] + 1, end_line=source_map[1])
                if source_map
                else None
            )
            raise SourceParsingError(
                "unsupported_construct",
                "HTML and images require a separate input profile.",
                locator,
            )
        pending.extend(
            (child, depth + 1, child.map or source_map)
            for child in reversed(token.children or [])
        )


def _strict_fence(state: StateBlock, start: int, end: int, silent: bool) -> bool:
    matched = fence(state, start, end, silent)
    if matched and not silent:
        token = state.tokens[-1]
        # A real closer is included in map but excluded from content. Reuse the
        # parser's container/indent handling, rather than guessing from backticks.
        body_through_end = state.getLines(
            start + 1, state.line, state.sCount[start], True
        )
        if token.content == body_through_end:
            raise SourceParsingError(
                "invalid_document_syntax", "Unclosed code fence.", _locator(token)
            )
    return matched


def _reject_malformed_table(token: Token) -> None:
    for line in token.content.split("\n"):
        cells = _cells(line)
        if (
            "|" in line
            and cells
            and all(re.fullmatch(r":?-+:?", cell.strip()) for cell in cells)
        ):
            raise SourceParsingError(
                "invalid_document_syntax",
                "Table delimiter does not match a valid header.",
                _locator(token),
            )


def parse_markdown(
    text: str,
) -> tuple[list[ParsedBlock], list[ExcludedRange], list[SourceDiagnostic]]:
    parser = MarkdownIt(
        "commonmark", {"html": True, "inline_definitions": True, "maxNesting": 64}
    ).enable("table")
    # HTML must be tokenized so it can be rejected, never rendered as safe text.
    parser.block.ruler.before("table", "source_budget", _guard_block)
    parser.block.ruler.at("table", _strict_table, {"alt": ["paragraph", "reference"]})
    parser.block.ruler.at(
        "fence",
        _strict_fence,
        {"alt": ["paragraph", "reference", "blockquote", "list"]},
    )
    env = {}
    tokens = parser.parse(text, env)
    _validate_tokens(tokens)
    blocks: list[ParsedBlock] = []
    excluded: list[ExcludedRange] = []
    headings: list[tuple[int, int]] = []
    containers: list[SourceContainer] = []
    heading_snapshots: list[list[tuple[int, int]]] = []
    header_index: int | None = None
    in_header = False
    row_token: Token | None = None
    row_cells: list[str] = []

    def append(block_type: str, locator: SourceLocator, **metadata) -> None:
        blocks.append(
            ParsedBlock(
                block_type,
                locator,
                tuple(index for _, index in headings),
                tuple(containers),
                **metadata,
            )
        )

    for index, token in enumerate(tokens):
        if token.type in {
            "bullet_list_open",
            "ordered_list_open",
            "list_item_open",
            "blockquote_open",
        }:
            containers.append(
                SourceContainer(
                    kind=token.type.removesuffix("_open"), locator=_locator(token)
                )
            )
            heading_snapshots.append(headings.copy())
        elif token.type in {
            "bullet_list_close",
            "ordered_list_close",
            "list_item_close",
            "blockquote_close",
        }:
            containers.pop()
            headings = heading_snapshots.pop()
        elif token.type == "heading_open":
            level = int(token.tag[1:])
            while headings and headings[-1][0] >= level:
                headings.pop()
            headings.append((level, len(blocks)))
            append(
                "heading",
                _locator(token),
                heading_level=level,
                heading_text=tokens[index + 1].content,
            )
        elif token.type == "paragraph_open":
            _reject_malformed_table(tokens[index + 1])
            block_type = (
                "list_item"
                if any(c.kind == "list_item" for c in containers)
                else "paragraph"
            )
            append(block_type, _locator(token))
        elif token.type in {"fence", "code_block"}:
            append("code_block", _locator(token), info=token.info or None)
        elif token.type == "definition":
            append("reference_definition", _locator(token))
        elif token.type == "hr":
            excluded.append(
                ExcludedRange(locator=_locator(token), reason="thematic_break")
            )
        elif token.type == "table_open":
            header_index = None
        elif token.type == "thead_open":
            in_header = True
        elif token.type == "thead_close":
            in_header = False
        elif token.type == "tr_open":
            row_token, row_cells = token, []
        elif token.type == "inline" and row_token is not None:
            row_cells.append(token.content)
        elif token.type == "tr_close":
            locator = _locator(row_token)
            if in_header:
                header_index = len(blocks)
                locator = SourceLocator(
                    start_line=locator.start_line, end_line=locator.end_line + 1
                )
                append("table_header", locator, cells=tuple(row_cells))
            else:
                append(
                    "table_row",
                    locator,
                    cells=tuple(row_cells),
                    table_header_index=header_index,
                )
            row_token = None

    # Reference definitions have no rendered output, but must remain searchable.
    diagnostics = [
        SourceDiagnostic(
            code="duplicate_reference",
            message="Duplicate link definition preserved; CommonMark resolves the first.",
            locator=SourceLocator(start_line=ref["map"][0] + 1, end_line=ref["map"][1]),
        )
        for ref in env.get("duplicate_refs", [])
    ]
    return blocks, excluded, diagnostics
