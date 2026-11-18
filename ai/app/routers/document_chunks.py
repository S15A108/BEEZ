"""Authenticated, bounded HTTP access to the document chunking pipeline."""

import asyncio
import json
import re
from functools import lru_cache
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import ValidationError

from app.core.internal_auth import require_internal_token
from app.schemas.document_chunks import DocumentChunksRequest
from app.services.document_parsing.errors import SourceParsingError
from app.services.retrieval.document_chunks import render_document_chunks
from app.services.retrieval.errors import RetrievalError
from app.services.retrieval.tokenization import EmbeddingTokenCounter

MAX_BODY_BYTES = 8_388_608
PROCESSING_TIMEOUT_SECONDS = 10.0


class InvalidDocumentJson(ValueError):
    """The request body could not be decoded as JSON."""


def _error(status: int, code: str, reason: str | None = None, locator=None):
    error = {"code": code, "message": "Document chunking request failed."}
    if reason:
        details = {"reason": reason}
        if locator is not None:
            details["locator"] = locator.model_dump(mode="json")
        error["details"] = details
    return JSONResponse({"error": error}, status_code=status)


class DocumentChunksRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            request_id = request.headers.get("x-request-id", "")
            if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", request_id):
                request_id = uuid4().hex
            try:
                response = await handler(request)
            except HTTPException as exc:
                response = _error(exc.status_code, str(exc.detail))
            except (
                ValidationError,
                InvalidDocumentJson,
                UnicodeDecodeError,
                RecursionError,
            ):
                response = _error(400, "validation_failed")
            except SourceParsingError as exc:
                if exc.code == "input_too_large":
                    status, code = 413, "file_too_large"
                elif exc.code == "unsupported_media_type":
                    status, code = 415, "unsupported_media_type"
                else:
                    status, code = 422, "invalid_ai_input"
                response = _error(status, code, exc.code, exc.locator)
            except RetrievalError as exc:
                status, code = (
                    (503, "processing_unavailable")
                    if exc.code == "tokenizer_failure"
                    else (422, "invalid_ai_input")
                )
                response = _error(status, code, exc.code)
            except TimeoutError:
                response = _error(504, "processing_timeout")
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


@lru_cache
def get_chunk_counter() -> EmbeddingTokenCounter:
    try:
        return EmbeddingTokenCounter()
    except Exception as exc:
        raise HTTPException(503, detail="processing_unavailable") from exc


def _parse_and_render(raw: bytes, counter: EmbeddingTokenCounter) -> bytes:
    text = raw.decode("utf-8", errors="strict")
    try:
        payload = json.loads(text)
    except ValueError as exc:
        raise InvalidDocumentJson from exc
    body = DocumentChunksRequest.model_validate(payload)
    return render_document_chunks(body, counter)


router = APIRouter(route_class=DocumentChunksRoute)


@router.post(
    "/v1/document-chunks",
    dependencies=[Depends(require_internal_token)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {
                    "schema": DocumentChunksRequest.model_json_schema()
                }
            },
        }
    },
)
async def create_document_chunks(
    request: Request,
    counter: Annotated[EmbeddingTokenCounter, Depends(get_chunk_counter)],
) -> Response:
    raw = bytearray()
    async for part in request.stream():
        if len(raw) + len(part) > MAX_BODY_BYTES:
            raise HTTPException(413, detail="file_too_large")
        raw.extend(part)
    async with asyncio.timeout(PROCESSING_TIMEOUT_SECONDS):
        rendered = await asyncio.to_thread(_parse_and_render, bytes(raw), counter)
    return Response(content=rendered, media_type="application/json")
