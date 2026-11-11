"""Value objects for a single, caller-scoped LLM request.

The backend owns persistence; the context on each usage object makes it possible
for the calling feature to pass the correct project and optional task scope on.
"""

from dataclasses import dataclass, field, replace
from decimal import Decimal
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from .errors import LLMError


@dataclass(frozen=True, slots=True)
class LLMMessage:
    role: str
    content: str = field(repr=False)


@dataclass(frozen=True, slots=True)
class Source:
    document_id: str
    document_version_id: str
    version_no: int
    location: str


@dataclass(frozen=True, slots=True)
class LLMContext:
    project_id: str
    feature: str
    request_key: str
    task_id: str | None = None
    validation_id: str | None = None
    attempt: int | None = None


@dataclass(frozen=True, slots=True)
class Pricing:
    """Caller-supplied prices per million tokens; never a built-in price list."""

    input_per_million: Decimal
    output_per_million: Decimal
    currency: str


@dataclass(frozen=True, slots=True)
class LLMRequest:
    model: str
    messages: tuple[LLMMessage, ...] = field(repr=False)
    context: LLMContext
    call_keys: tuple[str, ...]
    timeout_seconds: float
    response_format: dict[str, Any] | None = field(default=None, repr=False)
    sources: tuple[Source, ...] = field(default=(), repr=False)
    max_attempts: int = 1
    pricing: Pricing | None = None
    model_options: dict[str, Any] | None = field(default=None, repr=False)
    allowed_response_models: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class ProviderResponse:
    content: str = field(repr=False)
    model: str
    input_tokens: int | None = None
    output_tokens: int | None = None
    estimated_cost_amount: Decimal | None = None
    currency: str | None = None


@dataclass(frozen=True, slots=True)
class LLMUsage:
    request_key: str
    feature: str
    project_id: str
    model: str
    latency_ms: int
    failed: bool
    tokens_known: bool
    input_tokens: int | None = None
    output_tokens: int | None = None
    estimated_cost_amount: Decimal | None = None
    currency: str | None = None
    task_id: str | None = None
    validation_id: str | None = None
    attempt: int | None = None

    def to_api_dict(self) -> dict[str, Any]:
        """Serialize the Usage DTO; context IDs stay on this object for BE mapping."""
        result: dict[str, Any] = {
            "request_key": self.request_key,
            "feature": self.feature,
            "model": self.model,
            "latency_ms": self.latency_ms,
            "failed": self.failed,
            "tokens_known": self.tokens_known,
        }
        if self.tokens_known:
            result["input_tokens"] = self.input_tokens
            result["output_tokens"] = self.output_tokens
        if self.estimated_cost_amount is not None and self.currency is not None:
            result["estimated_cost_amount"] = str(self.estimated_cost_amount)
            result["currency"] = self.currency
        return result


@dataclass(frozen=True, slots=True)
class LLMResult:
    result: str | dict[str, Any] = field(repr=False)
    model: str
    usage: tuple[LLMUsage, ...]
    sources: tuple[Source, ...] = field(default=(), repr=False)
    origin: str = "MODEL"

    def to_api_dict(self) -> dict[str, Any]:
        """Serialize the common internal response envelope.

        Sources are retained on the result for the feature owner to validate and
        include in its own result schema; they are not automatically citations.
        """
        return {
            "result": self.result,
            "origin": self.origin,
            "model": self.model,
            "usage": [item.to_api_dict() for item in self.usage],
        }

    def invalid_output_error(
        self, previous_usage: tuple[LLMUsage, ...] = ()
    ) -> "LLMError":
        """Build a sanitized error after the feature's own output validation.

        Only this result's last actual call becomes failed. Previously failed
        attempts and other call records remain available to the backend.
        """
        from .errors import LLMError

        usage = self.usage
        if usage:
            usage = (*usage[:-1], replace(usage[-1], failed=True))
        return LLMError("invalid_output", (*previous_usage, *usage))
