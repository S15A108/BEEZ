import asyncio
import logging

import httpx
import pytest

from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMMessage,
    LLMRequest,
    OpenAICompatibleProvider,
)


@pytest.mark.parametrize(
    "finish,reasoning",
    [("stop", 4), ("length", 1500), ("stop", "secret-value"), (["untrusted"], 0)],
)
def test_completion_logs_safe_diagnostics_even_when_output_is_empty(
    caplog, finish, reasoning
):
    async def run():
        def handler(_):
            return httpx.Response(
                200,
                json={
                    "model": "test-model",
                    "choices": [
                        {
                            "finish_reason": finish,
                            "message": {"content": "{}" if finish == "stop" else ""},
                        }
                    ],
                    "usage": {
                        "prompt_tokens": 4155,
                        "completion_tokens": 1500,
                        "completion_tokens_details": {"reasoning_tokens": reasoning},
                    },
                },
            )

        async with OpenAICompatibleProvider(
            "https://example.test/v1/chat/completions",
            "secret-key",
            transport=httpx.MockTransport(handler),
        ) as provider:
            request = LLMRequest(
                "test-model",
                (LLMMessage("user", "private-document"),),
                LLMContext("1", "BEE_DRAFT", "r1"),
                ("call1",),
                2,
                response_format={"type": "json_object"},
                diagnostics="task_plan",
            )
            try:
                result = await LLMClient(provider).generate(request)
                # Diagnostics must not change the existing usage wire contract.
                assert "reasoning_tokens" not in result.to_api_dict()["usage"][0]
            except LLMError as exc:
                assert exc.code == (
                    "output_truncated" if finish == "length" else "invalid_output"
                )

    with caplog.at_level(logging.INFO):
        asyncio.run(run())
    assert "task_plan_completion" in caplog.text
    assert "input_tokens=4155" in caplog.text
    assert "secret-key" not in caplog.text
    assert "private-document" not in caplog.text
    assert "secret-value" not in caplog.text
    if finish == "length":
        assert "reasoning_tokens=1500" in caplog.text
        assert "finish_reason=length" in caplog.text


def test_other_features_sharing_the_draft_feature_name_do_not_emit_the_task_plan_log(
    caplog,
):
    async def run():
        def handler(_):
            return httpx.Response(
                200,
                json={
                    "model": "test-model",
                    "choices": [
                        {"finish_reason": "stop", "message": {"content": "{}"}}
                    ],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 5},
                },
            )

        async with OpenAICompatibleProvider(
            "https://example.test/v1/chat/completions",
            "secret-key",
            transport=httpx.MockTransport(handler),
        ) as provider:
            # 문서 검토·설계 초안도 feature 이름은 BEE_DRAFT 이지만 진단 로그를 켜지 않았다.
            request = LLMRequest(
                "test-model",
                (LLMMessage("user", "private-document"),),
                LLMContext("1", "BEE_DRAFT", "r2"),
                ("call2",),
                2,
                response_format={"type": "json_object"},
            )
            await LLMClient(provider).generate(request)

    with caplog.at_level(logging.INFO):
        asyncio.run(run())
    assert "_completion" not in caplog.text


def test_diagnostics_name_must_be_a_plain_identifier(caplog):
    async def run():
        def handler(_):
            return httpx.Response(200, json={})

        async with OpenAICompatibleProvider(
            "https://example.test/v1/chat/completions",
            "secret-key",
            transport=httpx.MockTransport(handler),
        ) as provider:
            for bad in ("Task Plan", "x\ny", "", "a" * 40):
                request = LLMRequest(
                    "test-model",
                    (LLMMessage("user", "x"),),
                    LLMContext("1", "BEE_DRAFT", "r3"),
                    ("call3",),
                    2,
                    diagnostics=bad,
                )
                try:
                    await LLMClient(provider).generate(request)
                except LLMError as exc:
                    assert exc.code == "invalid_input"
                else:
                    raise AssertionError(bad)

    asyncio.run(run())
