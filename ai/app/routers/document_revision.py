"""Internal scoped document revision; BE retains authorization and persistence."""

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.core.openapi import inline_request_schema
from app.routers.document_review import DocumentReviewRoute, get_review_client
from app.services.document_parsing.errors import SourceParsingError
from app.services.document_revision import RevisionRequest, generate_revision
from app.services.llm_client import LLMClient

router = APIRouter(route_class=DocumentReviewRoute)


@router.post(
    "/v1/document-revisions",
    dependencies=[Depends(require_internal_token)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {"schema": inline_request_schema(RevisionRequest)}
            },
        }
    },
)
async def document_revision(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    client: Annotated[LLMClient, Depends(get_review_client)],
):
    raw = bytearray()
    async for chunk in request.stream():
        if len(raw) + len(chunk) > 2 * 1024 * 1024:
            raise HTTPException(413, detail="file_too_large")
        raw.extend(chunk)
    body = RevisionRequest.model_validate_json(bytes(raw))
    try:
        return await generate_revision(body, client, settings.bee_default_model)
    except (SourceParsingError, ValueError):
        raise HTTPException(422, detail="invalid_ai_input") from None
