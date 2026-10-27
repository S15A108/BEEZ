import bisect
import json
from typing import Protocol

from app.schemas.documents import SourceDocument, SourceLocator
from app.schemas.retrieval import (
    ChunkBatch,
    ChunkingProfile,
    ChunkManifest,
    ContextReference,
    RetrievalChunk,
    SourceSpan,
)
from app.services.document_parsing.normalization import source_lines, text_digest
from app.services.retrieval.errors import RetrievalError

CHUNKER_VERSION = "beez-structure/1.1.0"
MAX_TOKEN_CHECKS = 20_000
MAX_BATCH_BYTES = 8_388_608


class TokenCounter(Protocol):
    tokenizer_id: str
    max_input_tokens: int

    def count(self, text: str) -> int: ...


class _Source:
    def __init__(self, document: SourceDocument) -> None:
        self.document = document
        self.text = document.normalized_text
        self.starts = [0]
        for line in source_lines(self.text):
            self.starts.append(self.starts[-1] + len(line))
        self.blocks = {b.block_id: b for b in document.blocks}
        self.bounds: dict[str, tuple[int, int]] = {}
        try:
            self._validate()
        except (ValueError, IndexError, KeyError, TypeError) as exc:
            raise RetrievalError(
                "invalid_source_document", "Source snapshot failed integrity checks."
            ) from exc

    def _validate(self) -> None:
        document = self.document
        if (
            not document.blocks
            or len(self.blocks) != len(document.blocks)
            or text_digest(self.text) != document.text_hash
        ):
            raise ValueError("Invalid document hash or block IDs")
        owners = [False] * (len(self.starts) - 1)
        previous_end = 0
        for block in document.blocks:
            self.claim_lines(block.locator, owners)
            start, end = self.offsets(block.locator)
            if (
                start < previous_end
                or text_digest(self.text[start:end]) != block.text_hash
            ):
                raise ValueError("Invalid block order or hash")
            previous_end = end
            self.bounds[block.block_id] = (start, end)
            prior_level = 0
            for block_id in block.heading_path:
                heading = self.blocks[block_id]
                if (
                    heading.block_type != "heading"
                    or heading.locator.start_line > block.locator.start_line
                ):
                    raise ValueError("Invalid heading reference")
                if (
                    heading.heading_level is None
                    or not prior_level < heading.heading_level <= 6
                ):
                    raise ValueError("Invalid heading hierarchy")
                prior_level = heading.heading_level
            if block.block_type == "table_row":
                header = self.blocks[block.table_header_id]
                if (
                    header.block_type != "table_header"
                    or header.locator.end_line >= block.locator.start_line
                ):
                    raise ValueError("Invalid table header reference")
            for container in block.container_path:
                if (
                    container.locator.start_line > block.locator.start_line
                    or container.locator.end_line < block.locator.end_line
                ):
                    raise ValueError("Invalid container range")
        for excluded in document.excluded_ranges:
            self.claim_lines(excluded.locator, owners)
            if excluded.reason == "blank":
                start, end = self.offsets(excluded.locator)
                if self.text[start:end].strip(" \t\n"):
                    raise ValueError("Nonblank content excluded as whitespace")
        if not all(owners):
            raise ValueError("Incomplete source coverage")

    def offsets(self, locator: SourceLocator) -> tuple[int, int]:
        return self.starts[locator.start_line - 1], self.starts[locator.end_line]

    def claim_lines(self, locator: SourceLocator, owners: list[bool]) -> None:
        if not 1 <= locator.start_line <= locator.end_line <= len(owners):
            raise ValueError("Source range is out of bounds")
        for line in range(locator.start_line - 1, locator.end_line):
            if owners[line]:
                raise ValueError("Overlapping source ranges")
            owners[line] = True

    def span(self, block_id: str, start: int, end: int) -> SourceSpan:
        return SourceSpan(
            block_id=block_id,
            start_offset=start,
            end_offset=end,
            locator=SourceLocator(
                start_line=bisect.bisect_right(self.starts, start),
                end_line=bisect.bisect_right(self.starts, end - 1),
            ),
        )

    def read(self, span: SourceSpan) -> str:
        return self.text[span.start_offset : span.end_offset]


