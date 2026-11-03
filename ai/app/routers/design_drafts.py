"""Opt-in internal transport for #134 answer-informed design item drafts.

Disabled by default. The BE authorizes the document, questions and items and
owns persistence, approval and publishing; this route only returns DRAFT
candidates, their change list and real model usage.
"""

import logging
import math
import re
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.schemas.design_drafts import DesignDraftRequest
from app.services.design_drafts import LLMDraftProposer, generate_design_drafts
from app.services.document_parsing.errors import SourceParsingError
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    OpenAICompatibleProvider,
)

_GMS_CHAT = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
_MESSAGE = "Design draft request rejected."

logger = logging.getLogger(__name__)


class InvalidRequest(Exception):
    """The request body failed schema validation; the input is never echoed."""

    def __init__(self, semantic: bool) -> None:
        super().__init__("Invalid design draft request.")
        self.semantic = semantic


def _error(status: int, code: str, reason: str | None = None, locator=None):
    error = {"code": code, "message": _MESSAGE}
    if reason:
        details = {"reason": reason}
        if locator is not None:
            details["locator"] = locator.model_dump(mode="json")
        error["details"] = details
    return JSONResponse({"error": error}, status_code=status)


class DesignDraftRoute(APIRoute):
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
            except InvalidRequest as exc:
                # Only the request body maps to 400/422; a pydantic error while
                # composing the result is this side's fault and becomes 502.
                response = (
                    _error(422, "invalid_ai_input")
                    if exc.semantic
                    else _error(400, "validation_failed")
                )
            except SourceParsingError as exc:
                if exc.code == "unsupported_media_type":
                    status, code = 415, "unsupported_media_type"
                else:
                    status, code = 422, "invalid_ai_input"
                response = _error(status, code, exc.code, exc.locator)
            except LLMError as exc:
                status = (
                    504
                    if exc.code == "model_timeout"
                    else 422
                    if exc.code == "invalid_input"
                    else 502
                )
                response = JSONResponse(exc.to_api_dict(), status_code=status)
            except Exception:
                # Never echo the payload or provider details; keep the traceback.
                logger.exception("Unexpected design draft failure")
                response = _error(502, "design_drafts_failed")
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


router = APIRouter(route_class=DesignDraftRoute)


@dataclass(frozen=True)
class DesignDraftConfig:
    max_body_bytes: int
    timeout_seconds: float
    output_token_limit: int
    output_token_parameter: str


def require_design_draft_config(
    settings: Annotated[Settings, Depends(get_settings)],
) -> DesignDraftConfig:
    """Explicit opt-in with bounded limits; nothing is enabled by default."""
    if not settings.design_drafts_enabled:
        raise HTTPException(503, detail="design_drafts_not_configured")
    try:
        max_body_bytes = int(settings.design_drafts_max_body_bytes)
        timeout_seconds = float(settings.design_drafts_llm_timeout_seconds)
        output_token_limit = int(settings.design_drafts_llm_output_token_limit)
        output_token_parameter = settings.design_drafts_llm_output_token_parameter
    except (TypeError, ValueError, OverflowError):
        raise HTTPException(503, detail="design_drafts_not_configured") from None
    if (
        not 1024 <= max_body_bytes <= 4_194_304
        or not math.isfinite(timeout_seconds)
        or not 0 < timeout_seconds <= 120
        or not 1 <= output_token_limit <= 4096
        or output_token_parameter not in {"max_completion_tokens", "max_tokens"}
    ):
        raise HTTPException(503, detail="design_drafts_not_configured")
    return DesignDraftConfig(
        max_body_bytes, timeout_seconds, output_token_limit, output_token_parameter
    )


@asynccontextmanager
async def get_design_draft_client(settings: Settings):
    if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
        raise HTTPException(502, detail="model_unavailable")
    try:
        provider = OpenAICompatibleProvider(
            endpoint=_GMS_CHAT, api_key=settings.gms_key.get_secret_value()
        )
    except ValueError:
        raise HTTPException(502, detail="model_unavailable") from None
    async with provider:
        yield LLMClient(provider)


@router.post(
    "/v1/design-drafts",
    dependencies=[
        Depends(require_internal_token),
        Depends(require_design_draft_config),
    ],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {"schema": DesignDraftRequest.model_json_schema()}
            },
        }
    },
)
async def create_design_drafts(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    config: Annotated[DesignDraftConfig, Depends(require_design_draft_config)],
):
    if (
        request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
        != "application/json"
    ):
        raise HTTPException(415, detail="unsupported_media_type")
    data = bytearray()
    async for chunk in request.stream():
        if len(data) + len(chunk) > config.max_body_bytes:
            raise HTTPException(413, detail="request_too_large")
        data.extend(chunk)
    try:
        body = DesignDraftRequest.model_validate_json(bytes(data))
    except ValidationError as exc:
        semantic = any(error["type"] == "value_error" for error in exc.errors())
        raise InvalidRequest(semantic) from None
    async with get_design_draft_client(settings) as client:
        proposer = LLMDraftProposer(
            client,
            context=LLMContext(
                project_id=body.project_id,
                feature="BEE_DRAFT",
                request_key=body.request_key,
            ),
            model=settings.bee_default_model,
            # A fresh key per actual model call, even when the BE retries.
            call_key=f"llm-design-drafts-{uuid4()}",
            timeout_seconds=config.timeout_seconds,
            output_token_limit=config.output_token_limit,
            output_token_parameter=config.output_token_parameter,
        )
        outcome = await generate_design_drafts(body, proposer)
    return outcome.to_api_dict()
