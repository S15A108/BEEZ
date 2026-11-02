"""Internal document review endpoint; authorization and persistence belong to BE."""

import re
from typing import Annotated
from urllib.parse import urlsplit
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.schemas.document_review import DocumentReviewRequest
from app.services.document_review import analyze
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider

_MAX_BODY_BYTES = 8 * 1024 * 1024


class DocumentReviewRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            request_id = request.headers.get("x-request-id", "")
            if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", request_id):
                request_id = uuid4().hex
            try:
                response = await handler(request)
            except ValidationError as exc:
                semantic = any(error["type"] == "value_error" for error in exc.errors())
                response = JSONResponse(
                    {
                        "error": {
                            "code": "invalid_ai_input"
                            if semantic
                            else "validation_failed",
                            "message": "Invalid document review request.",
                        }
                    },
                    status_code=422 if semantic else 400,
                )
            except HTTPException as exc:
                response = JSONResponse(
                    {
                        "error": {
                            "code": exc.detail,
                            "message": "Document review request rejected.",
                        }
                    },
                    status_code=exc.status_code,
                )
            except LLMError as exc:
                status = (
                    504
                    if exc.code == "model_timeout"
                    else 422
                    if exc.code == "invalid_input"
                    else 502
                )
                response = JSONResponse(exc.to_api_dict(), status_code=status)
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


router = APIRouter(route_class=DocumentReviewRoute)


def _valid_gms_endpoint(endpoint: str) -> bool:
    # The shared provider accepts arbitrary HTTPS endpoints. Document review
    # sends the GMS credential, so restrict its destination independently.
    if not isinstance(endpoint, str) or any(
        ord(c) <= 32 or ord(c) == 127 for c in endpoint
    ):
        return False
    try:
        parsed = urlsplit(endpoint)
        return (
            parsed.scheme == "https"
            and parsed.hostname == "gms.ssafy.io"
            and parsed.port in (None, 443)
            and parsed.username is None
            and parsed.password is None
            and "?" not in endpoint
            and "#" not in endpoint
            and parsed.path.endswith("/chat/completions")
        )
    except ValueError:
        return False


async def get_review_client(
    request: Request, settings: Annotated[Settings, Depends(get_settings)]
):
    """Keep one provider per request; the dependency always closes its transport."""
    if (
        settings.gms_key is None
        or not settings.gms_key.get_secret_value().strip()
        or not _valid_gms_endpoint(settings.gms_chat_endpoint)
    ):
        raise HTTPException(502, detail="model_unavailable")
    try:
        provider = OpenAICompatibleProvider(
            endpoint=settings.gms_chat_endpoint,
            api_key=settings.gms_key.get_secret_value(),
        )
    except (TypeError, ValueError):
        raise HTTPException(502, detail="model_unavailable") from None
    async with provider:
        yield LLMClient(provider)


def _request_openapi_schema():
    # The request is read as a stream to enforce the body limit.  Since FastAPI
    # does not register its model automatically, inline Pydantic's definitions:
    # a nested #/$defs reference would otherwise resolve against the OpenAPI root.
    schema = DocumentReviewRequest.model_json_schema()
    definitions = schema.pop("$defs", {})

    def inline(value):
        if isinstance(value, dict):
            if set(value) == {"$ref"} and value["$ref"].startswith("#/$defs/"):
                return inline(definitions[value["$ref"].rsplit("/", 1)[-1]])
            return {key: inline(item) for key, item in value.items()}
        if isinstance(value, list):
            return [inline(item) for item in value]
        return value

    return inline(schema)


@router.post(
    "/v1/document-reviews",
    dependencies=[Depends(require_internal_token)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {"application/json": {"schema": _request_openapi_schema()}},
        }
    },
)
async def document_review(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    client: Annotated[LLMClient, Depends(get_review_client)],
):
    raw = bytearray()
    async for chunk in request.stream():
        if len(raw) + len(chunk) > _MAX_BODY_BYTES:
            raise HTTPException(413, detail="file_too_large")
        raw.extend(chunk)
    body = DocumentReviewRequest.model_validate_json(bytes(raw))
    return await analyze(body, client, settings.bee_default_model)
