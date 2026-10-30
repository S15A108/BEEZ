"""Provider-independent batching with exact key/profile/vector validation."""

import asyncio
import math
import struct
import time
from typing import Literal, Protocol
from uuid import uuid4

from app.schemas.retrieval import (
    EmbeddingPolicy,
    EmbeddingProfile,
    EmbeddingResult,
    EmbeddingText,
    EmbeddingUsage,
    ProviderEmbeddingBatch,
)
from app.services.retrieval.chunking import TokenCounter
from app.services.retrieval.errors import RetrievalError


class EmbeddingError(RetrievalError):
    def __init__(self, code: str, message: str, usage: tuple[EmbeddingUsage, ...] = ()):
        super().__init__(code, message)
        self.usage = usage


class EmbeddingProviderError(Exception):
    """Adapters report one actual failed call, without hidden SDK retries."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        usage: EmbeddingUsage | None = None,
    ):
        super().__init__(message)
        self.code, self.retryable, self.usage = code, retryable, usage


class EmbeddingProvider(Protocol):
    async def embed(
        self,
        texts: tuple[EmbeddingText, ...],
        profile: EmbeddingProfile,
        purpose: Literal["document", "query"],
        request_key: str,
    ) -> ProviderEmbeddingBatch: ...


def _batches(
    texts: tuple[EmbeddingText, ...],
    profile: EmbeddingProfile,
    policy: EmbeddingPolicy,
    counter: TokenCounter,
) -> list[tuple[EmbeddingText, ...]]:
    if (
        not texts
        or len(texts) > policy.max_texts
        or len({t.key for t in texts}) != len(texts)
    ):
        raise EmbeddingError(
            "invalid_embedding_input",
            "Expected nonempty unique keys within the request limit.",
        )
    if (
        profile.tokenizer_id != counter.tokenizer_id
        or profile.max_input_tokens > counter.max_input_tokens
    ):
        raise EmbeddingError(
            "embedding_profile_mismatch", "Tokenizer identity or input limit mismatch."
        )
    if sum(len(t.text) for t in texts) > 2_000_000:
        raise EmbeddingError(
            "embedding_limit_exceeded", "Request exceeds the text budget."
        )
    batches = []
    current, total = [], 0
    for text in texts:
        if not text.text.strip():
            raise EmbeddingError(
                "invalid_embedding_input", "Blank text cannot be embedded."
            )
        try:
            count = counter.count(text.text)
        except Exception as exc:
            raise EmbeddingError(
                "tokenizer_failure", "Token counting failed before provider calls."
            ) from exc
        if type(count) is not int or count <= 0:
            raise EmbeddingError("tokenizer_failure", "Invalid token count.")
        if count > min(profile.max_input_tokens, policy.max_batch_tokens):
            raise EmbeddingError(
                "embedding_limit_exceeded", "Text exceeds the configured token budget."
            )
        if current and (
            len(current) >= policy.batch_size or total + count > policy.max_batch_tokens
        ):
            batches.append(tuple(current))
            current, total = [], 0
        current.append(text)
        total += count
    if current:
        batches.append(tuple(current))
    if len(batches) > policy.max_batches:
        raise EmbeddingError(
            "embedding_limit_exceeded", "Request exceeds the batch limit."
        )
    return batches


def _valid_vectors(
    response: ProviderEmbeddingBatch,
    texts: tuple[EmbeddingText, ...],
    profile: EmbeddingProfile,
) -> bool:
    if (
        response.profile != profile
        or response.usage.failed
        or response.usage.model != profile.model
    ):
        return False
    keys = [v.key for v in response.vectors]
    if len(keys) != len(texts) or set(keys) != {t.key for t in texts}:
        return False
    for vector in response.vectors:
        if len(vector.embedding) != profile.dimension or not all(
            math.isfinite(v) for v in vector.embedding
        ):
            return False
        # pgvector's vector type stores float32; a finite float64 vector can
        # overflow or become all zeros during that conversion.
        try:
            stored = struct.unpack(
                f"<{profile.dimension}f",
                struct.pack(f"<{profile.dimension}f", *vector.embedding),
            )
        except (OverflowError, struct.error):
            return False
        norm = math.hypot(*stored)
        if not math.isfinite(norm) or norm == 0:
            return False
    return True


def _unknown_usage(call_key: str, model: str, started: float) -> EmbeddingUsage:
    return EmbeddingUsage(
        request_key=call_key,
        model=model,
        latency_ms=int((time.monotonic() - started) * 1000),
        failed=True,
        tokens_known=False,
    )


async def embed_texts(
    texts: tuple[EmbeddingText, ...],
    profile: EmbeddingProfile,
    request_key: str,
    provider: EmbeddingProvider,
    token_counter: TokenCounter,
    *,
    purpose: Literal["document", "query"] = "document",
    policy: EmbeddingPolicy | None = None,
) -> EmbeddingResult:
    policy = policy or EmbeddingPolicy()
    if (
        not isinstance(request_key, str)
        or not 1 <= len(request_key) <= 128
        or purpose not in {"document", "query"}
    ):
        raise EmbeddingError(
            "invalid_embedding_input", "Invalid request key or purpose."
        )
    deadline = time.monotonic() + policy.total_timeout_seconds
    try:
        async with asyncio.timeout(policy.total_timeout_seconds):
            batches = await asyncio.to_thread(
                _batches, texts, profile, policy, token_counter
            )
    except TimeoutError as exc:
        raise EmbeddingError(
            "embedding_timeout", "Token validation exhausted the total budget."
        ) from exc
    except asyncio.CancelledError as exc:
        # Token validation completes before any external provider call.
        exc.embedding_usage = ()
        raise
    usage: list[EmbeddingUsage] = []
    vectors = {}
    invocation_id = uuid4().hex
    for batch_no, batch in enumerate(batches):
        for attempt in range(1, policy.max_attempts + 1):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise EmbeddingError(
                    "embedding_timeout",
                    "Total embedding budget exhausted.",
                    tuple(usage),
                )
            call_key = f"{request_key}:emb:{invocation_id}:{batch_no}:{attempt}"
            started = time.monotonic()

            try:
                async with asyncio.timeout(min(remaining, policy.call_timeout_seconds)):
                    response = await provider.embed(batch, profile, purpose, call_key)
            except TimeoutError:
                usage.append(_unknown_usage(call_key, profile.model, started))
                failure = EmbeddingProviderError(
                    "embedding_timeout",
                    "Provider response timed out.",
                    retryable=policy.retry_timeouts,
                )
            except asyncio.CancelledError as exc:
                # The provider was entered but its outcome/tokens are unknown.
                # Preserve previous batches and the interrupted call without
                # changing cancellation into a completed response or a retry.
                usage.append(_unknown_usage(call_key, profile.model, started))
                exc.embedding_usage = tuple(usage)
                raise
            except EmbeddingProviderError as exc:
                if exc.usage is not None and exc.usage.request_key != call_key:
                    usage.append(_unknown_usage(call_key, profile.model, started))
                    raise EmbeddingError(
                        "invalid_embedding_usage",
                        "Usage belongs to a different call.",
                        tuple(usage),
                    ) from exc
                usage.append(
                    exc.usage or _unknown_usage(call_key, profile.model, started)
                )
                failure = exc
            except Exception as exc:
                usage.append(_unknown_usage(call_key, profile.model, started))
                raise EmbeddingError(
                    "embedding_provider_failed",
                    "Provider adapter failed.",
                    tuple(usage),
                ) from exc
            else:
                if (
                    not isinstance(response, ProviderEmbeddingBatch)
                    or response.usage.request_key != call_key
                ):
                    usage.append(_unknown_usage(call_key, profile.model, started))
                    raise EmbeddingError(
                        "invalid_embedding_usage",
                        "Invalid provider response or usage identity.",
                        tuple(usage),
                    )
                usage.append(response.usage)
                if not _valid_vectors(response, batch, profile):
                    raise EmbeddingError(
                        "invalid_embedding_output",
                        "Keys, profile, or vectors failed validation.",
                        tuple(usage),
                    )
                vectors.update((v.key, v) for v in response.vectors)
                break
            if not failure.retryable or attempt == policy.max_attempts:
                raise EmbeddingError(
                    failure.code,
                    "Embedding call failed; no partial vectors are returned.",
                    tuple(usage),
                ) from failure
            remaining = deadline - time.monotonic()
            if remaining <= policy.retry_delay_seconds:
                raise EmbeddingError(
                    "embedding_timeout",
                    "Retry would exceed the total budget.",
                    tuple(usage),
                ) from failure
            try:
                await asyncio.sleep(policy.retry_delay_seconds)
            except asyncio.CancelledError as exc:
                # The failed attempt was already recorded; no new call began.
                exc.embedding_usage = tuple(usage)
                raise
    return EmbeddingResult(
        profile=profile,
        purpose=purpose,
        vectors=tuple(vectors[t.key] for t in texts),
        usage=tuple(usage),
    )
