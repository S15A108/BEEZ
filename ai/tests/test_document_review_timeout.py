"""The model-call limits of document review are settings.

S15P31A108-382 made it a setting with 30 s; a 6,800-character API spec took 31-38 s,
so the default became 50 s inside the BE 55 s / nginx 60 s chain (S15P31A108-383).
S15P31A108-391 moved review/RERUN to a BE worker outside the HTTP request (110 s),
while DISCUSS/REQUEST stays inside the request and keeps 50 s.
"""

import json
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.routers.document_review import get_review_client, router
from app.services.llm_client import LLMClient, ProviderResponse

FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "backend/src/test/resources/document/review-contract-partial.json"
)


class RecordingProvider:
    def __init__(self, output):
        self.output = output
        self.timeouts = []

    async def complete(self, request, *, timeout_seconds):
        self.timeouts.append(timeout_seconds)
        return ProviderResponse(
            json.dumps(self.output, ensure_ascii=False), request.model, 1, 1
        )


def call(settings_kwargs):
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    request = fixture["input"]
    result = fixture["response"]["result"]
    output = {
        "summary": result["summary"],
        "suggestions": [],
        "body_reviewed": True,
        "reviewed_reference_ids": [],
    }
    provider = RecordingProvider(output)
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal", **settings_kwargs
    )
    app.dependency_overrides[get_review_client] = lambda: LLMClient(provider)
    with TestClient(app) as client:
        response = client.post(
            "/v1/document-reviews",
            json=request,
            headers={"Authorization": "Bearer test-internal"},
        )
    return response, provider


def test_default_review_limit_is_one_hundred_ten_seconds():
    assert Settings(_env_file=None).document_review_llm_timeout_seconds == "110"
    _, provider = call({})
    # 모델에는 마감까지 남은 시간이 넘어가므로 설정값보다 마이크로초 단위로 작다. 정확히 같다고 비교하지 않는다.
    assert provider.timeouts == pytest.approx([110.0], abs=0.5)


def test_chat_modes_keep_the_short_limit_inside_the_nginx_chain():
    # 대화는 사용자 요청 안에서 기다리므로 BE 대화 읽기 55초·nginx 60초보다 짧아야 한다(S15P31A108-391).
    from app.routers.document_review import _model_timeout_seconds

    defaults = Settings(_env_file=None)
    assert _model_timeout_seconds(defaults, chat=True) == 50.0
    assert _model_timeout_seconds(defaults, chat=False) == 110.0
    tuned = Settings(_env_file=None, document_review_chat_llm_timeout_seconds="40")
    assert _model_timeout_seconds(tuned, chat=True) == 40.0
    broken = Settings(_env_file=None, document_review_chat_llm_timeout_seconds="121")
    with pytest.raises(Exception) as error:
        _model_timeout_seconds(broken, chat=True)
    assert getattr(error.value, "detail", None) == "document_review_not_configured"


def test_configured_limit_reaches_the_model_call():
    _, provider = call({"document_review_llm_timeout_seconds": "45"})
    assert provider.timeouts == pytest.approx([45.0], abs=0.5)


@pytest.mark.parametrize("value", ["0", "-1", "121", "abc", "nan", "inf", ""])
def test_invalid_limit_is_rejected_before_any_model_call(value):
    response, provider = call({"document_review_llm_timeout_seconds": value})
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "document_review_not_configured"
    assert provider.timeouts == []


def test_rejected_answer_logs_the_reason_without_document_text(caplog):
    import logging

    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    request = fixture["input"]
    bad = {
        "summary": "x",
        "body_reviewed": True,
        "reviewed_reference_ids": [],
        "suggestions": [
            {
                "type": "PATCH",
                "step": "BODY",
                "explanation": "비밀 본문 문장",
                "sources": [],
                "original_text": "문서에 없는 문장",
                "replacement_text": "대체",
                "category": "READABILITY",
                "severity": "MINOR",
            }
        ],
    }
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    app.dependency_overrides[get_review_client] = lambda: LLMClient(
        RecordingProvider(bad)
    )
    with (
        caplog.at_level(logging.WARNING, logger="app.services.document_review"),
        TestClient(app) as client,
    ):
        response = client.post(
            "/v1/document-reviews",
            json=request,
            headers={"Authorization": "Bearer test-internal"},
        )
    assert response.status_code == 502
    text = " ".join(record.getMessage() for record in caplog.records)
    assert "document review answer rejected" in text
    assert "rule:" in text or "schema:" in text
    assert "문서에 없는 문장" not in text and "비밀 본문 문장" not in text
