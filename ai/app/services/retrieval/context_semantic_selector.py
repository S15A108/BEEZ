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
    DirectMatchTruncation,
    SelectionInput,
    SelectionProposal,
)
from app.services.retrieval.embeddings import (
    EmbeddingError,
    EmbeddingProvider,
    _embed_prepared_texts,
    _prepare_embedding_texts,
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


def validate_correlation_key(value: str) -> str:
    """Validate the BE-supplied opaque key without reflecting it in errors."""
    if (
        not isinstance(value, str)
        or not 1 <= len(value) <= 128
        or not value.strip()
        or not value.isprintable()
    ):
        raise ValueError("Invalid request key")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError:
        raise ValueError("Invalid request key") from None
    return value


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
        request_key: str | None = None,
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
        if request_key is not None:
            validate_correlation_key(request_key)
        self._provider = provider
        self._profile = profile
        self._counter = counter
        self._threshold = float(min_similarity)
        self._max_items = max_items
        self._max_evidence = max_evidence
        self._policy = policy
        self._request_key = request_key

    async def select(self, request: SelectionInput) -> SelectionProposal:
        if request.search_status != "COMPLETE":
            raise ContextSelectionError(
                "context_not_ready"
                if request.search_status == "NOT_READY"
                else "search_failed"
            )

        # A zero limit skips a category, so a nonempty skipped category would
        # make the result incomplete even when another category matches.
        # Fail before any paid provider call rather than report a complete search.
        if (self._max_items == 0 and request.items) or (
            self._max_evidence == 0 and request.evidence
        ):
            raise ContextSelectionError("selection_incomplete")

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
            return SelectionProposal(
                design_item_version_ids=(),
                evidence_ids=(),
                direct_match_truncation=DirectMatchTruncation(items=0, evidence=0),
            )

        # embed_texts adds a fresh invocation UUID to each actual call.
        # Keep the supplied correlation key intact, even at its 128-char limit.
        key = self._request_key or f"context-selection-{uuid4().hex}"
        query_key = key if self._request_key is not None else key + "-query"
        document_key = key if self._request_key is not None else key + "-documents"
        query_usage = ()
        try:
            # Validate before the query incurs a provider call and reuse exactly
            # those immutable candidate batches for the document call.
            prepared_docs = await _prepare_embedding_texts(
                candidates, self._profile, self._policy, self._counter
            )
            query = await embed_texts(
                (EmbeddingText(key="query", text=request.request),),
                self._profile,
                query_key,
                self._provider,
                self._counter,
                purpose="query",
                policy=self._policy,
            )
            query_usage = query.usage
            docs = await _embed_prepared_texts(
                prepared_docs, document_key, self._provider, purpose="document"
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

        def select_keys(prefix: str, count: int, limit: int) -> tuple[list[int], int]:
            indices = [
                i for i in range(count) if scores[f"{prefix}-{i}"] >= self._threshold
            ]
            # Tie-break by input order to make a deterministic selection.
            ordered = sorted(indices, key=lambda i: (-scores[f"{prefix}-{i}"], i))
            return ordered[:limit], max(0, len(ordered) - limit)

        selected_items, omitted_items = select_keys("item", len(items), self._max_items)
        selected_evidence, omitted_evidence = select_keys(
            "evidence", len(evidence), self._max_evidence
        )

        return SelectionProposal(
            design_item_version_ids=tuple(
                items[i].design_item_version_id for i in selected_items
            ),
            evidence_ids=tuple(evidence[i].evidence_id for i in selected_evidence),
            usage=query.usage + docs.usage,
            direct_match_truncation=DirectMatchTruncation(
                items=omitted_items, evidence=omitted_evidence
            ),
        )
