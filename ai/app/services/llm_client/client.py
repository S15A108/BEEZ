"""Request-scoped orchestration for an injected LLM provider."""

import asyncio
import copy
import json
import math
import re
import time
from decimal import ROUND_HALF_UP, Decimal, DecimalException
from typing import Any, Protocol

from .errors import LLMError, ProviderError, ProviderNotStartedError
from .models import (
    LLMContext,
    LLMMessage,
    LLMRequest,
    LLMResult,
    LLMUsage,
    Pricing,
    ProviderResponse,
    Source,
)

FEATURES = frozenset(
    {
        "DOCUMENT_PARSE",
        "DESIGN_RECOMMENDATION",
        "TASK_VALIDATION",
        "CHANGE_IMPACT",
        "EMBEDDING",
        "BEE_CHAT",
        "BEE_DRAFT",
        "TASK_ASSIGNMENT",
        "MEETING_ANALYSIS",
    }
)
_ROLES = frozenset({"system", "developer", "user", "assistant"})
_RESERVED_OPTIONS = frozenset({"model", "messages", "stream", "response_format"})
_PROVIDER_CODES = frozenset({"model_timeout", "model_unavailable", "invalid_output"})
_MAX_DB_INT = 2_147_483_647
_MAX_DB_COST = Decimal("999999.999999")
_COST_QUANTUM = Decimal("0.000001")


class LLMProvider(Protocol):
    async def complete(
        self, request: LLMRequest, *, timeout_seconds: float
    ) -> ProviderResponse:
        """Make exactly one provider attempt; do not perform hidden retries."""
        ...


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _positive_integer(value: Any) -> bool:
    return (
        isinstance(value, int)
        and not isinstance(value, bool)
        and 0 < value <= _MAX_DB_INT
    )


def _nonnegative_integer(value: Any) -> bool:
    return (
        isinstance(value, int)
        and not isinstance(value, bool)
        and 0 <= value <= _MAX_DB_INT
    )


def _currency(value: Any) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[A-Z]{3}", value) is not None


def _price(value: Any) -> bool:
    return isinstance(value, Decimal) and value.is_finite() and value >= 0


def _cost_pair(amount: Any, currency: Any) -> tuple[Decimal | None, str | None]:
    """Only emit values representable by the current NUMERIC(12,6) column."""
    if not _price(amount) or not _currency(currency):
        return None, None
    try:
        rounded = amount.quantize(_COST_QUANTUM, rounding=ROUND_HALF_UP)
    except DecimalException:
        return None, None
    if rounded > _MAX_DB_COST or (amount > 0 and rounded == 0):
        return None, None
    return rounded, currency


def _json_safe(value: Any) -> bool:
    try:
        json.dumps(value, allow_nan=False)
        return True
    except (TypeError, ValueError, OverflowError, RecursionError):
        return False


