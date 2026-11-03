"""HTTP contract of /v1/bee/chat and /v1/bee/instances; disabled by default, fakes only."""

import json
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.services.bee_chat import INSUFFICIENT_TEXT
from app.services.llm_client import ProviderError
from evaluations.bee_chat.examples import (
    CHAT_PATH,
    HEADERS,
    INSTANCE_PATH,
    ExampleApp,
    build_examples,
    example_request,
    instance_request,
    settings,
)

EXAMPLES = (
    Path(__file__).resolve().parents[2] / "docs" / "api" / "examples" / "bee-chat"
)


@pytest.fixture(scope="module")
def request_body():
    return example_request()


@pytest.fixture
def api():
    with ExampleApp() as example_app:
        yield example_app


def test_routes_are_disabled_by_default(request_body):
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="internal"
    )
    try:
        with patch("app.main.EmbeddingTokenCounter"), TestClient(app) as client:
            headers = {"Authorization": "Bearer internal"}
            chat = client.post(CHAT_PATH, json=request_body, headers=headers)
            instance = client.post(
                INSTANCE_PATH, json=instance_request(), headers=headers
            )
    finally:
        app.dependency_overrides.clear()
    for response in (chat, instance):
        assert response.status_code == 503
        assert response.json()["error"]["code"] == "bee_chat_not_configured"


def test_internal_token_is_required(api, request_body):
    response = api.post(request_body, headers={"Authorization": "Bearer x"})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_internal_token"
    assert (
        api.post(instance_request(), path=INSTANCE_PATH, headers={}).status_code == 401
    )
    assert not api.provider.calls


def test_instance_initialization_is_deterministic_without_a_model(api):
    first = api.post(instance_request(), path=INSTANCE_PATH)
    second = api.post(instance_request(), path=INSTANCE_PATH)
    assert first.status_code == 200 and first.json() == second.json()
    body = first.json()
    assert body["origin"] == "TEMPLATE" and body["usage"] == [] and "model" not in body
    instance = body["result"]["instance"]
    assert instance["project_id"] == "101" and instance["profile_version"] == "bee-v1"
    assert instance["persona"] == "비비" and instance["project"]["name"] == "나래"
    # The project's own text is data for the model, never system instructions.
    assert "나래" not in instance["system_prompt"]
    assert not api.provider.calls
    blank = api.post(
        {**instance_request(), "project": {"name": "  "}}, path=INSTANCE_PATH
    )
    assert blank.status_code == 422
    missing = api.post({"project_id": "101"}, path=INSTANCE_PATH)
    assert missing.status_code == 400


def test_grounded_answer_carries_citations_refs_and_usage(api, request_body):
    instance = api.post(instance_request(), path=INSTANCE_PATH).json()["result"][
        "instance"
    ]
    response = api.post(request_body)
    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "bee-chat-example"
    body = response.json()
    assert body["origin"] == "MODEL" and body["model"] == "gpt-6-luna"
    usage = body["usage"][0]
    assert usage["feature"] == "BEE_CHAT" and not usage["failed"]
    assert usage["request_key"].startswith("llm-bee-chat-")
    assert api.provider.calls[0].context.request_key == request_body["request_key"]
    result = body["result"]
    assert result["answer_status"] == "ANSWERED" and result["grounded"]
    assert [c["n"] for c in result["citations"]] == [1, 2]
    assert result["citations"][1]["quote"].startswith("문서 생성 성공은 HTTP 201")
    assert [ref["key"] for ref in result["design_refs"]] == ["API-001"]
    assert [ref["key"] for ref in result["task_refs"]] == ["TASK-001"]
    assert result["instance"]["system_prompt_hash"] == instance["system_prompt_hash"]
    assert result["scope"] == request_body["scope"]
    # Sources are the two citations; the item's stored source stays on design_refs.
    assert len(result["sources"]) == 2
    assert result["design_refs"][0]["source"]["document_version_id"] == "312"


def test_insufficient_and_out_of_scope_are_200_with_fixed_text(api):
    withheld = api.post(example_request("password-absent")).json()["result"]
    assert withheld["answer_status"] == "INSUFFICIENT_EVIDENCE"
    assert withheld["text"] == INSUFFICIENT_TEXT and not withheld["grounded"]
    assert withheld["unresolved"] == ["비밀번호 정책은 공용 문서에 없다."]
    scope = api.post(example_request("out-of-scope")).json()["result"]
    assert scope["answer_status"] == "OUT_OF_SCOPE" and not scope["citations"]
    # A fabricated quote is a model failure (502), never a shown or withheld guess.
    fabricated = api.post(example_request("req-absent"))
    assert fabricated.status_code == 502
    assert fabricated.json()["error"]["code"] == "model_unavailable"
    assert fabricated.json()["usage"][0]["failed"]
    assert "90일" not in fabricated.text
    task_only = api.post(example_request("task-status")).json()["result"]
    assert task_only["answer_status"] == "ANSWERED" and task_only["grounded"]
    assert [ref["key"] for ref in task_only["task_refs"]] == ["TASK-001"]


def test_body_limit_media_type_and_json_errors(api, request_body):
    api.use(settings(bee_chat_max_body_bytes="1024"))
    assert api.post(request_body).status_code == 413
    api.use(settings())
    wrong_type = api.client.post(
        CHAT_PATH, content=b"{}", headers={"Content-Type": "text/plain", **HEADERS}
    )
    assert wrong_type.status_code == 415
    assert api.post(content=b"{").status_code == 400
    outside = {
        **request_body,
        "retrieved_evidence": [
            {**request_body["retrieved_evidence"][0], "document_version_id": "999"}
        ],
    }
    semantic = api.post(outside)
    assert semantic.status_code == 422
    assert semantic.json()["error"]["code"] == "invalid_ai_input"
    assert not api.provider.calls


def test_model_failures_and_contradictions_keep_usage(api, request_body):
    api.provider.failure = ProviderError(
        "model_unavailable", model="gpt-6-luna", input_tokens=9, output_tokens=0
    )
    response = api.post(request_body)
    assert response.status_code == 502
    body = response.json()
    assert body["error"]["code"] == "model_unavailable"
    assert body["usage"][0]["failed"] and body["usage"][0]["input_tokens"] == 9
    api.provider.failure = ProviderError("model_timeout", retryable=True)
    assert api.post(request_body).status_code == 504
    api.provider.failure = None
    api.provider.output = {
        "status": "ANSWERED",
        "text": "x [1]",
        "citations": [{"evidence_id": "ev-none", "quote": "x"}],
        "design_refs": [],
        "task_refs": [],
        "unresolved": [],
        "next_actions": [],
    }
    response = api.post(request_body)
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "model_unavailable"
    assert response.json()["usage"][0]["failed"]
    assert "ev-none" not in response.text


def test_openapi_request_schemas_are_self_contained(api):
    paths = api.client.get("/openapi.json").json()["paths"]
    for path in (CHAT_PATH, INSTANCE_PATH):
        schema = paths[path]["post"]["requestBody"]["content"]["application/json"][
            "schema"
        ]
        assert "$defs" not in schema and "#/$defs/" not in json.dumps(schema)
    chat = paths[CHAT_PATH]["post"]["requestBody"]["content"]["application/json"][
        "schema"
    ]
    evidence = chat["properties"]["retrieved_evidence"]["items"]
    assert "locator" in evidence["properties"]
    assert "baseline_id" in chat["properties"]["scope"]["properties"]


def test_published_examples_match_the_http_contract():
    for name, payload in build_examples().items():
        published = json.loads((EXAMPLES / name).read_text(encoding="utf-8"))
        assert published == payload, name
