"""Contract tests for the shared LLM client; no provider network is used."""

import asyncio
import json
import traceback
from dataclasses import replace
from decimal import Decimal

import httpx
import pytest

from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMMessage,
    LLMRequest,
    OpenAICompatibleProvider,
    Pricing,
    ProviderError,
    ProviderResponse,
    Source,
)


class ScriptedProvider:
    """Expose calls and return a sequence of independent provider outcomes."""

    def __init__(self, *outcomes):
        self.outcomes = list(outcomes)
        self.calls = []

    async def complete(self, request, *, timeout_seconds):
        self.calls.append((request, timeout_seconds))
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


_loop = None


@pytest.fixture(scope="module", autouse=True)
def shared_event_loop():
    """Reuse one loop so Windows does not need a new socket pair per test."""
    global _loop
    _loop = asyncio.new_event_loop()
    yield
    _loop.close()
    _loop = None


def run(awaitable):
    async def bounded():
        return await asyncio.wait_for(awaitable, timeout=2.0)

    return _loop.run_until_complete(bounded())


def request_for(feature="BEE_CHAT", **overrides):
    if feature == "TASK_VALIDATION":
        context = LLMContext(
            project_id="101",
            feature=feature,
            request_key="request-validation-601",
            task_id="501",
            validation_id="601",
            attempt=1,
        )
        call_keys = ("llm-validate-601-attempt-1-call-1",)
    else:
        context = LLMContext(
            project_id="101", feature=feature, request_key="request-chat-1"
        )
        call_keys = ("llm-chat-1-call-1",)
    request = LLMRequest(
        model="configured-model",
        messages=(LLMMessage(role="user", content="What does the document say?"),),
        context=context,
        call_keys=call_keys,
        timeout_seconds=1.0,
        allowed_response_models=("provider-model",),
    )
    return replace(request, **overrides)


def answer(content="answer", **kwargs):
    return ProviderResponse(content=content, model="provider-model", **kwargs)


def test_documented_reuse_examples_work_for_chat_and_validation(capsys):
    from examples.llm_reuse import ExampleProvider, answer_for_bee, validate_plan

    client = LLMClient(ExampleProvider())
    run(answer_for_bee(client))
    run(validate_plan(client))
    lines = capsys.readouterr().out.splitlines()
    chat = json.loads(lines[0])
    validation = json.loads(lines[-1])
    assert chat["usage"][0]["feature"] == "BEE_CHAT"
    assert validation["usage"][0]["feature"] == "TASK_VALIDATION"
    assert validation["usage"][0]["request_key"] == "llm-validate-601-attempt-1-call-1"


@pytest.mark.parametrize("feature", ["BEE_CHAT", "TASK_VALIDATION"])
def test_success_records_per_call_context_and_serializes_contract(feature):
    provider = ScriptedProvider(answer(input_tokens=12, output_tokens=7))
    request = request_for(feature)

    result = run(LLMClient(provider).generate(request))

    assert result.result == "answer"
    assert result.origin == "MODEL"
    assert result.model == "provider-model"
    assert len(provider.calls) == 1
    assert len(result.usage) == 1
    usage = result.usage[0]
    assert usage.request_key == request.call_keys[0]
    assert usage.feature == feature
    assert usage.model == "provider-model"
    assert usage.project_id == "101"
    assert usage.task_id == request.context.task_id
    assert usage.validation_id == request.context.validation_id
    assert usage.attempt == request.context.attempt
    assert usage.failed is False
    assert usage.tokens_known is True
    assert (usage.input_tokens, usage.output_tokens) == (12, 7)
    assert usage.estimated_cost_amount is None
    assert usage.currency is None
    wire = result.to_api_dict()
    assert set(wire) == {"result", "origin", "model", "usage"}
    assert wire["usage"][0]["request_key"] == request.call_keys[0]
    assert "project_id" not in wire["usage"][0]
    assert "estimated_cost_amount" not in wire["usage"][0]
    assert "currency" not in wire["usage"][0]
    json.dumps(wire)


def test_partial_token_counts_are_not_reported_as_known_or_zero():
    provider = ScriptedProvider(answer(input_tokens=12, output_tokens=None))
    result = run(LLMClient(provider).generate(request_for()))

    usage = result.usage[0]
    assert usage.tokens_known is False
    assert usage.input_tokens is None
    assert usage.output_tokens is None
    serialized = usage.to_api_dict()
    assert "input_tokens" not in serialized
    assert "output_tokens" not in serialized


