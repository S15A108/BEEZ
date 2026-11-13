"""HTTP contract of /v1/planning-questions; disabled by default, fakes only."""

import json
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.services.llm_client import ProviderError
from evaluations.planning_questions.cases import PROPOSALS, request_body
from evaluations.planning_questions.examples import (
    HEADERS,
    ExampleApp,
    build_examples,
    settings,
)

EXAMPLES = Path(__file__).resolve().parents[2] / "docs" / "api" / "examples"
EXAMPLES = EXAMPLES / "planning-questions"


@pytest.fixture
def api():
    with ExampleApp() as example_app:
        yield example_app


def test_route_is_disabled_by_default():
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="internal"
    )
    try:
        with patch("app.main.EmbeddingTokenCounter"), TestClient(app) as client:
            response = client.post(
                "/v1/planning-questions",
                json=request_body("plan.md"),
                headers={"Authorization": "Bearer internal"},
            )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "planning_questions_not_configured"


def test_internal_token_is_required(api):
    response = api.post(request_body("plan.md"), headers={"Authorization": "Bearer x"})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_internal_token"
    assert not api.provider.calls


def test_rules_only_mode_never_calls_the_model(api):
    response = api.post(request_body("plan.md", mode="RULES_ONLY"))
    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "planning-example"
    body = response.json()
    assert body["origin"] == "TEMPLATE"
    assert "usage" not in body and "model" not in body
    assert {q["origin"] for q in body["result"]["questions"]} == {"TEMPLATE"}
    assert not api.provider.calls


def test_model_mode_returns_source_checked_candidates_and_usage(api):
    request = request_body("plan.md")
    response = api.post(request)
    assert response.status_code == 200
    body = response.json()
    assert body["origin"] == "MODEL" and body["model"] == "gpt-6-luna"
    usage = body["usage"][0]
    assert usage["feature"] == "DOCUMENT_PARSE" and not usage["failed"]
    assert usage["request_key"].startswith("llm-planning-questions-")
    call = api.provider.calls[0]
    assert call.context.request_key == request["request_key"]
    assert call.context.project_id == "101"
    result = body["result"]
    assert result["document"] == request["document"]
    statuses = {c["summary"]: c["status"] for c in result["candidates"]}
    scripted = PROPOSALS["plan.md"]
    assert statuses[scripted[0]["summary"]] == "REVIEW_CANDIDATE"
    assert statuses[scripted[2]["summary"]] == "UNVERIFIED"
    assert statuses[scripted[3]["summary"]] == "UNVERIFIED"
    for candidate in result["candidates"]:
        assert candidate["candidate_key"].startswith(("rule-", "model-"))
        assert candidate["status"] in {"REVIEW_CANDIDATE", "UNVERIFIED"}
        assert candidate["evidence"] or candidate["status"] == "UNVERIFIED"
    # The fabricated quote from the scripted proposal is never echoed.
    assert "모든 멤버가 조회한다" not in response.text
    assert [q["rank"] for q in result["questions"]] == list(
        range(1, len(result["questions"]) + 1)
    )


def test_body_limit_media_type_and_json_errors(api):
    api.use(settings(planning_questions_max_body_bytes="1024"))
    assert api.post(request_body("plan.md")).status_code == 413
    api.use(settings())
    wrong_type = api.client.post(
        "/v1/planning-questions",
        content=b"{}",
        headers={"Content-Type": "text/plain", **HEADERS},
    )
    assert wrong_type.status_code == 415
    assert api.post(content=b"{").status_code == 400
    assert api.post({**request_body("plan.md"), "kind": "MEMO"}).status_code == 400
    assert not api.provider.calls


def test_parser_failures_are_invalid_ai_input_with_a_reason(api):
    response = api.post({**request_body("plan.md"), "text_hash": "0" * 64})
    assert response.status_code == 422
    error = response.json()["error"]
    assert error["code"] == "invalid_ai_input"
    assert error["details"]["reason"] == "source_hash_mismatch"
    assert not api.provider.calls


def test_model_failures_keep_usage(api):
    api.provider.failure = ProviderError(
        "model_unavailable", model="gpt-6-luna", input_tokens=9, output_tokens=0
    )
    response = api.post(request_body("plan.md"))
    assert response.status_code == 502
    body = response.json()
    assert body["error"]["code"] == "model_unavailable"
    assert body["usage"][0]["failed"] and body["usage"][0]["input_tokens"] == 9
    api.provider.failure = ProviderError("model_timeout", retryable=True)
    response = api.post(request_body("plan.md"))
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "model_timeout"


def test_output_cap_truncation_is_reported_as_its_own_code_with_usage(api):
    # 출력 상한에 닿아 답이 끊기면 형식 오류가 아니라 상한 문제로 알린다 (S15P31A108-382).
    api.provider.failure = ProviderError(
        "output_truncated", model="gpt-6-luna", input_tokens=9, output_tokens=2048
    )
    response = api.post(request_body("plan.md"))
    assert response.status_code == 502
    body = response.json()
    assert body["error"]["code"] == "model_output_truncated"
    assert body["usage"][0]["failed"] and body["usage"][0]["output_tokens"] == 2048


def test_invalid_model_output_is_a_model_failure_with_failed_usage(api):
    api.provider.output = {"candidates": [{"kind": "DEFECT"}]}
    response = api.post(request_body("plan.md"))
    assert response.status_code == 502
    body = response.json()
    assert body["error"]["code"] == "model_unavailable"
    assert body["usage"][0]["failed"]
    assert "DEFECT" not in response.text


def test_published_examples_match_the_http_contract():
    for name, payload in build_examples().items():
        published = json.loads((EXAMPLES / name).read_text(encoding="utf-8"))
        assert published == payload, name


# S15P31A108-388: the settings defaults (strings) and the proposer defaults are
# written in two places; real documents needed up to 3,834 tokens and 39 s.
def test_default_settings_match_the_measured_limits():
    from app.routers.planning_questions import require_planning_question_config
    from app.services import planning_questions

    config = require_planning_question_config(
        Settings(_env_file=None, planning_questions_enabled=True)
    )
    assert config.output_token_limit == planning_questions.DEFAULT_OUTPUT_TOKEN_LIMIT
    assert config.timeout_seconds == planning_questions.DEFAULT_TIMEOUT_SECONDS


# S15P31A108-414: the router hands the request's known questions to the model.
def test_known_questions_reach_the_model_input(api):
    known = [
        {"question": "재취소는?", "status": "ANSWERED", "answer": "409로 거부한다."}
    ]
    response = api.post({**request_body("plan.md"), "known_questions": known})
    assert response.status_code == 200
    payload = json.loads(api.provider.calls[0].messages[1].content)
    assert payload["known_questions"] == known
    bad = [{"question": "q", "status": "SUPERSEDED"}]
    assert (
        api.post({**request_body("plan.md"), "known_questions": bad}).status_code == 400
    )
    assert len(api.provider.calls) == 1
