"""Internal task plan draft endpoint (S15P31A108-385).

The BE authorizes the caller, picks the approved document excerpts and the
approved design items, and decides what is shown or stored. This route only
turns those inputs into a verified draft and reports the real model usage.
"""

import logging
import math
import re
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
from app.routers.document_review import get_review_client
from app.schemas.task_plan_drafts import TaskPlanDraftRequest
from app.services.llm_client import LLMClient, LLMContext, LLMError
from app.services.task_plan_drafts import LLMPlanProposer, generate_task_plan_draft
from app.services.task_plan_drafts.limits import DraftOutputLimits

_MESSAGE = "Task plan draft request rejected."

logger = logging.getLogger(__name__)


class InvalidRequest(Exception):
    """The request body failed schema validation; the input is never echoed."""

    def __init__(self, semantic: bool) -> None:
        super().__init__("Invalid task plan draft request.")
        self.semantic = semantic


def _error(status: int, code: str):
    return JSONResponse(
        {"error": {"code": code, "message": _MESSAGE}}, status_code=status
    )


class TaskPlanDraftRoute(APIRoute):
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
                logger.exception("Unexpected task plan draft failure")
                response = _error(502, "task_plan_draft_failed")
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


router = APIRouter(route_class=TaskPlanDraftRoute)


@dataclass(frozen=True)
class TaskPlanDraftConfig:
    max_body_bytes: int
    timeout_seconds: float
    output_token_limit: int
    output_token_parameter: str
    reasoning_effort: str
    output_limits: DraftOutputLimits
    input_token_limit: int
    input_reserve_tokens: int


def require_task_plan_draft_config(
    settings: Annotated[Settings, Depends(get_settings)],
) -> TaskPlanDraftConfig:
    """Bounded limits parsed per request; a bad value is a configuration error."""
    try:
        max_body_bytes = int(settings.task_plan_drafts_max_body_bytes)
        timeout_seconds = float(settings.task_plan_drafts_llm_timeout_seconds)
        output_token_limit = int(settings.task_plan_drafts_llm_output_token_limit)
        output_token_parameter = settings.task_plan_drafts_llm_output_token_parameter
        reasoning_effort = settings.task_plan_drafts_llm_reasoning_effort
        output_limits = DraftOutputLimits.from_settings(settings)
        input_token_limit = int(settings.task_plan_drafts_input_token_limit)
        input_reserve_tokens = int(settings.task_plan_drafts_input_reserve_tokens)
    except (TypeError, ValueError, OverflowError):
        raise HTTPException(503, detail="task_plan_drafts_not_configured") from None
    if (
        not 1024 <= max_body_bytes <= 4_194_304
        or not math.isfinite(timeout_seconds)
        or not 0 < timeout_seconds <= 120
        or output_token_limit < 1
        or output_token_parameter not in {"max_completion_tokens", "max_tokens"}
        or reasoning_effort not in {"", "none", "low", "medium", "high", "xhigh", "max"}
        or input_reserve_tokens < 0
        or input_token_limit <= input_reserve_tokens
    ):
        raise HTTPException(503, detail="task_plan_drafts_not_configured")
    return TaskPlanDraftConfig(
        max_body_bytes,
        timeout_seconds,
        output_token_limit,
        output_token_parameter,
        reasoning_effort,
        output_limits,
        input_token_limit,
        input_reserve_tokens,
    )


@router.post(
    "/v1/task-plan-drafts",
    dependencies=[Depends(require_internal_token)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {
                    "schema": inline_request_schema(TaskPlanDraftRequest)
                }
            },
        }
    },
)
async def create_task_plan_draft(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    config: Annotated[TaskPlanDraftConfig, Depends(require_task_plan_draft_config)],
    client: Annotated[LLMClient, Depends(get_review_client)],
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
        body = TaskPlanDraftRequest.model_validate_json(bytes(data))
    except ValidationError as exc:
        semantic = any(error["type"] == "value_error" for error in exc.errors())
        raise InvalidRequest(semantic) from None
    proposer = LLMPlanProposer(
        client,
        context=LLMContext(
            project_id=body.project_id,
            feature="BEE_DRAFT",
            request_key=body.request_key,
        ),
        model=settings.bee_default_model,
        # A fresh key per actual model call, even when the BE retries.
        call_key=f"llm-task-plan-{body.request_key}-{uuid4().hex[:12]}",
        timeout_seconds=config.timeout_seconds,
        output_token_limit=config.output_token_limit,
        output_token_parameter=config.output_token_parameter,
        reasoning_effort=config.reasoning_effort,
        output_limits=config.output_limits,
        input_token_limit=config.input_token_limit,
        input_reserve_tokens=config.input_reserve_tokens,
    )
    outcome = await generate_task_plan_draft(body, proposer)
    return outcome.to_api_dict()