def test_known_zero_token_counts_are_not_treated_as_unknown():
    provider = ScriptedProvider(answer(input_tokens=0, output_tokens=0))
    result = run(LLMClient(provider).generate(request_for()))

    usage = result.usage[0]
    assert usage.tokens_known is True
    assert usage.input_tokens == 0 and usage.output_tokens == 0


@pytest.mark.parametrize(
    ("input_tokens", "output_tokens"),
    [(2**31, 1), (1, 2**31), (-1, 1), (True, 1)],
)
def test_invalid_or_database_out_of_range_tokens_are_unknown(
    input_tokens, output_tokens
):
    provider = ScriptedProvider(
        answer(input_tokens=input_tokens, output_tokens=output_tokens)
    )
    result = run(LLMClient(provider).generate(request_for()))

    assert result.usage[0].tokens_known is False
    assert result.usage[0].input_tokens is None
    assert result.usage[0].output_tokens is None


def test_cost_is_calculated_only_from_explicit_price_and_known_tokens():
    pricing = Pricing(
        input_per_million=Decimal(2),
        output_per_million=Decimal(4),
        currency="USD",
    )
    provider = ScriptedProvider(
        ProviderResponse(
            content="answer",
            model="configured-model",
            input_tokens=1000,
            output_tokens=250,
        ),
        ProviderResponse(
            content="answer",
            model="configured-model",
            input_tokens=1000,
            output_tokens=None,
        ),
    )
    first = request_for(pricing=pricing, call_keys=("price-test-call-1",))
    second = request_for(pricing=pricing, call_keys=("price-test-call-2",))

    async def generate_both():
        client = LLMClient(provider)
        return await client.generate(first), await client.generate(second)

    known, partial = run(generate_both())
    assert known.usage[0].estimated_cost_amount == Decimal("0.003")
    assert known.usage[0].currency == "USD"
    assert Decimal(known.usage[0].to_api_dict()["estimated_cost_amount"]) == Decimal(
        "0.003"
    )
    assert partial.usage[0].estimated_cost_amount is None
    assert partial.usage[0].currency is None


def test_price_for_requested_model_is_not_applied_to_a_different_actual_model():
    pricing = Pricing(
        input_per_million=Decimal(2),
        output_per_million=Decimal(4),
        currency="USD",
    )
    provider = ScriptedProvider(answer(input_tokens=1000, output_tokens=250))
    result = run(LLMClient(provider).generate(request_for(pricing=pricing)))

    assert result.model == "provider-model"
    assert result.usage[0].tokens_known is True
    assert result.usage[0].estimated_cost_amount is None
    assert result.usage[0].currency is None


def test_price_is_not_inferred_when_failed_call_does_not_identify_actual_model():
    pricing = Pricing(
        input_per_million=Decimal(2),
        output_per_million=Decimal(4),
        currency="USD",
    )
    provider = ScriptedProvider(
        ProviderError("model_unavailable", input_tokens=1000, output_tokens=250)
    )

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request_for(pricing=pricing)))

    usage = caught.value.usage[0]
    assert usage.tokens_known is True
    assert usage.estimated_cost_amount is None
    assert usage.currency is None


@pytest.mark.parametrize("price", ["1e999999", "999999999"])
def test_unrepresentable_cost_does_not_lose_successful_call_usage(price):
    pricing = Pricing(
        input_per_million=Decimal(price),
        output_per_million=Decimal(1),
        currency="USD",
    )
    provider = ScriptedProvider(
        ProviderResponse(
            content="answer",
            model="configured-model",
            input_tokens=1_000_000,
            output_tokens=10,
        )
    )

    result = run(LLMClient(provider).generate(request_for(pricing=pricing)))

    assert result.result == "answer"
    assert len(result.usage) == 1
    assert result.usage[0].tokens_known is True
    assert result.usage[0].input_tokens == 1_000_000
    assert result.usage[0].estimated_cost_amount is None
    assert result.usage[0].currency is None


@pytest.mark.parametrize(
    ("provider_error", "expected_code"),
    [
        (ProviderError("model_unavailable"), "model_unavailable"),
        (ProviderError("model_timeout"), "model_timeout"),
        (TimeoutError(), "model_timeout"),
    ],
)
def test_started_call_failure_returns_failed_unknown_usage(
    provider_error, expected_code
):
    provider = ScriptedProvider(provider_error)
    request = request_for()

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request))

    error = caught.value
    assert error.code == expected_code
    assert len(provider.calls) == 1
    assert len(error.usage) == 1
    usage = error.usage[0]
    assert usage.request_key == request.call_keys[0]
    assert usage.failed is True
    assert usage.tokens_known is False
    assert usage.input_tokens is None and usage.output_tokens is None