def _validate(request: LLMRequest) -> None:
    """Reject bad inputs before a provider is invoked, without leaking values."""
    if not isinstance(request, LLMRequest):
        raise LLMError("invalid_input")
    ctx = request.context
    if (
        not isinstance(ctx, LLMContext)
        or not _text(request.model)
        or not _text(getattr(ctx, "project_id", None))
        or not isinstance(getattr(ctx, "feature", None), str)
        or ctx.feature not in FEATURES
        or not _text(getattr(ctx, "request_key", None))
    ):
        raise LLMError("invalid_input")
    if any(
        value is not None and not _text(value)
        for value in (ctx.task_id, ctx.validation_id)
    ):
        raise LLMError("invalid_input")
    if ctx.attempt is not None and not _positive_integer(ctx.attempt):
        raise LLMError("invalid_input")
    if (
        not isinstance(request.messages, (tuple, list))
        or not request.messages
        or any(
            not isinstance(m, LLMMessage)
            or not isinstance(m.role, str)
            or m.role not in _ROLES
            or not _text(m.content)
            for m in request.messages
        )
    ):
        raise LLMError("invalid_input")
    if (
        not _positive_integer(request.max_attempts)
        or request.max_attempts > 3
        or not isinstance(request.call_keys, (tuple, list))
        or len(request.call_keys) != request.max_attempts
        or any(not _text(key) for key in request.call_keys)
        or len(set(request.call_keys)) != len(request.call_keys)
    ):
        raise LLMError("invalid_input")
    if (
        not isinstance(request.timeout_seconds, (int, float))
        or isinstance(request.timeout_seconds, bool)
        or not math.isfinite(request.timeout_seconds)
        or request.timeout_seconds <= 0
        or request.timeout_seconds > _MAX_DB_INT / 1000
    ):
        raise LLMError("invalid_input")
    if ctx.feature == "TASK_VALIDATION":
        if not _text(ctx.validation_id) or not _positive_integer(ctx.attempt):
            raise LLMError("invalid_input")
        if request.max_attempts != 1:
            raise LLMError("invalid_input")
        pattern = (
            rf"llm-validate-{re.escape(ctx.validation_id)}-attempt-"
            rf"{ctx.attempt}-call-[1-9][0-9]*"
        )
        if re.fullmatch(pattern, request.call_keys[0]) is None:
            raise LLMError("invalid_input")
    if not isinstance(request.sources, (tuple, list)) or any(
        not isinstance(source, Source)
        or not _text(source.document_id)
        or not _text(source.document_version_id)
        or not _positive_integer(source.version_no)
        or not _text(source.location)
        for source in request.sources
    ):
        raise LLMError("invalid_input")
    if not isinstance(request.allowed_response_models, (tuple, list)) or any(
        not _text(model) for model in request.allowed_response_models
    ):
        raise LLMError("invalid_input")
    if request.pricing is not None:
        pricing = request.pricing
        if (
            not isinstance(pricing, Pricing)
            or not _price(pricing.input_per_million)
            or not _price(pricing.output_per_million)
            or not _currency(pricing.currency)
        ):
            raise LLMError("invalid_input")
    options = request.model_options
    if options is not None and (
        not isinstance(options, dict)
        or any(not _text(key) for key in options)
        or bool(_RESERVED_OPTIONS.intersection(options))
        or not _json_safe(options)
    ):
        raise LLMError("invalid_input")
    response_format = request.response_format
    if response_format is not None:
        if (
            not isinstance(response_format, dict)
            or not isinstance(response_format.get("type"), str)
            or response_format.get("type") not in {"text", "json_object", "json_schema"}
            or not _json_safe(response_format)
        ):
            raise LLMError("invalid_input")
        if response_format["type"] == "json_schema":
            schema = response_format.get("json_schema")
            if (
                not isinstance(schema, dict)
                or not _text(schema.get("name"))
                or not isinstance(schema.get("schema"), dict)
            ):
                raise LLMError("invalid_input")


def _usage(
    request: LLMRequest,
    call_key: str,
    model: Any,
    started: float,
    failed: bool,
    provider_data: ProviderResponse | ProviderError | None,
) -> LLMUsage:
    input_tokens = getattr(provider_data, "input_tokens", None)
    output_tokens = getattr(provider_data, "output_tokens", None)
    tokens_known = _nonnegative_integer(input_tokens) and _nonnegative_integer(
        output_tokens
    )
    if not tokens_known:
        input_tokens = output_tokens = None

    amount, currency = _cost_pair(
        getattr(provider_data, "estimated_cost_amount", None),
        getattr(provider_data, "currency", None),
    )
    if (
        amount is None
        and tokens_known
        and request.pricing is not None
        and _text(model)
        and model == request.model
    ):
        try:
            calculated = (
                Decimal(input_tokens) * request.pricing.input_per_million
                + Decimal(output_tokens) * request.pricing.output_per_million
            ) / Decimal(1_000_000)
        except DecimalException:
            calculated = None
        amount, currency = _cost_pair(calculated, request.pricing.currency)

    return LLMUsage(
        request_key=call_key,
        feature=request.context.feature,
        project_id=request.context.project_id,
        # Failures must never echo provider error bodies as the model name.
        model=request.model if failed or not _text(model) else model,
        latency_ms=max(0, int((time.monotonic() - started) * 1000)),
        failed=failed,
        tokens_known=tokens_known,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        estimated_cost_amount=amount,
        currency=currency,
        task_id=request.context.task_id,
        validation_id=request.context.validation_id,
        attempt=request.context.attempt,
    )


