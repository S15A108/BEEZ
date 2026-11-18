from hashlib import sha256

from app.schemas.documents import SourceLocator
from app.services.document_parsing.errors import SourceParsingError

NORMALIZATION_VERSION = "utf8-lf-v1"
MAX_INPUT_BYTES = 1_048_576
MAX_TEXT_CHARS = 100_000


def text_digest(text: str) -> str:
    return sha256(text.encode("utf-8")).hexdigest()


def normalize_text(content: str) -> str:
    """BE decodes original bytes strictly; this checks the delivered text again."""
    if len(content) > MAX_INPUT_BYTES:
        raise SourceParsingError("document_limit_exceeded", "Input exceeds 1 MiB.")
    try:
        size = len(content.encode("utf-8", errors="strict"))
    except UnicodeEncodeError as exc:
        raise SourceParsingError(
            "invalid_document_syntax", "Content is not valid Unicode for UTF-8."
        ) from exc
    if size > MAX_INPUT_BYTES:
        raise SourceParsingError("document_limit_exceeded", "Input exceeds 1 MiB.")
    if "\x00" in content:
        raise SourceParsingError(
            "invalid_document_syntax", "NUL would be replaced by the Markdown parser."
        )
    text = content.removeprefix("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    if len(text) > MAX_TEXT_CHARS:
        raise SourceParsingError(
            "document_limit_exceeded", "Normalized text exceeds 100,000 characters."
        )
    return text


def source_lines(text: str) -> list[str]:
    # str.splitlines also splits Unicode separators, unlike Markdown Token.map.
    pieces = text.split("\n")
    lines = [piece + "\n" for piece in pieces[:-1]]
    if pieces[-1]:
        lines.append(pieces[-1])
    return lines


def slice_lines(lines: list[str], locator: SourceLocator) -> str:
    if locator.end_line > len(lines):
        raise SourceParsingError(
            "invalid_source_locator", "Locator is outside the source.", locator
        )
    return "".join(lines[locator.start_line - 1 : locator.end_line])