def test_failure_preserves_provider_supplied_token_counts():
    provider = ScriptedProvider(
        ProviderError("model_unavailable", input_tokens=4, output_tokens=2)
    )
    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request_for()))

    usage = caught.value.usage[0]
    assert usage.failed is True
    assert usage.tokens_known is True
    assert (usage.input_tokens, usage.output_tokens) == (4, 2)


def test_provider_invalid_input_after_started_call_is_upstream_failure():
    provider = ScriptedProvider(ProviderError("invalid_input"))

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request_for()))

    assert len(provider.calls) == 1
    assert caught.value.code == "model_unavailable"
    assert caught.value.to_api_dict()["error"]["code"] == "model_unavailable"
    assert len(caught.value.usage) == 1
    assert caught.value.usage[0].failed is True


@pytest.mark.parametrize(
    "input_request",
    [
        request_for(model=""),
        request_for(max_attempts=2, call_keys=("duplicate", "duplicate")),
        request_for(
            "TASK_VALIDATION",
            max_attempts=2,
            call_keys=(
                "llm-validate-601-attempt-1-call-1",
                "llm-validate-601-attempt-1-call-2",
            ),
        ),
        request_for("TASK_VALIDATION", call_keys=("incorrect-validation-key",)),
        request_for(model_options={"model": "override"}),
        request_for(model_options={"stream": True}),
    ],
)
def test_invalid_input_is_rejected_before_any_call_or_usage(input_request):
    provider = ScriptedProvider(answer())

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(input_request))

    assert caught.value.code == "invalid_input"
    assert not caught.value.usage
    assert provider.calls == []
    wire = caught.value.to_api_dict()
    assert wire["error"]["code"] == "invalid_ai_input"
    assert "usage" not in wire


def test_validation_provider_failure_is_not_automatically_retried():
    provider = ScriptedProvider(
        ProviderError("model_unavailable", retryable=True), answer()
    )
    request = request_for("TASK_VALIDATION")

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request))

    assert len(provider.calls) == 1
    assert len(caught.value.usage) == 1
    assert caught.value.usage[0].request_key == "llm-validate-601-attempt-1-call-1"


def test_explicit_retry_preserves_each_call_and_uses_distinct_keys():
    provider = ScriptedProvider(
        ProviderError("model_unavailable", retryable=True),
        answer(input_tokens=8, output_tokens=3),
    )
    keys = ("llm-chat-1-call-1", "llm-chat-1-call-2")
    request = request_for(max_attempts=2, call_keys=keys)

    result = run(LLMClient(provider).generate(request))

    assert len(provider.calls) == 2
    assert [item.request_key for item in result.usage] == list(keys)
    assert [item.failed for item in result.usage] == [True, False]
    assert result.usage[0].tokens_known is False
    assert result.usage[1].tokens_known is True


def test_all_retries_share_the_original_deadline():
    class BlockingProvider:
        def __init__(self):
            self.timeouts = []

        async def complete(self, request, *, timeout_seconds):
            self.timeouts.append(timeout_seconds)
            if len(self.timeouts) == 1:
                await asyncio.sleep(0.01)
                raise ProviderError("model_unavailable", retryable=True)
            await asyncio.Event().wait()

    provider = BlockingProvider()
    keys = ("llm-chat-1-call-1", "llm-chat-1-call-2", "llm-chat-1-call-3")
    request = request_for(max_attempts=3, call_keys=keys, timeout_seconds=0.3)

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request))

    assert caught.value.code == "model_timeout"
    call_count = len(provider.timeouts)
    assert 2 <= call_count <= len(keys)
    assert provider.timeouts[0] <= 0.3
    assert all(
        0 < later < earlier
        for earlier, later in zip(provider.timeouts, provider.timeouts[1:])
    )
    assert [item.request_key for item in caught.value.usage] == list(keys[:call_count])
    assert all(item.failed for item in caught.value.usage)


