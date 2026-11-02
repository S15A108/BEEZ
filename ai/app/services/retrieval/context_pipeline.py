"""In-process composition of development context components.

The caller supplies an authorized current snapshot and must recheck authority
and freshness before using the result. This is not a BE, MCP or storage API.
"""

import asyncio
from dataclasses import dataclass, field
from typing import Literal, Protocol

from app.schemas.retrieval import EmbeddingUsage
from app.services.llm_client import LLMError, LLMResult, LLMUsage
from app.services.retrieval.context_request_analysis import (
    DevelopmentRequest,
    SearchHints,
)
from app.services.retrieval.context_selection import (
    SAFE_SELECTION_ERROR_CODES,
    ContextSelectionError,
    SelectionInput,
    SelectionProvider,
    SelectionResult,
    select_context,
)
from app.services.retrieval.context_unresolved import (
    UnresolvedAssessor,
    review_unresolved,
)

_SAFE_LLM_CAUSES = frozenset(
    {"invalid_input", "invalid_output", "model_timeout", "model_unavailable"}
)


class RequestAnalyzer(Protocol):
    async def analyze(self, request: DevelopmentRequest) -> SearchHints: ...


@dataclass(frozen=True, slots=True)
class ContextPipelineResult:
    hints: SearchHints = field(repr=False)
    selection: SelectionResult = field(repr=False)
    review: LLMResult = field(repr=False)
    # Usage appears on individual components too: persist each call only once.
    llm_usage: tuple[LLMUsage, ...]
    embedding_usage: tuple[EmbeddingUsage, ...]


class ContextPipelineError(Exception):
    """Sanitized failure with usage of all externally started calls."""

    def __init__(
        self,
        code: str,
        phase: Literal["input", "analysis", "selection", "review"],
        llm_usage: tuple[LLMUsage, ...] = (),
        embedding_usage: tuple[EmbeddingUsage, ...] = (),
        cause_code: str | None = None,
    ) -> None:
        super().__init__(code)
        self.code = code
        self.phase = phase
        self.llm_usage = llm_usage
        self.embedding_usage = embedding_usage
        self.cause_code = cause_code


async def build_development_context(
    snapshot: SelectionInput,
    analyzer: RequestAnalyzer,
    selector: SelectionProvider,
    assessor: UnresolvedAssessor,
) -> ContextPipelineResult:
    """Run the three injected components against exactly the same snapshot."""
    if snapshot.search_status != "COMPLETE":
        raise ContextPipelineError(
            "context_not_ready"
            if snapshot.search_status == "NOT_READY"
            else "search_failed",
            "input",
        )

    llm_usage: tuple[LLMUsage, ...] = ()
    embedding_usage: tuple[EmbeddingUsage, ...] = ()
    phase: Literal["analysis", "selection", "review"] = "analysis"
    try:
        hints = await analyzer.analyze(
            DevelopmentRequest(snapshot.project_id, snapshot.task_id, snapshot.request)
        )
        if not isinstance(hints, SearchHints):
            raise ContextPipelineError("analysis_failed", phase)
        llm_usage = hints.usage
        if hints.query != snapshot.request:
            raise ContextPipelineError("analysis_failed", phase, llm_usage)

        # Hints remain optional metadata; the selector sees the original input.
        phase = "selection"
        selection = await select_context(snapshot, selector)
        embedding_usage = selection.usage

        phase = "review"
        review = await review_unresolved(snapshot, selection, assessor)
        llm_usage += review.usage
        return ContextPipelineResult(
            hints, selection, review, llm_usage, embedding_usage
        )
    except asyncio.CancelledError as exc:
        # Preserve both completed and in-flight usage, without hiding cancellation.
        exc.llm_usage = llm_usage + tuple(getattr(exc, "llm_usage", ()))
        exc.embedding_usage = embedding_usage + tuple(
            getattr(exc, "embedding_usage", ())
        )
        raise
    except ContextPipelineError:
        # An injected component may itself raise this exception with unsafe text.
        raise ContextPipelineError(
            f"{phase}_failed", phase, llm_usage, embedding_usage
        ) from None
    except LLMError as exc:
        raise ContextPipelineError(
            f"{phase}_failed",
            phase,
            llm_usage + exc.usage,
            embedding_usage,
            exc.code
            if isinstance(exc.code, str) and exc.code in _SAFE_LLM_CAUSES
            else None,
        ) from None
    except ContextSelectionError as exc:
        # Only selection errors returned by select_context have passed its
        # sanitization; injected analysis/review errors can contain anything.
        code = (
            exc.code
            if phase == "selection"
            and isinstance(exc.code, str)
            and exc.code in SAFE_SELECTION_ERROR_CODES
            else f"{phase}_failed"
        )
        raise ContextPipelineError(
            code, phase, llm_usage, embedding_usage + exc.usage
        ) from None
    except Exception:  # noqa: BLE001
        # Third-party exceptions may contain request text or credentials.
        raise ContextPipelineError(
            f"{phase}_failed", phase, llm_usage, embedding_usage
        ) from None
