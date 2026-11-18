import re
from functools import lru_cache
from typing import Annotated
from uuid import uuid4

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.schemas.embedding_api import (
    EmbeddingApiResult,
    EmbeddingRequest,
    EmbeddingResponse,
)
from app.services.retrieval.embeddings import EmbeddingError, embed_texts
from app.services.retrieval.gms import GmsEmbeddingProvider
from app.services.retrieval.tokenization import EmbeddingTokenCounter, embedding_profile


class EmbeddingRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            request_id = request.headers.get("x-request-id", "")
            if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", request_id):
                request_id = uuid4().hex
            try:
                response = await handler(request)
            except RequestValidationError:
                response = JSONResponse(
                    {
                        "error": {
                            "code": "validation_failed",
                            "message": "Invalid request fields.",
                        }
                    },
                    status_code=400,
                )
            except HTTPException as exc:
                response = JSONResponse(
                    {
                        "error": {
                            "code": exc.detail,
                            "message": "Embedding request rejected.",
                        }
                    },
                    status_code=exc.status_code,
                )
            except EmbeddingError as exc:
                if exc.code == "embedding_timeout":
                    status, code = 504, "model_timeout"
                elif exc.code in {
                    "invalid_embedding_input",
                    "embedding_limit_exceeded",
                    "embedding_profile_mismatch",
                }:
                    status, code = 422, "invalid_ai_input"
                else:
                    status, code = 502, "model_unavailable"
                body = {
                    "error": {"code": code, "message": "Embedding processing failed."}
                }
                if exc.usage:
                    body["usage"] = [u.model_dump(exclude_none=True) for u in exc.usage]
                response = JSONResponse(body, status_code=status)
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


router = APIRouter(route_class=EmbeddingRoute)


@lru_cache
def get_counter() -> EmbeddingTokenCounter:
    try:
        return EmbeddingTokenCounter()
    except Exception as exc:
        raise HTTPException(502, detail="model_unavailable") from exc


async def get_provider(settings: Annotated[Settings, Depends(get_settings)]):
    if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
        raise HTTPException(502, detail="model_unavailable")
    # Credentials always go to the fixed GMS origin; redirects and proxy env are disabled.
    async with httpx.AsyncClient(trust_env=False, follow_redirects=False) as client:
        yield GmsEmbeddingProvider(client, settings.gms_key)


@router.post(
    "/v1/embeddings",
    response_model=EmbeddingResponse,
    response_model_exclude_none=True,
    dependencies=[Depends(require_internal_token)],
)
async def create_embeddings(
    body: EmbeddingRequest,
    settings: Annotated[Settings, Depends(get_settings)],
    counter: Annotated[EmbeddingTokenCounter, Depends(get_counter)],
    provider: Annotated[GmsEmbeddingProvider, Depends(get_provider)],
) -> EmbeddingResponse:
    profile = embedding_profile(settings)
    if (
        (body.model is not None and body.model != profile.model)
        or (body.dimension is not None and body.dimension != profile.dimension)
        or (
            body.embedding_profile_id is not None
            and body.embedding_profile_id != profile.profile_id
        )
    ):
        raise HTTPException(422, detail="invalid_ai_input")
    result = await embed_texts(
        tuple(body.texts),
        profile,
        body.request_key,
        provider,
        counter,
        purpose="query" if body.purpose == "QUERY" else "document",
    )
    return EmbeddingResponse(
        result=EmbeddingApiResult(
            model=profile.model,
            dimension=profile.dimension,
            vectors=result.vectors,
            embedding_profile_id=profile.profile_id if body.purpose else None,
            purpose=body.purpose,
        ),
        model=profile.model,
        usage=result.usage,
    )