def _result(request: LLMRequest, response: ProviderResponse) -> str | dict[str, Any]:
    if not isinstance(response, ProviderResponse) or not _text(response.content):
        raise ProviderError("invalid_output")
    if not _text(response.model) or response.model not in (
        request.model,
        *request.allowed_response_models,
    ):
        raise ProviderError("invalid_output")
    if request.response_format and request.response_format["type"] in {
        "json_object",
        "json_schema",
    }:
        try:
            result = json.loads(
                response.content,
                parse_constant=lambda _: (_ for _ in ()).throw(ValueError()),
            )
        except (TypeError, ValueError, RecursionError):
            raise ProviderError("invalid_output") from None
        if not isinstance(result, dict) or not _json_safe(result):
            raise ProviderError("invalid_output")
        return result
    return response.content


class LLMClient:
    """Call an injected provider while preserving attempt-level usage."""

    def __init__(self, provider: LLMProvider) -> None:
        self._provider = provider

    async def generate(self, request: LLMRequest) -> LLMResult:
        try:
            snapshot = copy.deepcopy(request)
        # Arbitrary supplied objects can throw while copying. Convert before
        # provider invocation and suppress any exception that may contain data.
        except Exception:  # noqa: BLE001
            raise LLMError("invalid_input") from None
        try:
            _validate(snapshot)
        except LLMError:
            raise
        except (AttributeError, TypeError, ValueError, OverflowError):
            raise LLMError("invalid_input") from None
        deadline = time.monotonic() + snapshot.timeout_seconds
        usages: list[LLMUsage] = []
        for index, call_key in enumerate(snapshot.call_keys):
            remaining = min(snapshot.timeout_seconds, deadline - time.monotonic())
            if remaining <= 0:
                raise LLMError("model_timeout", tuple(usages)) from None
            started = time.monotonic()
            response: ProviderResponse | None = None
            try:
                async with asyncio.timeout(remaining):
                    response = await self._provider.complete(
                        snapshot, timeout_seconds=remaining
                    )
                    result = _result(snapshot, response)
                    if time.monotonic() >= deadline:
                        raise TimeoutError()
            except asyncio.CancelledError as exc:
                usages.append(
                    _usage(
                        snapshot,
                        call_key,
                        getattr(response, "model", None),
                        started,
                        True,
                        response,
                    )
                )
                exc.llm_usage = tuple(usages)
                raise
            except TimeoutError:
                failure = ProviderError("model_timeout", retryable=True)
            except ProviderNotStartedError:
                # This attempt never started; retain earlier attempts only.
                raise LLMError("model_unavailable", tuple(usages)) from None
            except ProviderError as exc:
                failure = exc
            except Exception:  # noqa: BLE001
                # Exceptions from provider libraries can contain credentials,
                # URLs, and request content. Never propagate those details.
                failure = ProviderError("model_unavailable")
            else:
                usages.append(
                    _usage(snapshot, call_key, response.model, started, False, response)
                )
                return LLMResult(
                    result=result,
                    model=response.model,
                    usage=tuple(usages),
                    sources=tuple(snapshot.sources),
                )

            # If generic output validation failed after a provider response,
            # retain that response's token/cost data rather than the empty error.
            data = response if response is not None else failure
            model = getattr(data, "model", None)
            usages.append(_usage(snapshot, call_key, model, started, True, data))
            code = (
                failure.code
                if isinstance(failure.code, str) and failure.code in _PROVIDER_CODES
                else "model_unavailable"
            )
            can_retry = (
                index + 1 < snapshot.max_attempts
                and failure.retryable
                and failure.code in ("model_timeout", "model_unavailable")
            )
            if not can_retry:
                raise LLMError(code, tuple(usages)) from None
            remaining = min(snapshot.timeout_seconds, deadline - time.monotonic())
            if remaining <= 0:
                raise LLMError("model_timeout", tuple(usages)) from None
            delay = 0.1 * (2**index)
            retry_after = getattr(failure, "retry_after_seconds", None)
            if isinstance(retry_after, (int, float)) and not isinstance(
                retry_after, bool
            ):
                try:
                    retry_after = float(retry_after)
                except OverflowError:
                    retry_after = None
                if (
                    retry_after is not None
                    and math.isfinite(retry_after)
                    and retry_after >= 0
                ):
                    delay = max(delay, retry_after)
            # Never start another attempt if its wait alone consumes the budget.
            if delay >= remaining:
                raise LLMError("model_timeout", tuple(usages)) from None
            try:
                await asyncio.sleep(delay)
            except asyncio.CancelledError as exc:
                exc.llm_usage = tuple(usages)
                raise

        raise LLMError("model_unavailable", tuple(usages)) from None
