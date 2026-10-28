"""Safe errors shared by callers and provider implementations."""

import asyncio
from decimal import Decimal

from .models import LLMUsage


class ProviderNotStartedError(Exception):
    """The provider rejected an attempt before any HTTP request could start.

    Adapters should raise this only when they can guarantee no call began.
    The client may then preserve earlier usage without adding a new entry.
    """

    def __init__(self) -> None:
        super().__init__("The LLM provider is unavailable before the request.")


class ProviderError(Exception):
    """Provider failure with only sanitized, optionally reported usage.

    Provider implementations must not include response bodies, URLs, prompts,
    or credentials in the exception. A failed response may still report usage.
    """

    def __init__(
        self,
        code: str,
        *,
        retryable: bool = False,
        retry_after_seconds: float | None = None,
        input_tokens: int | None = None,
        output_tokens: int | None = None,
        model: str | None = None,
        estimated_cost_amount: Decimal | None = None,
        currency: str | None = None,
    ) -> None:
        super().__init__("The LLM provider request failed.")
        self.code = code
        self.retryable = retryable
        self.retry_after_seconds = retry_after_seconds
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens
        self.model = model
        self.estimated_cost_amount = estimated_cost_amount
        self.currency = currency


class LLMError(Exception):
    """Safe caller-facing error, retaining records for all started attempts."""

    def __init__(self, code: str, usage: tuple[LLMUsage, ...] = ()) -> None:
        super().__init__("The LLM request could not be completed.")
        self.code = code
        self.usage = usage

    def to_api_dict(self) -> dict:
        api_code = {
            "invalid_input": "invalid_ai_input",
            "invalid_output": "model_unavailable",
        }.get(self.code, self.code)
        result: dict = {"error": {"code": api_code, "message": str(self)}}
        if self.usage:
            result["usage"] = [item.to_api_dict() for item in self.usage]
        return result


class LLMCancelledError(asyncio.CancelledError):
    """Legacy exception retained for compatibility with existing imports.

    The current client propagates the original ``asyncio.CancelledError``
    with usage attached to its ``llm_usage`` attribute instead.
    """

    def __init__(self, usage: tuple[LLMUsage, ...]) -> None:
        super().__init__("The LLM request was cancelled.")
        self.usage = usage