def test_one_concurrent_feature_failure_does_not_affect_another():
    class MixedProvider:
        async def complete(self, request, *, timeout_seconds):
            await asyncio.sleep(0)
            if request.context.feature == "DOCUMENT_PARSE":
                raise ProviderError("model_unavailable")
            return answer(input_tokens=5, output_tokens=2)

    async def run_together():
        client = LLMClient(MixedProvider())
        return await asyncio.gather(
            client.generate(request_for("DOCUMENT_PARSE")),
            client.generate(request_for("BEE_CHAT")),
            return_exceptions=True,
        )

    failure, success = run(run_together())
    assert isinstance(failure, LLMError)
    assert failure.usage[0].feature == "DOCUMENT_PARSE"
    assert failure.usage[0].failed is True
    assert success.result == "answer"
    assert success.usage[0].feature == "BEE_CHAT"
    assert success.usage[0].failed is False


def test_callers_supply_distinct_keys_for_separate_requests():
    provider = ScriptedProvider(answer(), answer())
    first = request_for(call_keys=("request-1-call-1",))
    second = request_for(call_keys=("request-2-call-1",))

    async def generate_both():
        client = LLMClient(provider)
        return await client.generate(first), await client.generate(second)

    first_result, second_result = run(generate_both())
    assert first_result.usage[0].request_key == "request-1-call-1"
    assert second_result.usage[0].request_key == "request-2-call-1"
    assert first_result.usage[0].request_key != second_result.usage[0].request_key


def test_cancellation_is_propagated_to_caller():
    class BlockingProvider:
        def __init__(self):
            self.started = asyncio.Event()

        async def complete(self, request, *, timeout_seconds):
            self.started.set()
            await asyncio.Event().wait()

    async def cancel_started_call():
        provider = BlockingProvider()
        task = asyncio.create_task(LLMClient(provider).generate(request_for()))
        await provider.started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError) as caught:
            await task
        assert len(caught.value.usage) == 1
        assert caught.value.usage[0].request_key == "llm-chat-1-call-1"
        assert caught.value.usage[0].failed is True
        assert caught.value.usage[0].tokens_known is False

    run(cancel_started_call())


def test_cancellation_of_a_retry_retains_previous_and_current_call_usage():
    class RetryingProvider:
        def __init__(self):
            self.calls = 0
            self.retry_started = asyncio.Event()

        async def complete(self, request, *, timeout_seconds):
            self.calls += 1
            if self.calls == 1:
                raise ProviderError("model_unavailable", retryable=True)
            self.retry_started.set()
            await asyncio.Event().wait()

    async def cancel_retry():
        provider = RetryingProvider()
        request = request_for(
            max_attempts=2, call_keys=("retry-call-1", "retry-call-2")
        )
        task = asyncio.create_task(LLMClient(provider).generate(request))
        await provider.retry_started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError) as caught:
            await task
        assert provider.calls == 2
        assert [usage.request_key for usage in caught.value.usage] == [
            "retry-call-1",
            "retry-call-2",
        ]
        assert all(usage.failed for usage in caught.value.usage)

    run(cancel_retry())


def test_bad_structured_output_is_failure_and_keeps_known_usage():
    provider = ScriptedProvider(answer("not json", input_tokens=13, output_tokens=4))
    request = request_for(response_format={"type": "json_object"})

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request))

    assert caught.value.code == "invalid_output"
    usage = caught.value.usage[0]
    assert usage.failed is True
    assert usage.tokens_known is True
    assert (usage.input_tokens, usage.output_tokens) == (13, 4)
    wire = caught.value.to_api_dict()
    assert wire["error"]["code"] == "model_unavailable"
    assert wire["usage"][0]["input_tokens"] == 13


def test_feature_output_validation_failure_preserves_all_attempt_usage():
    provider = ScriptedProvider(
        answer("first", input_tokens=10, output_tokens=3),
        ProviderError("model_unavailable", retryable=True),
        answer('{"answer":"not usable"}', input_tokens=7, output_tokens=2),
    )
    first_request = request_for(call_keys=("shared-request-call-1",))
    second_request = request_for(
        max_attempts=2,
        call_keys=("shared-request-call-2", "shared-request-call-3"),
        response_format={"type": "json_object"},
    )

    async def call_twice():
        client = LLMClient(provider)
        first = await client.generate(first_request)
        second = await client.generate(second_request)
        return first, second

    first, second = run(call_twice())
    error = second.invalid_output_error(previous_usage=first.usage)

    assert error.code == "invalid_output"
    assert error.to_api_dict()["error"]["code"] == "model_unavailable"
    assert [item.request_key for item in error.usage] == [
        "shared-request-call-1",
        "shared-request-call-2",
        "shared-request-call-3",
    ]
    assert [item.failed for item in error.usage] == [False, True, True]
    assert error.usage[-1].input_tokens == 7
    assert error.usage[-1].output_tokens == 2
    assert second.usage[-1].failed is False


