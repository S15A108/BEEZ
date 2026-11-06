"""Expose selectable parser blocks for a BE-authorized document snapshot.

This internal endpoint does not check membership or current approval. The BE
must enforce both and repeat its current-version check when generating a draft.
"""

import asyncio
import json

from fastapi import APIRouter, Depends, HTTPException, Request

from app.core.internal_auth import require_internal_token
from app.core.openapi import inline_request_schema
from app.routers.document_review import DocumentReviewRoute
from app.schemas.documents import SourceInput
from app.services.document_parsing.errors import SourceParsingError
from app.services.document_parsing.pipeline import parse_source, resolve_source

_MAX_BODY_BYTES = 2 * 1024 * 1024
_MAX_RESPONSE_BYTES = 2 * 1024 * 1024

router = APIRouter(route_class=DocumentReviewRoute)


def _blocks(source: SourceInput) -> dict:
    try:
        document = parse_source(source)
        # The revision endpoint accepts only the normalized snapshot, so do
        # not return selectable IDs for input it would later reject.
        if document.normalized_text != source.content:
            raise ValueError("source must be normalized")
        response = {
            "document_version_id": document.document_version_id,
            "text_hash": document.text_hash,
            "normalization_version": document.normalization_version,
            "parser_profile": document.parser_profile,
            "parser_version": document.parser_version,
            "blocks": [
                {
                    "block_id": block.block_id,
                    "block_type": block.block_type,
                    "locator": block.locator.model_dump(mode="json"),
                    "text": resolve_source(document, block.locator),
                }
                for block in document.blocks
            ],
        }
        if (
            len(json.dumps(response, ensure_ascii=False).encode("utf-8"))
            > _MAX_RESPONSE_BYTES
        ):
            raise ValueError("response exceeds size limit")
        return response
    except (SourceParsingError, ValueError, UnicodeError):
        raise HTTPException(422, detail="invalid_ai_input") from None


@router.post(
    "/v1/document-revision-blocks",
    dependencies=[Depends(require_internal_token)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {"schema": inline_request_schema(SourceInput)}
            },
        }
    },
)
async def document_revision_blocks(request: Request):
    raw = bytearray()
    async for chunk in request.stream():
        if len(raw) + len(chunk) > _MAX_BODY_BYTES:
            raise HTTPException(413, detail="file_too_large")
        raw.extend(chunk)
    source = SourceInput.model_validate_json(bytes(raw))
    return await asyncio.to_thread(_blocks, source)
