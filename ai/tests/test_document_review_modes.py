"""Review-mode model boundaries with an in-memory provider, never live calls."""

import asyncio
import copy
import json
from pathlib import Path

import pytest

from app.schemas.document_review import DocumentReviewRequest, text_hash
from app.services.document_review_modes import analyze_mode
from app.services.llm_client import LLMClient, LLMError, ProviderError, ProviderResponse

FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "backend/src/test/resources/document/review-contract-partial.json"
)


class FakeProvider:
    def __init__(self, output):
        self.output = output
        self.requests = []
        self.failure = None

    async def complete(self, request, *, timeout_seconds):
        self.requests.append(request)
        if self.failure:
            raise self.failure
        return ProviderResponse(
            json.dumps(self.output, ensure_ascii=False), request.model, 12, 7
        )


def body(mode="REQUEST", *, comment=False):
    payload = json.loads(FIXTURE.read_text(encoding="utf-8"))["input"]
    payload["mode"] = mode
    if mode == "REQUEST":
        payload["request_text"] = "로그인 설명을 인증으로 변경해 주세요."
    else:
        payload["suggestion"] = {
            "id": "1",
            "type": "COMMENT" if comment else "PATCH",
            "step": "BODY",
            "explanation": "명확하게 쓰세요",
            "original_text": None if comment else "로그인한다.",
            "replacement_text": None if comment else "인증한다.",
            "decision": None if comment else "PENDING",
        }
        payload["messages"] = [{"author_kind": "HUMAN", "text": "왜 필요한가요?"}]
    return DocumentReviewRequest.model_validate(payload)


def call(request, output):
    provider = FakeProvider(output)
    result = asyncio.run(analyze_mode(request, LLMClient(provider), "test-model"))
    return result, provider


def patch():
    return {
        "type": "PATCH",
        "step": "BODY",
        "explanation": "표현을 구체화해요.",
        "sources": [],
        "original_text": "로그인한다.",
        "replacement_text": "인증한다.",
    }


def test_discuss_produces_v2_identifiers_and_patch_alternative():
    request = body("DISCUSS")
    result, provider = call(
        request,
        {"reply": {"text": "표현을 명확하게 해요.", "proposed_text": "인증해요."}},
    )
    assert result["origin"] == "MODEL"
    assert result["result"]["reply"] == {
        "text": "표현을 명확하게 해요.",
        "proposed_text": "인증해요.",
    }
    for field in (
        "document_id",
        "revision",
        "content_hash",
        "reference_hash",
        "request_key",
    ):
        assert result["result"][field] == getattr(request, field)
    assert result["usage"][0]["feature"] == "BEE_DRAFT"
    assert result["usage"][0]["failed"] is False
    messages = provider.requests[0].messages
    assert messages[0].role == "system"
    assert request.messages[0].text not in messages[0].content
    payload = json.loads(messages[1].content)
    assert payload["messages"][0]["text"] == request.messages[0].text
    assert payload["content"] == request.content


def test_discuss_empty_proposed_text_means_deletion_and_is_not_dropped():
    result, _ = call(
        body("DISCUSS"), {"reply": {"text": "삭제할 수 있어요.", "proposed_text": ""}}
    )
    assert result["result"]["reply"]["proposed_text"] == ""


@pytest.mark.parametrize("decision", ["APPLIED", "SKIPPED", None])
def test_processed_suggestion_still_allows_discussion_but_not_alternative(decision):
    payload = body("DISCUSS").model_dump()
    payload["suggestion"]["decision"] = decision
    # An applied patch's original may no longer be in the current snapshot.
    payload["content"] = "현재 문서"
    payload["content_hash"] = text_hash(payload["content"])
    request = DocumentReviewRequest.model_validate(payload)
    result, _ = call(request, {"reply": {"text": "기존 제안에 대한 설명이에요."}})
    assert result["result"]["reply"] == {"text": "기존 제안에 대한 설명이에요."}
    with pytest.raises(LLMError) as exc:
        call(request, {"reply": {"text": "변경해요.", "proposed_text": "새 문장"}})
    assert exc.value.code == "invalid_output"
    assert exc.value.usage[0].failed is True