@pytest.mark.parametrize("invalid_json", ['{"score": 1e999}', '{"score": NaN}'])
def test_non_finite_numbers_in_structured_output_are_rejected(invalid_json):
    provider = ScriptedProvider(answer(invalid_json, input_tokens=2, output_tokens=1))
    request = request_for(response_format={"type": "json_object"})

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request))

    assert caught.value.code == "invalid_output"
    assert caught.value.usage[0].failed is True
    assert caught.value.usage[0].tokens_known is True


def test_unexpected_provider_exception_does_not_expose_prompt_or_secret():
    secret = "sensitive-api-key-do-not-log"
    prompt = "sensitive-document-do-not-log"
    provider = ScriptedProvider(RuntimeError(f"{secret} {prompt}"))
    request = request_for(messages=(LLMMessage(role="user", content=prompt),))

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request))

    assert caught.value.code == "model_unavailable"
    assert len(caught.value.usage) == 1
    assert secret not in str(caught.value)
    assert prompt not in str(caught.value)
    assert secret not in repr(caught.value)
    assert prompt not in repr(caught.value)
    full_traceback = "".join(traceback.format_exception(caught.value))
    assert secret not in full_traceback
    assert prompt not in full_traceback


@pytest.mark.parametrize(
    ("echoed_model", "prompt"),
    [
        ("test-only-key", "ordinary prompt"),
        ("alice@example.org", "고객 이메일은 alice@example.org"),
    ],
)
def test_untrusted_success_model_echo_is_rejected_without_leaking(echoed_model, prompt):
    provider = ScriptedProvider(
        ProviderResponse(
            content="ordinary answer",
            model=echoed_model,
            input_tokens=2,
            output_tokens=1,
        )
    )
    request = request_for(
        messages=(LLMMessage(role="user", content=prompt),),
        allowed_response_models=(),
    )

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request))

    assert caught.value.code == "invalid_output"
    assert echoed_model not in json.dumps(caught.value.to_api_dict())
    assert echoed_model not in "".join(traceback.format_exception(caught.value))
    assert caught.value.usage[0].model == "configured-model"
    assert caught.value.usage[0].input_tokens == 2
    assert caught.value.usage[0].output_tokens == 1


def test_openai_compatible_adapter_sends_format_and_preserves_given_sources():
    captured = []
    source = Source(
        document_id="201", document_version_id="211", version_no=1, location="3-8"
    )
    format_option = {
        "type": "json_schema",
        "json_schema": {
            "name": "answer",
            "strict": True,
            "schema": {
                "type": "object",
                "properties": {"answer": {"type": "string"}},
                "required": ["answer"],
                "additionalProperties": False,
            },
        },
    }

    def handler(http_request):
        captured.append(http_request)
        return httpx.Response(
            200,
            json={
                "model": "provider-model",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": '{"answer":"ok"}'},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {"prompt_tokens": 15, "completion_tokens": 9},
            },
        )

    provider = OpenAICompatibleProvider(
        endpoint="https://llm.example/v1/chat/completions",
        api_key="test-only-key",
        transport=httpx.MockTransport(handler),
    )
    request = request_for(
        response_format=format_option,
        sources=(source,),
        model_options={"temperature": 0.2},
    )

    result = run(LLMClient(provider).generate(request))

    assert result.result == {"answer": "ok"}
    assert result.sources == (source,)
    assert len(captured) == 1
    body = json.loads(captured[0].content)
    assert body["model"] == "configured-model"
    assert body["messages"] == [
        {"role": "user", "content": "What does the document say?"}
    ]
    assert body["response_format"] == format_option
    assert body["temperature"] == 0.2
    assert result.usage[0].input_tokens == 15
    assert result.usage[0].output_tokens == 9


def test_http_provider_does_not_retry_validation_and_hides_provider_error_body():
    calls = []
    secret = "fake-provider-secret"

    def handler(http_request):
        calls.append(http_request)
        return httpx.Response(503, json={"error": {"message": secret}})

    provider = OpenAICompatibleProvider(
        endpoint="https://llm.example/v1/chat/completions",
        api_key="test-only-key",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request_for("TASK_VALIDATION")))

    assert len(calls) == 1
    assert caught.value.code == "model_unavailable"
    assert secret not in str(caught.value)
    assert caught.value.usage[0].failed is True


