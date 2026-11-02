"""Opt-in provisional transport for selecting from a BE-authorized snapshot."""

import asyncio
import math
import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Annotated
from uuid import uuid4

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.core.internal_auth import require_internal_token
from app.routers.embeddings import get_counter
from app.schemas.context_selection_api import ContextSelectionRequest
from app.schemas.retrieval import EmbeddingPolicy
from app.services.retrieval.context_selection import (
    ContextSelectionError,
    SelectionInput,
    SelectionProvider,
    select_context,
)
from app.services.retrieval.context_semantic_selector import EmbeddingContextSelector
from app.services.retrieval.gms import GmsEmbeddingProvider
from app.services.retrieval.tokenization import embedding_profile

# Hard transport bound, applied while receiving, before parsing or model calls.
MAX_BODY_BYTES = 262_144


class SelectionRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def wrapped(request: Request):
            request_id = request.headers.get("x-request-id", "")
            if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", request_id):
                request_id = uuid4().hex
            try:
                response = await handler(request)
            except HTTPException as exc:
                # Only fixed internal error codes are thrown in this router and auth.
                response = JSONResponse(
                    {
                        "error": {
                            "code": exc.detail,
                            "message": "Selection request rejected.",
                        }
                    },
                    status_code=exc.status_code,
                )
            except ContextSelectionError as exc:
                status = (
                    503
                    if exc.code == "context_not_ready"
                    else 504
                    if exc.code == "embedding_timeout"
                    else 422
                    if exc.code
                    in {
                        "invalid_selection",
                        "invalid_embedding_input",
                        "embedding_limit_exceeded",
                        "embedding_profile_mismatch",
                    }
                    else 502
                )
                body = {
                    "error": {
                        "code": exc.code,
                        "message": "Selection processing failed.",
                    }
                }
                if exc.usage:
                    body["usage"] = [
                        u.model_dump(mode="json", exclude_none=True) for u in exc.usage
                    ]
                response = JSONResponse(body, status_code=status)
            except Exception:  # noqa: BLE001
                # Never echo raw payload, provider details, or unexpected exceptions.
                response = JSONResponse(
                    {
                        "error": {
                            "code": "selection_failed",
                            "message": "Selection processing failed.",
                        }
                    },
                    status_code=502,
                )
            response.headers["X-Request-Id"] = request_id
            return response

        return wrapped


router = APIRouter(route_class=SelectionRoute)


@dataclass(frozen=True)
class SelectionConfig:
    min_similarity: float
    max_items: int
    max_evidence: int
    total_timeout_seconds: float


def require_selection_config(
    settings: Annotated[Settings, Depends(get_settings)],
) -> SelectionConfig:
    """The service owner must explicitly set all limits; no operating defaults."""
    if (
        not settings.context_selection_enabled
        or settings.gms_key is None
        or not settings.gms_key.get_secret_value().strip()
    ):
        raise HTTPException(503, detail="selection_not_configured")
    try:
        min_similarity = float(settings.context_selection_min_similarity)
        max_items = int(settings.context_selection_max_items)
        max_evidence = int(settings.context_selection_max_evidence)
        total_timeout_seconds = float(settings.context_selection_total_timeout_seconds)
    except (TypeError, ValueError, OverflowError):
        raise HTTPException(503, detail="selection_not_configured") from None
    if (
        not math.isfinite(min_similarity)
        or not -1 <= min_similarity <= 1
        or not 0 <= max_items <= 200
        or not 0 <= max_evidence <= 200
        or (max_items == 0 and max_evidence == 0)
        or not math.isfinite(total_timeout_seconds)
        or not 0 < total_timeout_seconds <= 120
    ):
        raise HTTPException(503, detail="selection_not_configured")
    return SelectionConfig(
        min_similarity, max_items, max_evidence, total_timeout_seconds
    )


class ConfiguredSelector:
    def __init__(self, config: SelectionConfig, settings: Settings, request_key: str):
        self._config = config
        self._settings = settings
        self._request_key = request_key

    async def select(self, snapshot: SelectionInput):
        # Construct the provider only after validated input reaches selection.
        # The existing route's fixed destination/no-proxy/no-redirect policy applies.
        async with httpx.AsyncClient(trust_env=False, follow_redirects=False) as client:
            selector = EmbeddingContextSelector(
                GmsEmbeddingProvider(client, self._settings.gms_key),
                embedding_profile(self._settings),
                get_counter(),
                min_similarity=self._config.min_similarity,
                max_items=self._config.max_items,
                max_evidence=self._config.max_evidence,
                policy=EmbeddingPolicy(
                    max_attempts=1,
                    total_timeout_seconds=self._config.total_timeout_seconds,
                ),
                request_key=self._request_key,
            )
            return await selector.select(snapshot)


def get_context_selector(
    config: Annotated[SelectionConfig, Depends(require_selection_config)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> Callable[[str], SelectionProvider]:
    return lambda request_key: ConfiguredSelector(config, settings, request_key)


def _request_openapi_schema():
    schema = ContextSelectionRequest.model_json_schema()
    definitions = schema.pop("$defs", {})

    def inline(value):
        if isinstance(value, dict):
            if set(value) == {"$ref"} and value["$ref"].startswith("#/$defs/"):
                return inline(definitions[value["$ref"].rsplit("/", 1)[-1]])
            return {key: inline(item) for key, item in value.items()}
        if isinstance(value, list):
            return [inline(item) for item in value]
        return value

    return inline(schema)


@router.post(
    "/v1/development-context-selections",
    dependencies=[Depends(require_internal_token), Depends(require_selection_config)],
    openapi_extra={
        "requestBody": {
            "required": True,
            "content": {"application/json": {"schema": _request_openapi_schema()}},
        }
    },
)
async def select_development_context(
    request: Request,
    selector_factory: Annotated[
        Callable[[str], SelectionProvider], Depends(get_context_selector)
    ],
    config: Annotated[SelectionConfig, Depends(require_selection_config)],
):
    if (
        request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
        != "application/json"
    ):
        raise HTTPException(415, detail="unsupported_media_type")
    data = bytearray()
    async for chunk in request.stream():
        if len(data) + len(chunk) > MAX_BODY_BYTES:
            raise HTTPException(413, detail="request_too_large")
        data.extend(chunk)
    try:
        body = ContextSelectionRequest.model_validate_json(bytes(data))
        # Pydantic's JSON mode validates arrays as tuples in the frozen model.
        snapshot = SelectionInput.model_validate_json(
            body.model_dump_json(exclude={"request_key"})
        )
    except (ValidationError, ValueError):
        raise HTTPException(400, detail="validation_failed") from None
    try:
        async with asyncio.timeout(config.total_timeout_seconds):
            result = await select_context(snapshot, selector_factory(body.request_key))
    except TimeoutError as exc:
        cancelled = exc.__cause__
        usage = tuple(getattr(cancelled, "embedding_usage", ()))
        raise ContextSelectionError("embedding_timeout", usage) from None
    # Snapshot binding is intentionally excluded; it cannot cross processes.
    return {
        "result": result.model_dump(mode="json", exclude={"usage"}),
        "usage": [u.model_dump(mode="json", exclude_none=True) for u in result.usage],
    }
