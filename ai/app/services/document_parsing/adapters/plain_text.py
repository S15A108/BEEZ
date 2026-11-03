"""Plain text as blank-line separated paragraphs, with no markup interpretation.

Nothing is read as HTML, Markdown, an image or a link, so no construct is
unsupported: every non-blank line is searchable source exactly as written.
"""

from app.schemas.documents import ExcludedRange, SourceDiagnostic, SourceLocator
from app.services.document_parsing.adapters.blocks import ParsedBlock
from app.services.document_parsing.errors import SourceParsingError
from app.services.document_parsing.normalization import source_lines

PARSER_PROFILE = "plain-v1"
PARSER_VERSION = "beez-plain/1.0.0"
MAX_BLOCKS = 4_000


def is_blank(line: str) -> bool:
    # Same rule as the shared coverage check and the chunker's blank-range audit.
    return not line.strip(" \t\n")


def parse_plain_text(
    text: str,
) -> tuple[list[ParsedBlock], list[ExcludedRange], list[SourceDiagnostic]]:
    blocks: list[ParsedBlock] = []
    first: int | None = None
    # A trailing blank sentinel closes the final paragraph.
    for index, line in enumerate((*source_lines(text), "\n")):
        if not is_blank(line):
            if first is None:
                first = index
            continue
        if first is None:
            continue
        locator = SourceLocator(start_line=first + 1, end_line=index)
        if len(blocks) >= MAX_BLOCKS:
            raise SourceParsingError(
                "document_limit_exceeded", "Too many paragraphs.", locator
            )
        blocks.append(ParsedBlock("paragraph", locator, (), ()))
        first = None
    # Blank lines are reported by the shared coverage check; nothing else is excluded.
    return blocks, [], []
