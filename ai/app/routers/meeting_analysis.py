"""Internal, read-only meeting analysis endpoint."""

import re
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.schemas.meeting_analysis import MeetingAnalysisRequest
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider
from app.services.meeting_analysis import analyze

_GMS_CHAT = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"


class MeetingAnalysisRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            request_id = request.headers.get("x-request-id", "")
            if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", request_id):
                request_id = uuid4().hex
            try:
                response = await handler(request)
            except RequestValidationError as exc:
                semantic = any(error["type"] == "value_error" for error in exc.errors())
                response = JSONResponse(
                    {
                        "error": {
                            "code": "invalid_ai_input"
                            if semantic
                            else "validation_failed",
                            "message": "Invalid meeting analysis request.",
                        }
                    },
                    status_code=422 if semantic else 400,
                )
            except HTTPException as exc:
                response = JSONResponse(
                    {
                        "error": {
                            "code": exc.detail,
                            "message": "Meeting analysis request rejected.",
                        }
                    },
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
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


router = APIRouter(route_class=MeetingAnalysisRoute)


@asynccontextmanager
async def get_meeting_client(settings: Settings):
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


@router.post("/v1/bee/meeting-analysis", dependencies=[Depends(require_internal_token)])
async def analyze_meeting(
    body: MeetingAnalysisRequest,
    settings: Annotated[Settings, Depends(get_settings)],
):
    async with get_meeting_client(settings) as client:
        return await analyze(body, client, settings.bee_default_model)
