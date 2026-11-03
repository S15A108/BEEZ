"""Request analysis uses a fake provider and never makes network calls."""

import json

import pytest

from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    ProviderError,
    ProviderResponse,
)
from app.services.retrieval.context_request_analysis import (
    DevelopmentRequest,
    LLMRequestAnalyzer,
)

TEXT = "  Markdown 문서 업로드 생성 API의 30 MB 제한을 구현해 주세요.  "


class FakeProvider:
    def __init__(self, output):
        self.output = output
        self.calls = []

    async def complete(self, request, *, timeout_seconds):
        self.calls.append(request)
        if isinstance(self.output, Exception):
            raise self.output
        return ProviderResponse(
            content=json.dumps(self.output, ensure_ascii=False),
            model=request.model,
            input_tokens=12,
            output_tokens=7,
        )


def analyzer(provider, *, project="p", task=None, **kwargs):
    # BEE_CHAT is a test fixture; #131 has no agreed production feature.
    return LLMRequestAnalyzer(
        LLMClient(provider),
        context=LLMContext(
            project_id=project,
            task_id=task,
            feature="BEE_CHAT",
            request_key="request-1",
        ),
        model="fake",
        call_key="call-1",
        timeout_seconds=3,
        **kwargs,
    )


@pytest.mark.asyncio
async def test_hints_are_anchored_and_original_query_and_usage_are_preserved():
    provider = FakeProvider(
        {
            "functions": ["업로드 생성"],
            "domains": ["문서"],
            "keywords": ["Markdown", "30 MB", "문서"],
        }
    )
    result = await analyzer(provider).analyze(DevelopmentRequest("p", None, TEXT))
    assert result.query == TEXT
    assert result.functions == ("업로드 생성",)
    assert result.domains == ("문서",)
    assert result.keywords == ("Markdown", "30 MB", "문서")
    assert result.usage[0].input_tokens == 12 and not result.usage[0].failed
    assert len(provider.calls) == 1
    llm_request = provider.calls[0]
    assert llm_request.messages[0].role == "system"
    assert "untrusted" in llm_request.messages[0].content.lower()
    assert json.loads(llm_request.messages[1].content) == {"request": TEXT}
    assert llm_request.response_format == {"type": "json_object"}
    assert TEXT not in repr(result)


@pytest.mark.asyncio
async def test_empty_categories_are_unresolved_hints_not_search_failure():
    provider = FakeProvider({"functions": [], "domains": [], "keywords": []})
    result = await analyzer(provider).analyze(DevelopmentRequest("p", None, TEXT))
    assert result.query == TEXT and not result.functions and not result.domains
    assert not result.keywords


@pytest.mark.asyncio
async def test_matching_task_scope_is_forwarded_to_the_client():
    provider = FakeProvider({"functions": [], "domains": [], "keywords": []})
    result = await analyzer(provider, task="task-1").analyze(
        DevelopmentRequest("p", "task-1", TEXT)
    )
    assert result.usage[0].task_id == "task-1"
    assert provider.calls[0].context.project_id == "p"
    assert provider.calls[0].context.task_id == "task-1"


@pytest.mark.parametrize(
    "bad_output",
    [
        {"functions": ["결제"], "domains": [], "keywords": []},
        {"functions": ["업로드", "업로드"], "domains": [], "keywords": []},
        {"functions": [" 업로드"], "domains": [], "keywords": []},
        {"functions": [""], "domains": [], "keywords": []},
        {"functions": [], "domains": [], "keywords": ["문서"] * 9},
        {"functions": [], "domains": [], "keywords": [42]},
        {"functions": [], "domains": []},
        {"functions": [], "domains": [], "keywords": [], "authorized": True},
    ],
)
@pytest.mark.asyncio
async def test_invalid_model_output_is_rejected_and_usage_retained(bad_output):
    provider = FakeProvider(bad_output)
    with pytest.raises(LLMError) as error:
        await analyzer(provider).analyze(DevelopmentRequest("p", None, TEXT))
    assert error.value.code == "invalid_output"
    assert len(provider.calls) == 1
    assert error.value.usage[0].failed
    assert error.value.usage[0].input_tokens == 12
    assert error.value.usage[0].output_tokens == 7
    assert TEXT not in str(error.value)


@pytest.mark.asyncio
async def test_overlong_anchored_term_is_rejected():
    term = "가" * 81
    provider = FakeProvider({"functions": [term], "domains": [], "keywords": []})
    with pytest.raises(LLMError) as error:
        await analyzer(provider).analyze(DevelopmentRequest("p", None, term))
    assert error.value.code == "invalid_output" and error.value.usage[0].failed


@pytest.mark.parametrize(
    "development_request",
    [
        DevelopmentRequest("other", None, TEXT),
        DevelopmentRequest("p", "other", TEXT),
        DevelopmentRequest("p", None, "  "),
        DevelopmentRequest("p", None, "a" * 2001),
        DevelopmentRequest("p", None, "\ud800"),
    ],
)
@pytest.mark.asyncio
async def test_invalid_request_or_scope_never_reaches_provider(development_request):
    provider = FakeProvider({"functions": [], "domains": [], "keywords": []})
    with pytest.raises(LLMError) as error:
        await analyzer(provider).analyze(development_request)
    assert error.value.code == "invalid_input"
    assert not provider.calls and not error.value.usage


@pytest.mark.parametrize(
    "options",
    [
        {"output_token_limit": True},
        {"output_token_limit": 0},
        {"output_token_limit": 4097},
        {"output_token_parameter": "model"},
        {"output_token_parameter": []},
    ],
)
@pytest.mark.asyncio
async def test_invalid_output_budget_never_reaches_provider(options):
    provider = FakeProvider({})
    with pytest.raises(LLMError) as error:
        await analyzer(provider, **options).analyze(DevelopmentRequest("p", None, TEXT))
    assert error.value.code == "invalid_input" and not provider.calls


@pytest.mark.asyncio
async def test_model_failure_keeps_real_failed_usage():
    provider = FakeProvider(
        ProviderError(
            "model_unavailable", model="fake", input_tokens=4, output_tokens=0
        )
    )
    with pytest.raises(LLMError) as error:
        await analyzer(provider).analyze(DevelopmentRequest("p", None, TEXT))
    assert error.value.code == "model_unavailable"
    assert error.value.usage[0].failed and error.value.usage[0].input_tokens == 4
