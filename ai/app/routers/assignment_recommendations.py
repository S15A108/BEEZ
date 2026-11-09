"""Opt-in internal AI-A07 endpoint; it only returns assignment suggestions."""

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
from app.core.openapi import inline_request_schema
from app.schemas.assignment_recommendations import AssignmentRequest
from app.services.assignment_recommendations import empty_result, recommend_assignments
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider

_GMS_CHAT = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
_MESSAGE = "Assignment recommendation request rejected."
logger = logging.getLogger(__name__)


class InvalidRequest(Exception):
    def __init__(self, semantic: bool) -> None:
        self.semantic = semantic


class AssignmentRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            request_id = request.headers.get("x-request-id", "")
            if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", request_id):
                request_id = uuid4().hex
            try:
                response = await handler(request)
            except InvalidRequest as exc:
                response = JSONResponse(
                    {
                        "error": {
                            "code": "invalid_ai_input"
                            if exc.semantic
                            else "validation_failed",
                            "message": _MESSAGE,
                        }
                    },
                    status_code=422 if exc.semantic else 400,
                )
            except HTTPException as exc:
                response = JSONResponse(
                    {"error": {"code": exc.detail, "message": _MESSAGE}},
                    status_code=exc.status_code,
                )
            except LLMError as exc:
                status = (
                    422
                    if exc.code == "invalid_input"
                    else 504
                    if exc.code == "model_timeout"
                    else 502
                )
                response = JSONResponse(exc.to_api_dict(), status_code=status)
            except Exception:
                logger.exception("Unexpected assignment recommendation failure")
                response = JSONResponse(
                    {
                        "error": {
                            "code": "assignment_recommendation_failed",
                            "message": _MESSAGE,
                        }
                    },
                    status_code=502,
                )
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


router = APIRouter(route_class=AssignmentRoute)


@dataclass(frozen=True)
class AssignmentConfig:
    max_body_bytes: int
    timeout_seconds: float
    output_token_limit: int


def require_assignment_config(
    settings: Annotated[Settings, Depends(get_settings)],
) -> AssignmentConfig:
    if not settings.assignment_recommendation_enabled:
        raise HTTPException(503, detail="assignment_recommendation_not_configured")
    try:
        max_body = int(settings.assignment_recommendation_max_body_bytes)
        timeout = float(settings.assignment_recommendation_timeout_seconds)
        output_limit = int(settings.assignment_recommendation_output_token_limit)
    except (TypeError, ValueError, OverflowError):
        raise HTTPException(
            503, detail="assignment_recommendation_not_configured"
        ) from None
    if (
        not 1024 <= max_body <= 4_194_304
        or not math.isfinite(timeout)
        or not 0 < timeout <= 120
        or not 1 <= output_limit <= 4096
    ):
        raise HTTPException(503, detail="assignment_recommendation_not_configured")
    return AssignmentConfig(max_body, timeout, output_limit)


@asynccontextmanager
async def get_assignment_client(settings: Settings):
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


async def _read_body(request: Request, limit: int) -> AssignmentRequest:
    if (
        request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
        != "application/json"
    ):
        raise HTTPException(415, detail="unsupported_media_type")
    data = bytearray()
    async for chunk in request.stream():
        if len(data) + len(chunk) > limit:
            raise HTTPException(413, detail="request_too_large")
        data.extend(chunk)
    try:
        return AssignmentRequest.model_validate_json(bytes(data))
    except ValidationError as exc:
        raise InvalidRequest(
            any(error["type"] == "value_error" for error in exc.errors())
        ) from None


@router.post(
    "/v1/bee/assignments",
    dependencies=[Depends(require_internal_token), Depends(require_assignment_config)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {"schema": inline_request_schema(AssignmentRequest)}
            },
        }
    },
)
async def suggest_assignments(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    config: Annotated[AssignmentConfig, Depends(require_assignment_config)],
):
    body = await _read_body(request, config.max_body_bytes)
    empty = empty_result(body)
    if empty is not None:
        return empty
    async with get_assignment_client(settings) as client:
        return await recommend_assignments(
            body,
            client,
            settings.bee_default_model,
            timeout_seconds=config.timeout_seconds,
            output_token_limit=config.output_token_limit,
        )