@pytest.mark.parametrize("status_code", [400, 422])
def test_provider_client_error_is_upstream_failure_not_local_input_error(status_code):
    calls = []
    secret = "provider-error-secret"

    def handler(http_request):
        calls.append(http_request)
        return httpx.Response(
            status_code,
            json={
                "error": {"message": secret},
                "usage": {"prompt_tokens": 2, "completion_tokens": 1},
            },
        )

    provider = OpenAICompatibleProvider(
        endpoint="https://llm.example/v1/chat/completions",
        api_key="test-only-key",
        transport=httpx.MockTransport(handler),
    )
    request = request_for(
        max_attempts=2, call_keys=("provider-error-call-1", "provider-error-call-2")
    )

    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request))

    assert len(calls) == 1
    assert caught.value.code == "model_unavailable"
    assert caught.value.to_api_dict()["error"]["code"] == "model_unavailable"
    assert secret not in json.dumps(caught.value.to_api_dict())
    assert secret not in "".join(traceback.format_exception(caught.value))
    assert len(caught.value.usage) == 1
    assert caught.value.usage[0].request_key == "provider-error-call-1"
    assert caught.value.usage[0].failed is True
    assert caught.value.usage[0].input_tokens == 2
    assert caught.value.usage[0].output_tokens == 1


def test_http_error_model_echo_does_not_leak_secret_or_lose_known_usage():
    secret = "fake-provider-secret"

    def handler(http_request):
        return httpx.Response(
            503,
            json={
                "model": secret,
                "error": {"message": secret},
                "usage": {"prompt_tokens": 2, "completion_tokens": 1},
            },
        )

    provider = OpenAICompatibleProvider(
        endpoint="https://llm.example/v1/chat/completions",
        api_key=secret,
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request_for()))

    assert caught.value.code == "model_unavailable"
    assert secret not in json.dumps(caught.value.to_api_dict())
    assert secret not in "".join(traceback.format_exception(caught.value))
    assert len(caught.value.usage) == 1
    assert caught.value.usage[0].model == "configured-model"
    assert caught.value.usage[0].failed is True
    assert caught.value.usage[0].input_tokens == 2
    assert caught.value.usage[0].output_tokens == 1


def test_truncated_http_output_is_failure_even_if_content_is_valid_json():
    def handler(http_request):
        return httpx.Response(
            200,
            json={
                "model": "provider-model",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": '{"ok":true}'},
                        "finish_reason": "length",
                    }
                ],
                "usage": {"prompt_tokens": 3, "completion_tokens": 4},
            },
        )

    provider = OpenAICompatibleProvider(
        endpoint="https://llm.example/v1/chat/completions",
        api_key="test-only-key",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(LLMError) as caught:
        run(
            LLMClient(provider).generate(
                request_for(response_format={"type": "json_object"})
            )
        )

    assert caught.value.code == "invalid_output"
    assert len(caught.value.usage) == 1
    assert caught.value.usage[0].failed is True
    assert caught.value.usage[0].tokens_known is True
    assert caught.value.usage[0].input_tokens == 3
    assert caught.value.usage[0].output_tokens == 4


@pytest.mark.parametrize(
    "choices",
    [
        [],
        [{"message": {"role": "assistant", "content": None}, "finish_reason": "stop"}],
        [
            {
                "message": {
                    "role": "assistant",
                    "content": None,
                    "refusal": "refusal-text-must-not-leak",
                },
                "finish_reason": "stop",
            }
        ],
    ],
)
def test_missing_or_refused_content_is_failure_and_retains_usage(choices):
    def handler(http_request):
        return httpx.Response(
            200,
            json={
                "model": "provider-model",
                "choices": choices,
                "usage": {"prompt_tokens": 3, "completion_tokens": 4},
            },
        )

    provider = OpenAICompatibleProvider(
        endpoint="https://llm.example/v1/chat/completions",
        api_key="test-only-key",
        transport=httpx.MockTransport(handler),
    )
    with pytest.raises(LLMError) as caught:
        run(LLMClient(provider).generate(request_for()))

    assert caught.value.code == "invalid_output"
    assert "refusal-text-must-not-leak" not in str(caught.value)
    assert len(caught.value.usage) == 1
    assert caught.value.usage[0].failed is True
    assert caught.value.usage[0].tokens_known is True
    assert caught.value.usage[0].input_tokens == 3
    assert caught.value.usage[0].output_tokens == 4