@pytest.mark.parametrize(
    ("content", "original", "replacement"),
    [
        ("로그인한다.", "로그인한다.", ""),  # Empty resulting document.
        ("로그인한다.  ", "로그인한다.", ""),  # Blank resulting document.
        ("로그인한다.", "로그인한다.", "변경\x01"),  # Invalid control character.
        ("다른 문장", "로그인한다.", "변경"),  # Original no longer exists.
        ("로그인한다. 로그인한다.", "로그인한다.", "변경"),  # Ambiguous original.
    ],
)
def test_discuss_rejects_unapplicable_alternative(content, original, replacement):
    payload = body("DISCUSS").model_dump()
    payload["content"] = content
    payload["content_hash"] = text_hash(content)
    payload["suggestion"]["original_text"] = original
    request = DocumentReviewRequest.model_validate(payload)
    with pytest.raises(LLMError) as exc:
        call(request, {"reply": {"text": "변경해요.", "proposed_text": replacement}})
    assert exc.value.code == "invalid_output"
    assert exc.value.usage[0].failed is True


def test_discuss_accepts_optional_empty_suggestions():
    result, _ = call(
        body("DISCUSS"), {"reply": {"text": "확인했어요."}, "suggestions": []}
    )
    assert result["result"]["reply"] == {"text": "확인했어요."}
    assert result["result"].get("suggestions", []) == []


@pytest.mark.parametrize("mode", ["DISCUSS", "REQUEST"])
def test_optional_summary_is_validated_and_returned(mode):
    output = (
        {"reply": {"text": "확인했어요."}}
        if mode == "DISCUSS"
        else {"suggestions": [patch()]}
    )
    output["summary"] = "본문을 확인했어요."
    result, _ = call(body(mode), output)
    assert result["result"]["summary"] == "본문을 확인했어요."
    output["summary"] = "\x00"
    provider = FakeProvider(output)
    with pytest.raises(LLMError) as exc:
        asyncio.run(analyze_mode(body(mode), LLMClient(provider), "test-model"))
    assert exc.value.code == "invalid_output"


@pytest.mark.parametrize(
    ("output", "comment"),
    [
        ({"reply": {"text": " "}}, False),
        ({"reply": {"text": "😀" * 5001}}, False),
        ({"reply": {"text": "설명\x00"}}, False),
        ({"reply": {"text": "설명", "proposed_text": "수정\x00"}}, False),
        ({"reply": {"text": "설명", "proposed_text": "x"}}, True),
        ({"reply": {"text": "설명", "proposed_text": "😀" * 50001}}, False),
        ({"reply": {"text": "설명"}, "suggestions": [patch()]}, False),
    ],
)
def test_invalid_discussion_is_failed_usage_without_leaking_output(output, comment):
    provider = FakeProvider(output)
    with pytest.raises(LLMError) as exc:
        asyncio.run(
            analyze_mode(
                body("DISCUSS", comment=comment), LLMClient(provider), "test-model"
            )
        )
    assert exc.value.code == "invalid_output"
    assert exc.value.usage[0].failed is True
    assert "proposed_text" not in str(exc.value)


def test_request_emits_one_body_patch_and_v2_echo():
    request = body()
    result, provider = call(request, {"suggestions": [patch()]})
    assert result["result"]["suggestions"] == [patch()]
    assert result["result"]["content_hash"] == request.content_hash
    assert (
        json.loads(provider.requests[0].messages[1].content)["request_text"]
        == request.request_text
    )


def test_request_body_patch_does_not_expose_reference_content_to_model():
    fixture = FIXTURE.with_name("review-contract-complete.json")
    payload = json.loads(fixture.read_text(encoding="utf-8"))["input"]
    payload.update({"mode": "REQUEST", "request_text": "본문을 바꿔 주세요"})
    request = DocumentReviewRequest.model_validate(payload)
    _, provider = call(request, {"suggestions": [patch()]})
    model_payload = json.loads(provider.requests[0].messages[1].content)
    assert request.references
    assert "references" not in model_payload