def resolve_span(document: SourceDocument, span: SourceSpan) -> str:
    source = _Source(document)
    if span.block_id not in source.bounds:
        raise RetrievalError("invalid_source_span", "Unknown source block.")
    start, end = source.bounds[span.block_id]
    if not start <= span.start_offset < span.end_offset <= end:
        raise RetrievalError("invalid_source_span", "Span exceeds its source block.")
    if (
        source.span(span.block_id, span.start_offset, span.end_offset).locator
        != span.locator
    ):
        raise RetrievalError(
            "invalid_source_span", "Offsets and line locator disagree."
        )
    return source.read(span)


class _Builder:
    def __init__(
        self, source: _Source, profile: ChunkingProfile, counter: TokenCounter
    ):
        self.source, self.profile, self.counter = source, profile, counter
        self.checks = 0

    def count(self, text: str) -> int:
        self.checks += 1
        if self.checks > MAX_TOKEN_CHECKS:
            raise RetrievalError(
                "chunk_limit_exceeded", "Token counting budget exceeded."
            )
        try:
            count = self.counter.count(text)
        except Exception as exc:
            raise RetrievalError("tokenizer_failure", "Token counter failed.") from exc
        if type(count) is not int or count < 0 or (text and count == 0):
            raise RetrievalError(
                "tokenizer_failure", "Token counter returned an invalid count."
            )
        return count

    def render(self, spans: tuple[SourceSpan, ...]):
        body_ids = {s.block_id for s in spans}
        refs: dict[str, str] = {}
        for span in spans:
            block = self.source.blocks[span.block_id]
            for heading in block.heading_path:
                refs[heading] = "heading"
            if block.table_header_id:
                refs[block.table_header_id] = "table_header"
        context_refs = []
        prefix_parts = []
        for block_id, role in refs.items():
            block = self.source.blocks[block_id]
            enabled = (
                self.profile.include_headings
                if role == "heading"
                else self.profile.include_table_headers
            )
            applied = enabled and block_id not in body_ids
            context_refs.append(
                ContextReference(
                    block_id=block_id,
                    role=role,
                    locator=block.locator,
                    text_hash=block.text_hash,
                    applied=applied,
                )
            )
            if applied:
                start, end = self.source.bounds[block_id]
                prefix_parts.append(self.source.text[start:end])
        prefix = "\n".join(prefix_parts) + "\n\n" if prefix_parts else ""
        if prefix and self.count(prefix) >= self.profile.max_tokens:
            raise RetrievalError(
                "context_limit_exceeded",
                "Context leaves no body budget; use a different profile.",
            )
        text = "".join(self.source.read(span) for span in spans)
        embedding_text = prefix + text
        return text, embedding_text, tuple(context_refs), self.count(embedding_text)

    def pieces(self, span: SourceSpan):
        pending = [span]
        while pending:
            current = pending.pop()
            if self.render((current,))[3] <= self.profile.max_tokens:
                yield current
                continue
            start, end = current.start_offset, current.end_offset
            if end - start == 1:
                raise RetrievalError(
                    "chunk_limit_exceeded",
                    "One code point cannot fit the configured token budget.",
                )
            middle = (start + end) // 2
            line_break = self.source.text.rfind("\n", start, middle)
            cut = line_break + 1 if line_break >= start else middle
            # Exact counting of each final piece, without assuming BPE token
            # counts are monotonic in character length or slicing token bytes.
            pending.append(self.source.span(current.block_id, cut, end))
            pending.append(self.source.span(current.block_id, start, cut))


