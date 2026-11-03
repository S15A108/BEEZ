"""Internal-only endpoint for plan semantic analysis."""

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
from app.schemas.validation import ValidateRequest
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider
from app.services.validation import InvalidInput, analyze, validate_input

GMS_CHAT_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"


class ValidationRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            request_id = request.headers.get("x-request-id", "")
            if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", request_id):
                request_id = uuid4().hex
            try:
                response = await handler(request)
            except RequestValidationError as exc:
                # Nested check/evidence structure and empty check sets are
                # business-contract failures; missing top-level or wrong
                # generic scalar types remain ordinary request failures.
                semantic_error = any(
                    len(error.get("loc", ())) >= 3
                    and error["loc"][:2] in (("body", "checks"), ("body", "evidence"))
                    or error.get("loc") == ("body", "checks")
                    and error.get("type") == "too_short"
                    for error in exc.errors()
                )
                status, code = (
                    (422, "invalid_ai_input")
                    if semantic_error
                    else (400, "validation_failed")
                )
                response = JSONResponse(
                    {"error": {"code": code, "message": "Invalid request fields."}},
                    status_code=status,
                )
            except HTTPException as exc:
                response = JSONResponse(
                    {
                        "error": {
                            "code": exc.detail,
                            "message": "Validation request rejected.",
                        }
                    },
                    status_code=exc.status_code,
                )
            except InvalidInput:
                response = JSONResponse(
                    {
                        "error": {
                            "code": "invalid_ai_input",
                            "message": "Unsupported validation input.",
                        }
                    },
                    status_code=422,
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


router = APIRouter(route_class=ValidationRoute)


@asynccontextmanager
async def get_validation_client(settings: Settings):
    if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
        raise HTTPException(status_code=502, detail="model_unavailable")
    try:
        provider = OpenAICompatibleProvider(
            endpoint=GMS_CHAT_URL, api_key=settings.gms_key.get_secret_value()
        )
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="model_unavailable") from exc
    async with provider:
        yield LLMClient(provider)


@router.post("/v1/validate", dependencies=[Depends(require_internal_token)])
async def validate_plan(
    body: ValidateRequest,
    settings: Annotated[Settings, Depends(get_settings)],
):
    validate_input(body)
    async with get_validation_client(settings) as client:
        return await analyze(
            body,
            client,
            settings.bee_default_model,
            response_format=getattr(
                settings, "validation_response_format", "json_object"
            ),
        )
