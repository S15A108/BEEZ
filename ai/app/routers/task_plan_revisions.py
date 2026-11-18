"""Read-only revision proposals. Human approval and revalidation belong to BE."""

from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.core.openapi import inline_request_schema
from app.routers.document_review import get_review_client
from app.routers.task_plan_drafts import (
    InvalidRequest,
    TaskPlanDraftConfig,
    TaskPlanDraftRoute,
    require_task_plan_draft_config,
)
from app.schemas.task_plan_revisions import TaskPlanRevisionRequest
from app.services.llm_client import LLMClient
from app.services.task_plan_revisions import generate_task_plan_revision

router = APIRouter(route_class=TaskPlanDraftRoute)


@router.post(
    "/v1/task-plan-revision-proposals",
    dependencies=[Depends(require_internal_token)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {
                    "schema": inline_request_schema(TaskPlanRevisionRequest)
                }
            },
        }
    },
)
async def create_task_plan_revision_proposal(
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
        body = TaskPlanRevisionRequest.model_validate_json(bytes(data))
    except ValidationError as exc:
        semantic = any(error["type"] == "value_error" for error in exc.errors())
        raise InvalidRequest(semantic) from None
    return await generate_task_plan_revision(
        body,
        client,
        model=settings.bee_default_model,
        call_key=f"llm-plan-revision-{body.request_key}-{uuid4().hex[:12]}",
        timeout_seconds=config.timeout_seconds,
        output_token_limit=config.output_token_limit,
        output_token_parameter=config.output_token_parameter,
        reasoning_effort=config.reasoning_effort,
        input_token_limit=config.input_token_limit,
        input_reserve_tokens=config.input_reserve_tokens,
    )