def build_chunks(
    document: SourceDocument, profile: ChunkingProfile, token_counter: TokenCounter
) -> ChunkBatch:
    if (
        profile.tokenizer_id != token_counter.tokenizer_id
        or profile.max_tokens > token_counter.max_input_tokens
    ):
        raise RetrievalError(
            "chunking_profile_mismatch",
            "Tokenizer identity or model input limit mismatch.",
        )
    source = _Source(document)
    builder = _Builder(source, profile, token_counter)
    groups: list[tuple[SourceSpan, ...]] = []
    current: tuple[SourceSpan, ...] = ()
    current_boundary = None

    def flush():
        nonlocal current
        if current:
            groups.append(current)
            current = ()
            if len(groups) > profile.max_chunks:
                raise RetrievalError(
                    "chunk_limit_exceeded",
                    "Too many chunks; no partial batch is returned.",
                )

    for block in document.blocks:
        table_id = (
            block.block_id
            if block.block_type == "table_header"
            else block.table_header_id
        )
        containers = block.container_path
        # Siblings share the list root, while nested lists retain their parent
        # item. Older snapshots without list roots keep their original boundary.
        if (
            len(containers) >= 2
            and containers[-1].kind == "list_item"
            and containers[-2].kind in {"bullet_list", "ordered_list"}
        ):
            containers = containers[:-1]
        boundary = (block.heading_path, table_id, containers)
        if boundary != current_boundary:
            flush()
            current_boundary = boundary
        start, end = source.bounds[block.block_id]
        for span in builder.pieces(source.span(block.block_id, start, end)):
            candidate = (*current, span)
            if current and builder.render(candidate)[3] > profile.max_tokens:
                flush()
            current = (*current, span)
    flush()

    identity = [
        document.project_id,
        document.document_version_id,
        document.text_hash,
        document.parser_profile,
        document.parser_version,
        document.normalization_version,
        CHUNKER_VERSION,
        profile.model_dump(mode="json"),
    ]
    chunks = []
    for chunk_no, spans in enumerate(groups):
        text, embedding_text, refs, count = builder.render(spans)
        key = "chk_" + text_digest(
            json.dumps(
                [identity, [s.model_dump(mode="json") for s in spans]],
                sort_keys=True,
                ensure_ascii=False,
                separators=(",", ":"),
            )
        )
        chunks.append(
            RetrievalChunk(
                chunk_key=key,
                chunk_no=chunk_no,
                document_version_id=document.document_version_id,
                text=text,
                source_spans=spans,
                context_refs=refs,
                embedding_text=embedding_text,
                token_count=count,
            )
        )
    # Validate the actual subranges, not just whether each block ID appeared.
    actual = [span for chunk in chunks for span in chunk.source_spans]
    cursor = 0
    for block in document.blocks:
        start, end = source.bounds[block.block_id]
        position = start
        while cursor < len(actual) and actual[cursor].block_id == block.block_id:
            span = actual[cursor]
            if span.start_offset != position or span.end_offset > end:
                raise RetrievalError(
                    "invalid_chunk_coverage", "Overlapping or missing source range."
                )
            position, cursor = span.end_offset, cursor + 1
        if position != end:
            raise RetrievalError("invalid_chunk_coverage", "Incomplete source block.")
    if cursor != len(actual):
        raise RetrievalError("invalid_chunk_coverage", "Unexpected source span.")
    batch = ChunkBatch(
        manifest=ChunkManifest(
            project_id=document.project_id,
            document_version_id=document.document_version_id,
            content_hash=document.content_hash,
            text_hash=document.text_hash,
            normalization_version=document.normalization_version,
            parser_profile=document.parser_profile,
            parser_version=document.parser_version,
            chunker_version=CHUNKER_VERSION,
            chunking_profile=profile,
            expected_chunk_keys=tuple(c.chunk_key for c in chunks),
        ),
        chunks=tuple(chunks),
        excluded_ranges=document.excluded_ranges,
    )
    if len(batch.model_dump_json().encode("utf-8")) > MAX_BATCH_BYTES:
        raise RetrievalError("chunk_limit_exceeded", "Chunk batch exceeds 8 MiB.")
    return batch
