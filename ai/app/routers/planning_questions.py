"""Opt-in internal transport for #133 review candidates and supplement questions.

Disabled by default. The BE authorizes the document and owns persistence;
this route only returns candidates, questions and real model usage.
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
from app.schemas.planning_questions import PlanningQuestionRequest
from app.services.document_parsing.errors import SourceParsingError
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    OpenAICompatibleProvider,
)
from app.services.planning_questions import (
    LLMCandidateProposer,
    generate_planning_questions,
)

_GMS_CHAT = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
_MESSAGE = "Planning question request rejected."

logger = logging.getLogger(__name__)


class InvalidRequest(Exception):
    """The request body failed schema validation; the input is never echoed."""

    def __init__(self, semantic: bool) -> None:
        super().__init__("Invalid planning question request.")
        self.semantic = semantic


def _error(status: int, code: str, reason: str | None = None, locator=None):
    error = {"code": code, "message": _MESSAGE}
    if reason:
        details = {"reason": reason}
        if locator is not None:
            details["locator"] = locator.model_dump(mode="json")
        error["details"] = details
    return JSONResponse({"error": error}, status_code=status)


class PlanningQuestionRoute(APIRoute):
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
                # Only the request body maps to 400/422. A pydantic error raised
                # while composing the result is this side's fault and falls
                # through to the generic 502 below, never blamed on the caller.
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
                logger.exception("Unexpected planning question failure")
                response = _error(502, "planning_questions_failed")
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


router = APIRouter(route_class=PlanningQuestionRoute)


@dataclass(frozen=True)
class PlanningQuestionConfig:
    max_body_bytes: int
    timeout_seconds: float
    output_token_limit: int
    output_token_parameter: str


def require_planning_question_config(
    settings: Annotated[Settings, Depends(get_settings)],
) -> PlanningQuestionConfig:
    """Explicit opt-in with bounded limits; nothing is enabled by default."""
    if not settings.planning_questions_enabled:
        raise HTTPException(503, detail="planning_questions_not_configured")
    try:
        max_body_bytes = int(settings.planning_questions_max_body_bytes)
        timeout_seconds = float(settings.planning_questions_llm_timeout_seconds)
        output_token_limit = int(settings.planning_questions_llm_output_token_limit)
        output_token_parameter = settings.planning_questions_llm_output_token_parameter
    except (TypeError, ValueError, OverflowError):
        raise HTTPException(503, detail="planning_questions_not_configured") from None
    if (
        not 1024 <= max_body_bytes <= 4_194_304
        or not math.isfinite(timeout_seconds)
        or not 0 < timeout_seconds <= 120
        or not 1 <= output_token_limit <= 4096
        or output_token_parameter not in {"max_completion_tokens", "max_tokens"}
    ):
        raise HTTPException(503, detail="planning_questions_not_configured")
    return PlanningQuestionConfig(
        max_body_bytes, timeout_seconds, output_token_limit, output_token_parameter
    )


@asynccontextmanager
async def get_planning_question_client(settings: Settings):
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
    "/v1/planning-questions",
    dependencies=[
        Depends(require_internal_token),
        Depends(require_planning_question_config),
    ],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {
                    "schema": PlanningQuestionRequest.model_json_schema()
                }
            },
        }
    },
)
async def create_planning_questions(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    config: Annotated[
        PlanningQuestionConfig, Depends(require_planning_question_config)
    ],
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
        body = PlanningQuestionRequest.model_validate_json(bytes(data))
    except ValidationError as exc:
        # Model validators denote structurally valid but semantically
        # incompatible input.
        semantic = any(error["type"] == "value_error" for error in exc.errors())
        raise InvalidRequest(semantic) from None
    if body.mode == "RULES_ONLY":
        outcome = await generate_planning_questions(body, None)
        return outcome.to_api_dict()
    async with get_planning_question_client(settings) as client:
        proposer = LLMCandidateProposer(
            client,
            context=LLMContext(
                project_id=body.project_id,
                feature="DOCUMENT_PARSE",
                request_key=body.request_key,
            ),
            model=settings.bee_default_model,
            # A fresh key per actual model call, even when the BE retries.
            call_key=f"llm-planning-questions-{uuid4()}",
            timeout_seconds=config.timeout_seconds,
            output_token_limit=config.output_token_limit,
            output_token_parameter=config.output_token_parameter,
        )
        outcome = await generate_planning_questions(body, proposer)
    return outcome.to_api_dict()
