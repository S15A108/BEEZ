"""Map the internal source pipeline to the BE-facing chunk contract."""

import json

from app.schemas.document_chunks import DocumentChunksRequest
from app.schemas.documents import SourceInput
from app.schemas.retrieval import ChunkingProfile
from app.services.document_parsing.errors import SourceParsingError
from app.services.document_parsing.normalization import MAX_INPUT_BYTES
from app.services.document_parsing.pipeline import (
    default_parser_profile,
    parse_source,
)
from app.services.retrieval.chunking import TokenCounter, build_chunks
from app.services.retrieval.errors import RetrievalError

MAX_RESPONSE_BYTES = 8_388_608


def render_document_chunks(body: DocumentChunksRequest, counter: TokenCounter) -> bytes:
    try:
        if len(body.content.encode("utf-8", errors="strict")) > MAX_INPUT_BYTES:
            raise SourceParsingError("input_too_large", "Input exceeds 1 MiB.")
    except UnicodeEncodeError as exc:
        raise SourceParsingError("invalid_document_syntax", "Invalid Unicode.") from exc

    if body.chunking_profile_id != "md-structure-v1":
        raise RetrievalError(
            "chunking_profile_mismatch", "Unsupported chunking profile."
        )

    parser_profile = (
        body.parser_profile
        if body.parser_profile is not None
        else default_parser_profile(body.media_type)
    )
    source = parse_source(
        SourceInput(
            project_id=body.project_id,
            document_version_id=body.document_version_id,
            kind=body.kind,
            media_type=body.media_type,
            content=body.content,
            content_hash=body.content_hash.removeprefix("sha256:"),
            text_hash=body.text_hash.removeprefix("sha256:"),
            normalization_version=body.normalization_profile,
            parser_profile=parser_profile,
        )
    )
    profile = ChunkingProfile(
        profile_id="md-structure-v1",
        tokenizer_id=counter.tokenizer_id,
        max_tokens=600,
        max_chunks=1024,
        include_headings=False,
        include_table_headers=False,
    )
    batch = build_chunks(source, profile, counter)
    chunks = []
    for chunk in batch.chunks:
        item = chunk.model_dump(mode="json")
        item["locator"] = {
            "type": "text_lines",
            "start_line": chunk.source_spans[0].locator.start_line,
            "end_line": chunk.source_spans[-1].locator.end_line,
        }
        chunks.append(item)
    result = {
        "contract_version": "document-chunks-v1",
        "project_id": body.project_id,
        "document_version_id": body.document_version_id,
        "request_key": body.request_key,
        "text_hash": body.text_hash,
        "normalization_profile": body.normalization_profile,
        "chunking_profile_id": profile.profile_id,
        "parser_version": source.parser_version,
        "manifest": batch.manifest.model_dump(mode="json"),
        "excluded_ranges": [e.model_dump(mode="json") for e in batch.excluded_ranges],
        "diagnostics": [d.model_dump(mode="json") for d in source.diagnostics],
        "chunks": chunks,
    }
    rendered = json.dumps(
        {"result": result, "origin": "PARSER", "usage": []},
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    if len(rendered) > MAX_RESPONSE_BYTES:
        raise RetrievalError("chunk_limit_exceeded", "Response exceeds 8 MiB.")
    return rendered
