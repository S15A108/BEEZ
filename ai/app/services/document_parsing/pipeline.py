import json
import re
from collections.abc import Callable
from dataclasses import dataclass

from app.schemas.documents import (
    ExcludedRange,
    SourceBlock,
    SourceDiagnostic,
    SourceDocument,
    SourceInput,
    SourceLocator,
)
from app.services.document_parsing.adapters import markdown, plain_text
from app.services.document_parsing.adapters.blocks import ParsedBlock
from app.services.document_parsing.errors import SourceParsingError
from app.services.document_parsing.normalization import (
    NORMALIZATION_VERSION,
    normalize_text,
    slice_lines,
    source_lines,
    text_digest,
)

MAX_RESULT_BYTES = 2_097_152
SUPPORTED_KINDS = frozenset({"PLAN", "REQUIREMENTS", "API_SPEC", "ERD"})


@dataclass(frozen=True)
class ParserProfile:
    media_type: str
    parser_profile: str
    parser_version: str
    parse: Callable[
        [str], tuple[list[ParsedBlock], list[ExcludedRange], list[SourceDiagnostic]]
    ]


# Exactly one parser profile per media type; the caller must echo its name.
PARSER_PROFILES: dict[str, ParserProfile] = {
    profile.media_type: profile
    for profile in (
        ParserProfile(
            "text/markdown",
            markdown.PARSER_PROFILE,
            markdown.PARSER_VERSION,
            markdown.parse_markdown,
        ),
        ParserProfile(
            "text/plain",
            plain_text.PARSER_PROFILE,
            plain_text.PARSER_VERSION,
            plain_text.parse_plain_text,
        ),
    )
}


def default_parser_profile(media_type: str) -> str:
    profile = PARSER_PROFILES.get(media_type)
    if profile is None:
        raise SourceParsingError(
            "unsupported_media_type", "Expected text/markdown or text/plain."
        )
    return profile.parser_profile


def _coverage(
    lines: list[str], blocks: list[SourceBlock], exclusions: list[ExcludedRange]
) -> tuple[ExcludedRange, ...]:
    result = list(exclusions)
    owners = [False] * len(lines)
    for item in (*blocks, *exclusions):
        locator = item.locator
        if locator.end_line > len(lines):
            raise SourceParsingError(
                "invalid_source_locator", "Block exceeds source.", locator
            )
        for index in range(locator.start_line - 1, locator.end_line):
            if owners[index]:
                raise SourceParsingError(
                    "invalid_document_syntax", "Overlapping source blocks.", locator
                )
            owners[index] = True
    run_start: int | None = None
    run_reason: str | None = None
    for index, line in enumerate(lines):
        if owners[index]:
            reason = None
        elif not line.strip(" \t\n"):
            reason = "blank"
        elif re.fullmatch(
            r"[ \t]*(?:>[ \t]*)+(?:[-+*]|[0-9]{1,9}[.)])?[ \t]*\n?|[ \t]*(?:[-+*]|[0-9]{1,9}[.)])[ \t]*\n?",
            line,
        ):
            reason = "container_marker"
        else:
            raise SourceParsingError(
                "unsupported_construct",
                "Unaccounted source content.",
                SourceLocator(start_line=index + 1, end_line=index + 1),
            )
        if run_start is not None and reason != run_reason:
            result.append(
                ExcludedRange(
                    locator=SourceLocator(start_line=run_start + 1, end_line=index),
                    reason=run_reason,
                )
            )
            run_start = None
        if run_start is None and reason is not None:
            run_start, run_reason = index, reason
    if run_start is not None:
        result.append(
            ExcludedRange(
                locator=SourceLocator(start_line=run_start + 1, end_line=len(lines)),
                reason=run_reason,
            )
        )
    return tuple(sorted(result, key=lambda x: x.locator.start_line))


def parse_source(source: SourceInput) -> SourceDocument:
    profile = PARSER_PROFILES.get(source.media_type)
    if source.kind not in SUPPORTED_KINDS or profile is None:
        raise SourceParsingError(
            "unsupported_media_type",
            "Expected supported kind + text/markdown or text/plain.",
        )
    if (
        source.parser_profile != profile.parser_profile
        or source.normalization_version != NORMALIZATION_VERSION
    ):
        raise SourceParsingError(
            "pipeline_version_mismatch", "Unsupported parser or normalization profile."
        )
    text = normalize_text(source.content)
    if text_digest(text) != source.text_hash:
        raise SourceParsingError(
            "source_hash_mismatch", "Normalized text does not match text_hash."
        )
    parsed, excluded, diagnostics = profile.parse(text)
    if not parsed:
        raise SourceParsingError(
            "no_extractable_content", "No searchable source content."
        )
    lines = source_lines(text)
    identity = [
        source.project_id,
        source.document_version_id,
        source.kind,
        source.text_hash,
        source.normalization_version,
        source.parser_profile,
        profile.parser_version,
    ]
    block_ids = [
        "blk_"
        + text_digest(
            json.dumps(
                [*identity, b.block_type, b.locator.start_line, b.locator.end_line],
                ensure_ascii=False,
                separators=(",", ":"),
            )
        )
        for b in parsed
    ]
    blocks = [
        SourceBlock(
            block_id=block_ids[index],
            block_type=block.block_type,
            locator=block.locator,
            text_hash=text_digest(slice_lines(lines, block.locator)),
            heading_path=tuple(block_ids[i] for i in block.heading_path),
            container_path=block.container_path,
            heading_level=block.heading_level,
            heading_text=block.heading_text,
            cells=block.cells,
            table_header_id=(
                block_ids[block.table_header_index]
                if block.table_header_index is not None
                else None
            ),
            info=block.info,
        )
        for index, block in enumerate(parsed)
    ]
    document = SourceDocument(
        project_id=source.project_id,
        document_version_id=source.document_version_id,
        kind=source.kind,
        media_type=profile.media_type,
        content_hash=source.content_hash,
        text_hash=source.text_hash,
        normalization_version=NORMALIZATION_VERSION,
        parser_profile=profile.parser_profile,
        parser_version=profile.parser_version,
        normalized_text=text,
        blocks=tuple(blocks),
        excluded_ranges=_coverage(lines, blocks, excluded),
        diagnostics=tuple(diagnostics),
    )
    if len(document.model_dump_json().encode("utf-8")) > MAX_RESULT_BYTES:
        raise SourceParsingError(
            "document_limit_exceeded", "Source result exceeds 2 MiB."
        )
    return document


def resolve_source(document: SourceDocument, locator: SourceLocator) -> str:
    """Resolve against this version's normalized source; never use rendered text."""
    return slice_lines(source_lines(document.normalized_text), locator)
