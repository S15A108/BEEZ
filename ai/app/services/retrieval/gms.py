"""One GMS HTTP call per invocation; batching/retries belong to embed_texts."""

import json
import math
import time
from typing import Literal

import httpx
from pydantic import SecretStr

from app.schemas.retrieval import (
    EmbeddingProfile,
    EmbeddingText,
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)
from app.services.retrieval.embeddings import EmbeddingProviderError
from app.services.retrieval.tokenization import MAX_INPUT_TOKENS, TOKENIZER_ID

GMS_EMBEDDINGS_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/embeddings"
MAX_RESPONSE_BYTES = 8 * 1024 * 1024


def _usage(payload, call_key, model, started, *, failed):
    raw = payload.get("usage") if isinstance(payload, dict) else None
    count = raw.get("prompt_tokens") if isinstance(raw, dict) else None
    total = raw.get("total_tokens") if isinstance(raw, dict) else None
    known = type(count) is int and type(total) is int and count >= 0 and total == count
    return EmbeddingUsage(
        request_key=call_key,
        model=model,
        input_tokens=count if known else None,
        output_tokens=0 if known else None,
        tokens_known=known,
        failed=failed,
        latency_ms=int((time.monotonic() - started) * 1000),
    )


def _vectors(payload, texts, profile):
    if payload.get("model") != profile.model or payload.get("object") != "list":
        raise ValueError("Different model or response type")
    data = payload.get("data")
    if not isinstance(data, list) or len(data) != len(texts):
        raise ValueError("Incomplete vector set")
    result, seen = [], set()
    for item in data:
        if not isinstance(item, dict) or item.get("object") != "embedding":
            raise ValueError("Invalid vector record")
        index, values = item.get("index"), item.get("embedding")
        if type(index) is not int or not 0 <= index < len(texts) or index in seen:
            raise ValueError("Invalid vector index")
        if (
            not isinstance(values, list)
            or len(values) != profile.dimension
            or not all(type(v) in (int, float) and math.isfinite(v) for v in values)
        ):
            raise ValueError("Invalid vector values")
        seen.add(index)
        result.append(EmbeddingVector(key=texts[index].key, embedding=tuple(values)))
    return tuple(result)


class GmsEmbeddingProvider:
    def __init__(self, client: httpx.AsyncClient, api_key: SecretStr):
        if not api_key.get_secret_value().strip():
            raise ValueError("GMS_KEY is missing")
        self.client = client
        self.api_key = api_key

    async def embed(
        self,
        texts: tuple[EmbeddingText, ...],
        profile: EmbeddingProfile,
        purpose: Literal["document", "query"],
        request_key: str,
    ) -> ProviderEmbeddingBatch:
        if (
            profile.provider != "gms-openai"
            or profile.model != "text-embedding-3-small"
            or not 1 <= profile.dimension <= 1536
            or profile.tokenizer_id != TOKENIZER_ID
            or profile.max_input_tokens != MAX_INPUT_TOKENS
            or purpose not in {"document", "query"}
        ):
            raise EmbeddingProviderError(
                "embedding_profile_mismatch", "Unsupported GMS profile."
            )
        started = time.monotonic()
        payload = None
        try:
            async with self.client.stream(
                "POST",
                GMS_EMBEDDINGS_URL,
                headers={"Authorization": f"Bearer {self.api_key.get_secret_value()}"},
                json={
                    "model": profile.model,
                    "input": [text.text for text in texts],
                    "dimensions": profile.dimension,
                    "encoding_format": "float",
                },
                follow_redirects=False,
                timeout=20.0,
            ) as response:
                chunks, size = [], 0
                async for chunk in response.aiter_bytes():
                    size += len(chunk)
                    if size > MAX_RESPONSE_BYTES:
                        raise EmbeddingProviderError(
                            "invalid_embedding_output",
                            "Provider response exceeds size limit.",
                        )
                    chunks.append(chunk)
                try:
                    payload = json.loads(b"".join(chunks))
                except (ValueError, UnicodeError):
                    pass
                if response.status_code != 200:
                    raise EmbeddingProviderError(
                        "embedding_provider_failed",
                        f"GMS returned HTTP {response.status_code}.",
                        retryable=response.status_code in {429, 500, 502, 503, 504},
                        usage=_usage(
                            payload, request_key, profile.model, started, failed=True
                        ),
                    )
        except httpx.TimeoutException:
            raise EmbeddingProviderError(
                "embedding_timeout", "GMS request timed out."
            ) from None
        except httpx.RequestError as exc:
            raise EmbeddingProviderError(
                "embedding_provider_failed",
                "GMS transport failed.",
                retryable=isinstance(exc, httpx.ConnectError),
            ) from None

        usage = _usage(payload, request_key, profile.model, started, failed=False)
        try:
            if not isinstance(payload, dict) or not usage.tokens_known:
                raise ValueError("Missing valid usage")
            vectors = _vectors(payload, texts, profile)
        except (ValueError, TypeError, OverflowError):
            raise EmbeddingProviderError(
                "invalid_embedding_output",
                "GMS response failed validation.",
                usage=usage.model_copy(update={"failed": True}),
            ) from None
        return ProviderEmbeddingBatch(profile=profile, vectors=vectors, usage=usage)
