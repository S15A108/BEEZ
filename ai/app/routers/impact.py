"""Authenticated AI-A03 endpoint; the backend owns approval and persistence."""

import math
import re
from typing import Annotated
from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.services.change_impact import (
    ImpactAnalysisService,
    ImpactInput,
    ImpactModelConfig,
)
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider


class ImpactRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            request_id = request.headers.get("x-request-id", "")
            if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", request_id):
                request_id = uuid4().hex
            try:
                response = await handler(request)
            except ValidationError as exc:
                # Pydantic model validators denote structurally valid but
                # semantically incompatible input; never echo the input.
                semantic = any(error["type"] == "value_error" for error in exc.errors())
                status, code = (
                    (422, "invalid_ai_input")
                    if semantic
                    else (400, "validation_failed")
                )
                response = JSONResponse(
                    {"error": {"code": code, "message": "Invalid impact request."}},
                    status_code=status,
                )
            except HTTPException as exc:
                response = JSONResponse(
                    {
                        "error": {
                            "code": exc.detail,
                            "message": "Impact request rejected.",
                        }
                    },
                    status_code=exc.status_code,
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


router = APIRouter(route_class=ImpactRoute)


async def get_impact_client(
    request: Request, settings: Annotated[Settings, Depends(get_settings)]
) -> LLMClient:
    """Lazily create one provider per app; shutdown closes its HTTP client."""
    client = getattr(request.app.state, "impact_client", None)
    if client is not None:
        return client
    endpoint, key, model = (
        settings.impact_llm_endpoint,
        settings.impact_llm_api_key,
        settings.impact_llm_model,
    )
    if not endpoint or key is None or not model or not model.strip():
        raise HTTPException(502, detail="model_unavailable")
    try:
        provider = OpenAICompatibleProvider(
            endpoint=endpoint, api_key=key.get_secret_value()
        )
    except (TypeError, ValueError):
        raise HTTPException(502, detail="model_unavailable") from None
    client = LLMClient(provider)
    request.app.state.impact_provider = provider
    request.app.state.impact_client = client
    return client


@router.post(
    "/v1/impact",
    dependencies=[Depends(require_internal_token)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {"schema": ImpactInput.model_json_schema()}
            },
        }
    },
)
async def analyze_impact(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
    client: Annotated[LLMClient, Depends(get_impact_client)],
):
    # A fresh key denotes this actual model call, even when BE retries a request.
    # request_key remains in the call context for correlation.
    try:
        timeout = float(settings.impact_llm_timeout_seconds)
        if not math.isfinite(timeout) or not 0 < timeout <= 120:
            raise ValueError("invalid timeout")
        max_bytes = int(settings.impact_max_body_bytes)
        max_items = int(settings.impact_max_items_per_list)
        if not 1024 <= max_bytes <= 1048576 or not 1 <= max_items <= 1000:
            raise ValueError("invalid limit")
        output_limit = int(settings.impact_llm_output_token_limit)
        output_parameter = settings.impact_llm_output_token_parameter
        response_format = settings.impact_llm_response_format
        if response_format not in {"json_object", "json_schema"}:
            raise ValueError("invalid response format")
        if not 1 <= output_limit <= 4096 or output_parameter not in {
            "max_completion_tokens",
            "max_tokens",
        }:
            raise ValueError("invalid output token setting")
        aliases = tuple(
            alias.strip()
            for alias in settings.impact_llm_allowed_response_models.split(",")
            if alias.strip()
        )
        if len(aliases) > 20 or any(len(alias) > 256 for alias in aliases):
            raise ValueError("invalid response model aliases")
    except (TypeError, ValueError):
        raise HTTPException(502, detail="model_unavailable") from None
    raw = bytearray()
    async for chunk in request.stream():
        if len(raw) + len(chunk) > max_bytes:
            raise HTTPException(413, detail="invalid_ai_input")
        raw.extend(chunk)
    body = ImpactInput.model_validate_json(bytes(raw))
    result = await ImpactAnalysisService(client).analyze(
        body,
        ImpactModelConfig(
            model=settings.impact_llm_model or "",
            call_key=f"impact-{uuid4().hex}",
            timeout_seconds=timeout,
            allowed_response_models=aliases,
            max_input_bytes=max_bytes,
            max_items_per_list=max_items,
            output_token_limit=output_limit,
            output_token_parameter=output_parameter,
            response_format_mode=response_format,
        ),
    )
    return result.to_api_dict()
