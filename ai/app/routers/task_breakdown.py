"""Internal task decomposition for a captured approved project baseline."""

from dataclasses import dataclass
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.core.openapi import inline_request_schema
from app.routers.document_review import get_review_client
from app.schemas.task_breakdown import TaskBreakdownRequest
from app.services.llm_client import LLMClient, LLMError
from app.services.task_breakdown import generate_task_breakdown

_MAX_BODY_BYTES = 512 * 1024
_MAX_TIMEOUT_SECONDS = 240
_MAX_OUTPUT_TOKEN_LIMIT = 32_768


class TaskBreakdownRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            try:
                return await handler(request)
            except ValidationError as exc:
                semantic = any(error["type"] == "value_error" for error in exc.errors())
                return JSONResponse(
                    {
                        "error": {
                            "code": "invalid_ai_input"
                            if semantic
                            else "validation_failed",
                            "message": "Invalid task breakdown request.",
                        }
                    },
                    status_code=422 if semantic else 400,
                )
            except HTTPException as exc:
                return JSONResponse(
                    {
                        "error": {
                            "code": exc.detail,
                            "message": "Task breakdown request rejected.",
                        }
                    },
                    status_code=exc.status_code,
                )
            except LLMError as exc:
                status = {"model_timeout": 504, "invalid_input": 422}.get(exc.code, 502)
                body = exc.to_api_dict()
                if exc.code == "invalid_output":
                    body["error"]["code"] = "invalid_output"
                return JSONResponse(body, status_code=status)

        return wrapped


router = APIRouter(route_class=TaskBreakdownRoute)


@dataclass(frozen=True)
class TaskBreakdownConfig:
    timeout_seconds: float
    output_token_limit: int


def require_task_breakdown_config(
    settings: Annotated[Settings, Depends(get_settings)],
) -> TaskBreakdownConfig:
    try:
        timeout_seconds = float(settings.task_breakdown_llm_timeout_seconds)
        output_token_limit = int(settings.task_breakdown_llm_output_token_limit)
    except (TypeError, ValueError, OverflowError):
        raise HTTPException(503, detail="task_breakdown_not_configured") from None
    if not (
        0 < timeout_seconds <= _MAX_TIMEOUT_SECONDS
        and 1 <= output_token_limit <= _MAX_OUTPUT_TOKEN_LIMIT
    ):
        raise HTTPException(503, detail="task_breakdown_not_configured")
    return TaskBreakdownConfig(timeout_seconds, output_token_limit)


async def _read_snapshot(request: Request) -> TaskBreakdownRequest:
    media_type = (
        request.headers.get("content-type", "").partition(";")[0].strip().lower()
    )
    if media_type != "application/json":
        raise HTTPException(415, detail="unsupported_media_type")
    raw = bytearray()
    async for chunk in request.stream():
        if len(raw) + len(chunk) > _MAX_BODY_BYTES:
            raise HTTPException(413, detail="request_too_large")
        raw.extend(chunk)
    return TaskBreakdownRequest.model_validate_json(bytes(raw))


@router.post(
    "/v1/bee/task-breakdown",
    dependencies=[Depends(require_internal_token)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {
                    "schema": inline_request_schema(TaskBreakdownRequest)
                }
            },
        }
    },
)
async def task_breakdown(
    snapshot: Annotated[TaskBreakdownRequest, Depends(_read_snapshot)],
    settings: Annotated[Settings, Depends(get_settings)],
    config: Annotated[TaskBreakdownConfig, Depends(require_task_breakdown_config)],
    client: Annotated[LLMClient, Depends(get_review_client)],
):
    return await generate_task_breakdown(
        snapshot,
        client,
        settings.bee_default_model,
        timeout_seconds=config.timeout_seconds,
        output_token_limit=config.output_token_limit,
    )
