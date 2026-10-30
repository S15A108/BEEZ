"""Optional embedding-based selector over an already authorized snapshot.

This adapter re-embeds every supplied candidate for each call; it does not
query or reuse the BE vector index. Both a threshold and result limits must be
chosen by the caller, and the caller remains responsible for the BE scope,
consistency check, and persisting returned per-call usage.
"""

import asyncio
import math
from uuid import uuid4

from app.schemas.retrieval import EmbeddingPolicy, EmbeddingProfile, EmbeddingText
from app.services.retrieval.chunking import TokenCounter
from app.services.retrieval.context_selection import (
    ContextSelectionError,
    SelectionInput,
    SelectionProposal,
)
from app.services.retrieval.embeddings import (
    EmbeddingError,
    EmbeddingProvider,
    embed_texts,
)

_SAFE_ERROR_CODES = frozenset(
    {
        "invalid_embedding_input",
        "embedding_profile_mismatch",
        "embedding_limit_exceeded",
        "tokenizer_failure",
        "embedding_timeout",
        "invalid_embedding_usage",
        "embedding_provider_failed",
        "invalid_embedding_output",
    }
)


class EmbeddingContextSelector:
    """Rank supplied design items and evidence with one common embedding profile."""

    def __init__(
        self,
        provider: EmbeddingProvider,
        profile: EmbeddingProfile,
        counter: TokenCounter,
        *,
        min_similarity: float,
        max_items: int,
        max_evidence: int,
        policy: EmbeddingPolicy,
    ) -> None:
        if (
            isinstance(min_similarity, bool)
            or not isinstance(min_similarity, (float, int))
            or not math.isfinite(min_similarity)
            or not -1.0 <= min_similarity <= 1.0
            or type(max_items) is not int
            or type(max_evidence) is not int
            or max_items < 0
            or max_evidence < 0
            or (max_items == 0 and max_evidence == 0)
        ):
            raise ValueError("Invalid explicit selection limits or threshold")
        self._provider = provider
        self._profile = profile
        self._counter = counter
        self._threshold = float(min_similarity)
        self._max_items = max_items
        self._max_evidence = max_evidence
        self._policy = policy

    async def select(self, request: SelectionInput) -> SelectionProposal:
        if request.search_status != "COMPLETE":
            raise ContextSelectionError(
                "context_not_ready"
                if request.search_status == "NOT_READY"
                else "search_failed"
            )

        # Provider keys are internal, opaque and independent of user-provided IDs.
        items = request.items if self._max_items else ()
        evidence = request.evidence if self._max_evidence else ()
        candidates = tuple(
            EmbeddingText(key=f"item-{i}", text=item.text)
            for i, item in enumerate(items)
        ) + tuple(
            EmbeddingText(key=f"evidence-{i}", text=item.text)
            for i, item in enumerate(evidence)
        )
        if not candidates:
            return SelectionProposal(design_item_version_ids=(), evidence_ids=())

        # UUIDs avoid reuse across concurrent requests and retain no user text.
        key = f"context-selection-{uuid4().hex}"
        query_usage = ()
        try:
            query = await embed_texts(
                (EmbeddingText(key="query", text=request.request),),
                self._profile,
                key + "-query",
                self._provider,
                self._counter,
                purpose="query",
                policy=self._policy,
            )
            query_usage = query.usage
            docs = await embed_texts(
                candidates,
                self._profile,
                key + "-documents",
                self._provider,
                self._counter,
                purpose="document",
                policy=self._policy,
            )
        except EmbeddingError as exc:
            code = exc.code if exc.code in _SAFE_ERROR_CODES else "selection_failed"
            raise ContextSelectionError(code, query_usage + exc.usage) from None
        except asyncio.CancelledError as exc:
            # Completed calls remain available to an in-process cancellation handler.
            exc.embedding_usage = query_usage + tuple(
                getattr(exc, "embedding_usage", ())
            )
            raise

        query_vector = query.vectors[0].embedding
        query_norm = math.hypot(*query_vector)
        scores = {}
        for vector in docs.vectors:
            norm = math.hypot(*vector.embedding)
            # Both sides have already passed embed_texts profile and vector validation.
            scores[vector.key] = math.fsum(
                (left / query_norm) * (right / norm)
                for left, right in zip(query_vector, vector.embedding, strict=True)
            )

        def select_keys(prefix: str, count: int, limit: int) -> list[int]:
            indices = [
                i for i in range(count) if scores[f"{prefix}-{i}"] >= self._threshold
            ]
            # Tie-break by input order to make a deterministic selection.
            return sorted(indices, key=lambda i: (-scores[f"{prefix}-{i}"], i))[:limit]

        return SelectionProposal(
            design_item_version_ids=tuple(
                items[i].design_item_version_id
                for i in select_keys("item", len(items), self._max_items)
            ),
            evidence_ids=tuple(
                evidence[i].evidence_id
                for i in select_keys("evidence", len(evidence), self._max_evidence)
            ),
            usage=query.usage + docs.usage,
        )