@pytest.mark.parametrize(
    "mutation",
    [
        "missing",
        "duplicate",
        "source",
        "step",
        "comment",
        "missing_original",
        "empty_original",
        "explanation",
        "null_explanation",
        "oversize",
    ],
)
def test_request_rejects_unsafe_or_unverifiable_model_output(mutation):
    suggestion = copy.deepcopy(patch())
    suggestions = [suggestion]
    if mutation == "missing":
        suggestions = []
    elif mutation == "duplicate":
        suggestions.append(copy.deepcopy(suggestion))
    elif mutation == "source":
        suggestion["sources"] = [{"reference_id": "forged"}]
    elif mutation == "step":
        suggestion["step"] = "ERD"
    elif mutation == "comment":
        suggestion["type"] = "COMMENT"
    elif mutation == "missing_original":
        suggestion["original_text"] = "없는 문장"
    elif mutation == "empty_original":
        suggestion["original_text"] = ""
    elif mutation == "explanation":
        suggestion["explanation"] = " "
    elif mutation == "null_explanation":
        suggestion["explanation"] = "설명\x00"
    else:
        suggestion["replacement_text"] = "😀" * 50001
    provider = FakeProvider({"suggestions": suggestions})
    with pytest.raises(LLMError) as exc:
        asyncio.run(analyze_mode(body(), LLMClient(provider), "test-model"))
    assert exc.value.code == "invalid_output"
    assert exc.value.usage[0].failed is True


def test_provider_failure_propagates_reported_usage():
    provider = FakeProvider({})
    provider.failure = ProviderError("model_timeout", input_tokens=3, output_tokens=1)
    with pytest.raises(LLMError) as exc:
        asyncio.run(analyze_mode(body(), LLMClient(provider), "test-model"))
    assert exc.value.code == "model_timeout"
    assert exc.value.usage[0].failed is True


def test_request_with_nothing_to_change_is_a_normal_answer_with_a_reason():
    request = body()
    result, _ = call(
        request,
        {
            "outcome": "NO_CHANGE",
            "suggestions": [],
            "summary": "요청한 속도가 이미 3.2절에 있어요.",
        },
    )
    assert result["result"]["outcome"] == "NO_CHANGE"
    assert result["result"]["suggestions"] == []
    assert result["result"]["summary"] == "요청한 속도가 이미 3.2절에 있어요."
    assert result["result"]["content_hash"] == request.content_hash
    # 정상 답이라 실패 사용량으로 세지 않는다.
    assert all(not usage.get("failed") for usage in result["usage"])


def test_request_without_outcome_still_reads_an_empty_answer_with_reason_as_no_change():
    result, _ = call(body(), {"suggestions": [], "summary": "이미 반영돼 있어요."})
    assert result["result"]["outcome"] == "NO_CHANGE"


@pytest.mark.parametrize(
    "output",
    [
        {"outcome": "NO_CHANGE", "suggestions": []},
        {"outcome": "NO_CHANGE", "suggestions": [], "summary": " "},
        {"outcome": "NO_CHANGE", "suggestions": [], "summary": "이유\x00"},
        {"outcome": "SUGGESTION", "suggestions": [], "summary": "이유"},
        {"outcome": "NO_CHANGE", "suggestions": [patch()], "summary": "이유"},
    ],
)
def test_request_rejects_no_change_without_a_usable_reason_or_with_a_patch(output):
    provider = FakeProvider(output)
    with pytest.raises(LLMError) as exc:
        asyncio.run(analyze_mode(body(), LLMClient(provider), "test-model"))
    assert exc.value.code == "invalid_output"
    assert exc.value.usage[0].failed is True


def test_request_patch_answer_carries_the_suggestion_outcome():
    result, _ = call(body(), {"suggestions": [patch()]})
    assert result["result"]["outcome"] == "SUGGESTION"
