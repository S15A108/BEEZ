"""A configurable, non-streaming Chat Completions HTTP adapter.

Compatibility depends on the configured provider and model; this adapter does
not prescribe an endpoint, model, provider pricing, or feature prompt.
"""

import math
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from typing import Self
from urllib.parse import urlsplit

import httpx

from .errors import ProviderError
from .models import LLMRequest, ProviderResponse


def _endpoint_is_safe(endpoint: str) -> bool:
    if not isinstance(endpoint, str):
        return False
    try:
        parsed = urlsplit(endpoint)
        _ = parsed.port  # Reject malformed ports before constructing a client.
    except ValueError:
        return False
    if (
        not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
    ):
        return False
    if parsed.query or parsed.fragment or not parsed.path.endswith("/chat/completions"):
        return False
    if parsed.scheme == "https":
        return True
    return parsed.scheme == "http" and parsed.hostname in {
        "localhost",
        "127.0.0.1",
        "::1",
    }


def _tokens(body: object) -> tuple[int | None, int | None]:
    if not isinstance(body, dict) or not isinstance(body.get("usage"), dict):
        return None, None
    usage = body["usage"]
    incoming, outgoing = usage.get("prompt_tokens"), usage.get("completion_tokens")
    if (
        not isinstance(incoming, int)
        or isinstance(incoming, bool)
        or not 0 <= incoming <= 2_147_483_647
        or not isinstance(outgoing, int)
        or isinstance(outgoing, bool)
        or not 0 <= outgoing <= 2_147_483_647
    ):
        return None, None
    return incoming, outgoing


def _retry_after_seconds(value: str | None) -> float | None:
    """Parse a provider's Retry-After delay without trusting invalid values."""
    if not value:
        return None
    try:
        delay = float(value)
    except ValueError:
        try:
            retry_at = parsedate_to_datetime(value)
            if retry_at.tzinfo is None:
                retry_at = retry_at.replace(tzinfo=UTC)
            delay = (retry_at - datetime.now(UTC)).total_seconds()
        except (TypeError, ValueError, OverflowError):
            return None
    if not math.isfinite(delay) or delay < 0:
        return None
    return delay


class OpenAICompatibleProvider:
    """Send one HTTP attempt to an explicitly configured endpoint."""

    def __init__(
        self,
        endpoint: str,
        api_key: str,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if not _endpoint_is_safe(endpoint):
            raise ValueError("Invalid LLM endpoint configuration.")
        if (
            not isinstance(api_key, str)
            or not api_key.strip()
            or api_key != api_key.strip()
            or "\r" in api_key
            or "\n" in api_key
        ):
            raise ValueError("Invalid LLM credential configuration.")
        self._endpoint = endpoint
        self._api_key = api_key
        # The provider owns this client and closes its transport in aclose().
        self._client = httpx.AsyncClient(
            follow_redirects=False,
            trust_env=False,
            transport=transport,
        )

    async def aclose(self) -> None:
        """Close the shared HTTP client after all callers have finished."""
        await self._client.aclose()

    async def __aenter__(self) -> Self:
        if self._client.is_closed:
            raise RuntimeError("The LLM provider has already been closed.")
        return self

    async def __aexit__(self, exc_type: object, exc: object, tb: object) -> None:
        await self.aclose()

    async def complete(
        self, request: LLMRequest, *, timeout_seconds: float
    ) -> ProviderResponse:
        payload = {
            "model": request.model,
            "messages": [
                {"role": message.role, "content": message.content}
                for message in request.messages
            ],
            "stream": False,
        }
        if request.response_format is not None:
            payload["response_format"] = request.response_format
        if request.model_options:
            payload.update(request.model_options)
        headers = {"Authorization": f"Bearer {self._api_key}"}
        try:
            response = await self._client.post(
                self._endpoint,
                headers=headers,
                json=payload,
                timeout=timeout_seconds,
            )
        except httpx.TimeoutException:
            raise ProviderError("model_timeout", retryable=True) from None
        except httpx.HTTPError:
            raise ProviderError("model_unavailable", retryable=True) from None

        try:
            body = response.json()
        except (ValueError, UnicodeError):
            body = None
        input_tokens, output_tokens = _tokens(body)
        reported_model = body.get("model") if isinstance(body, dict) else None
        if not isinstance(reported_model, str) or not reported_model.strip():
            reported_model = None
        kwargs = {
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "model": reported_model,
        }

        if not 200 <= response.status_code < 300:
            status = response.status_code
            # An error response can echo request content or authorization in
            # arbitrary fields. Never surface its untrusted model name.
            kwargs["model"] = None
            if status in {408, 504}:
                raise ProviderError("model_timeout", retryable=True, **kwargs)
            if status in {429, 503}:
                kwargs["retry_after_seconds"] = _retry_after_seconds(
                    response.headers.get("Retry-After")
                )
            raise ProviderError(
                "model_unavailable",
                retryable=(status == 429 or status >= 500),
                **kwargs,
            )

        if not isinstance(body, dict):
            raise ProviderError("invalid_output", **kwargs)
        # Never expose a credential supplied in an untrusted metadata field.
        # The client separately accepts only explicitly configured model names.
        if reported_model is not None and self._api_key in reported_model:
            kwargs["model"] = None
            raise ProviderError("invalid_output", **kwargs)
        choices = body.get("choices")
        if (
            not isinstance(choices, list)
            or not choices
            or not isinstance(choices[0], dict)
        ):
            raise ProviderError("invalid_output", **kwargs)
        choice = choices[0]
        message = choice.get("message")
        if (
            not isinstance(message, dict)
            or message.get("refusal")
            or message.get("tool_calls")
            or choice.get("finish_reason") not in (None, "stop")
            or not isinstance(message.get("content"), str)
            or not message["content"].strip()
            or reported_model is None
        ):
            raise ProviderError("invalid_output", **kwargs)
        return ProviderResponse(
            content=message["content"],
            model=reported_model,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
        )
