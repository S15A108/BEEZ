"""Internal candidate analysis, behind explicit rollout configuration."""

from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.schemas.design_recommendations import RecommendationRequest
from app.services.design_recommendations import recommend, template_result
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider

_GMS_CHAT = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"


class RecommendationRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            try:
                return await handler(request)
            except RequestValidationError as exc:
                # Structural type/required-field errors are 400; cross-field
                # business constraints raised by model validators are 422.
                semantic = any(error["type"] == "value_error" for error in exc.errors())
                return JSONResponse(
                    {
                        "error": {
                            "code": "invalid_ai_input"
                            if semantic
                            else "validation_failed",
                            "message": "Invalid request fields.",
                        }
                    },
                    status_code=422 if semantic else 400,
                )
            except HTTPException as exc:
                return JSONResponse(
                    {
                        "error": {
                            "code": exc.detail,
                            "message": "Recommendation request rejected.",
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
                return JSONResponse(exc.to_api_dict(), status_code=status)

        return wrapped


router = APIRouter(route_class=RecommendationRoute)


@asynccontextmanager
async def get_recommendation_client(settings: Settings):
    if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
        raise HTTPException(503, detail="recommendation_not_configured")
    try:
        provider = OpenAICompatibleProvider(
            endpoint=_GMS_CHAT, api_key=settings.gms_key.get_secret_value()
        )
    except ValueError:
        raise HTTPException(503, detail="recommendation_not_configured") from None
    async with provider:
        yield LLMClient(provider)


@router.post(
    "/v1/design-reference-recommendations",
    dependencies=[Depends(require_internal_token)],
)
async def recommend_designs(
    body: RecommendationRequest,
    settings: Annotated[Settings, Depends(get_settings)],
):
    if body.search_status != "READY":
        # BE knows search readiness and must not send a failed search to AI.
        raise HTTPException(422, detail="invalid_ai_input")
    prepared = template_result(body)
    if prepared is not None:
        return prepared
    if not settings.design_recommendation_enabled:
        raise HTTPException(503, detail="recommendation_not_enabled")
    async with get_recommendation_client(settings) as client:
        return await recommend(body, client, settings.bee_default_model)
