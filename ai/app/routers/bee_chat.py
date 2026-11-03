"""Opt-in internal transport for the #135 Bee: instance init and grounded chat.

Disabled by default. The BE authorizes the project, runs the search and owns
answer ids, feedback and any history; these routes only return the instance
profile, the verified answer with its grounding and real model usage.
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
from pydantic import BaseModel, ValidationError

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.core.openapi import inline_request_schema
from app.schemas.bee_chat import BeeChatRequest, BeeInstanceRequest
from app.services.bee_chat import LLMAnswerProposer, build_instance, generate_bee_chat
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    OpenAICompatibleProvider,
)

_GMS_CHAT = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
_MESSAGE = "Bee request rejected."

logger = logging.getLogger(__name__)


class InvalidRequest(Exception):
    """The request body failed schema validation; the input is never echoed."""

    def __init__(self, semantic: bool) -> None:
        super().__init__("Invalid bee request.")
        self.semantic = semantic


def _error(status: int, code: str):
    return JSONResponse(
        {"error": {"code": code, "message": _MESSAGE}}, status_code=status
    )


class BeeRoute(APIRoute):
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
                logger.exception("Unexpected bee failure")
                response = _error(502, "bee_chat_failed")
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


router = APIRouter(route_class=BeeRoute)


@dataclass(frozen=True)
class BeeChatConfig:
    max_body_bytes: int
    timeout_seconds: float
    output_token_limit: int
    output_token_parameter: str


def require_bee_chat_config(
    settings: Annotated[Settings, Depends(get_settings)],
) -> BeeChatConfig:
    """Explicit opt-in with bounded limits; nothing is enabled by default."""
    if not settings.bee_chat_enabled:
        raise HTTPException(503, detail="bee_chat_not_configured")
    try:
        max_body_bytes = int(settings.bee_chat_max_body_bytes)
        timeout_seconds = float(settings.bee_chat_llm_timeout_seconds)
        output_token_limit = int(settings.bee_chat_llm_output_token_limit)
        output_token_parameter = settings.bee_chat_llm_output_token_parameter
    except (TypeError, ValueError, OverflowError):
        raise HTTPException(503, detail="bee_chat_not_configured") from None
    if (
        not 1024 <= max_body_bytes <= 4_194_304
        or not math.isfinite(timeout_seconds)
        or not 0 < timeout_seconds <= 120
        or not 1 <= output_token_limit <= 4096
        or output_token_parameter not in {"max_completion_tokens", "max_tokens"}
    ):
        raise HTTPException(503, detail="bee_chat_not_configured")
    return BeeChatConfig(
        max_body_bytes, timeout_seconds, output_token_limit, output_token_parameter
    )


@asynccontextmanager
async def get_bee_chat_client(settings: Settings):
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


async def _read_body(request: Request, limit: int, model: type[BaseModel]):
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
        return model.model_validate_json(bytes(data))
    except ValidationError as exc:
        semantic = any(error["type"] == "value_error" for error in exc.errors())
        raise InvalidRequest(semantic) from None


def _request_body(model: type[BaseModel]) -> dict:
    # Self-contained: `$defs` references would dangle in the OpenAPI document
    # and break generated clients.
    return {
        "requestBody": {
            "required": True,
            "content": {"application/json": {"schema": inline_request_schema(model)}},
        }
    }


@router.post(
    "/v1/bee/instances",
    dependencies=[Depends(require_internal_token), Depends(require_bee_chat_config)],
    openapi_extra=_request_body(BeeInstanceRequest),
)
async def create_bee_instance(
    request: Request,
    config: Annotated[BeeChatConfig, Depends(require_bee_chat_config)],
):
    """PRJ-A01 처리 규칙: 프로젝트 생성 뒤 비비 논리 인스턴스를 초기화한다."""
    body = await _read_body(request, config.max_body_bytes, BeeInstanceRequest)
    instance = build_instance(body)
    return {
        "result": {"instance": instance.model_dump(mode="json")},
        "origin": "TEMPLATE",
        "usage": [],
    }


@router.post(
    "/v1/bee/chat",
    dependencies=[Depends(require_internal_token), Depends(require_bee_chat_config)],
    openapi_extra=_request_body(BeeChatRequest),
)
async def answer_bee_question(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    config: Annotated[BeeChatConfig, Depends(require_bee_chat_config)],
):
    body = await _read_body(request, config.max_body_bytes, BeeChatRequest)
    async with get_bee_chat_client(settings) as client:
        proposer = LLMAnswerProposer(
            client,
            context=LLMContext(
                project_id=body.project_id,
                feature="BEE_CHAT",
                request_key=body.request_key,
            ),
            model=settings.bee_default_model,
            # A fresh key per actual model call, even when the BE retries.
            call_key=f"llm-bee-chat-{uuid4()}",
            timeout_seconds=config.timeout_seconds,
            output_token_limit=config.output_token_limit,
            output_token_parameter=config.output_token_parameter,
        )
        outcome = await generate_bee_chat(body, proposer)
    return outcome.to_api_dict()
