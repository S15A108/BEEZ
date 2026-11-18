import json
import re
from contextlib import asynccontextmanager
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import design_recommendations as router
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse


def candidate():
    return {
        "design_item_version": {
            "id": "311",
            "design_item_id": "301",
            "key": "API-001",
            "kind": "API_CONTRACT",
            "version_no": 1,
            "status": "APPROVED",
            "schema_version": 1,
            "content": {"description": "로그인 API", "path": "/api/auth/login"},
            "source": {
                "document_id": "201",
                "document_version_id": "211",
                "version_no": 1,
                "location": "3~5행",
            },
        },
        "design_source_verification": "VERIFIED",
    }


def request_body(**changes):
    body = {
        "project_id": "101",
        "request_key": "request-1",
        "call_key": "globally-unique-call-1",
        "request": "로그인 화면을 만들고 싶어요",
        "task_id": "501",
        "search_status": "READY",
        "search_truncated": False,
        "mapping_complete": True,
        "search_observation": "DOCUMENTS_FOUND",
        "execution_budget_ms": 15000,
        "candidates": [candidate()],
    }
    body.update(changes)
    return body


@pytest.fixture(scope="module")
def http_client():
    # Recommendation tests do not exercise tokenizer readiness.
    with patch("app.main.EmbeddingTokenCounter"), TestClient(app) as client:
        yield client


@pytest.fixture
def api(monkeypatch, http_client):
    state = {
        "calls": [],
        "response": {
            "recommendations": [
                {"design_item_version_id": "311", "reason": "로그인 API 계약입니다."}
            ]
        },
        "error": None,
    }
    settings = Settings(
        _env_file=None, ai_internal_token="internal", design_recommendation_enabled=True
    )

    class Provider:
        async def complete(self, request, *, timeout_seconds):
            state["calls"].append(request)
            if state["error"]:
                raise state["error"]
            return ProviderResponse(
                json.dumps(state["response"], ensure_ascii=False), request.model, 5, 3
            )

    @asynccontextmanager
    async def client(_settings):
        yield LLMClient(Provider())

    monkeypatch.setattr(router, "get_recommendation_client", client)
    monkeypatch.setitem(app.dependency_overrides, get_settings, lambda: settings)
    yield http_client, state


def post(client, body):
    return client.post(
        "/v1/design-reference-recommendations",
        json=body,
        headers={"Authorization": "Bearer internal"},
    )


def test_model_selects_only_supplied_design_and_server_preserves_its_source(api):
    client, state = api
    response = post(client, request_body(execution_budget_ms=8000))
    assert response.status_code == 200
    body = response.json()
    assert body["result"]["status"] == "RECOMMENDED"
    result = body["result"]["candidates"][0]
    assert result["design_item_version"] == candidate()["design_item_version"]
    assert result["sources"] == [candidate()["design_item_version"]["source"]]
    assert result["reason"] == "로그인 API 계약입니다."
    assert body["usage"][0]["request_key"] == "globally-unique-call-1"
    assert body["usage"][0]["feature"] == "DESIGN_RECOMMENDATION"
    assert state["calls"][0].context.task_id == "501"
    assert state["calls"][0].timeout_seconds == 8


def test_empty_search_and_truncated_empty_are_distinct_without_model(api):
    client, state = api
    empty = post(client, request_body(candidates=[], search_observation="NO_DOCUMENTS"))
    incomplete = post(client, request_body(candidates=[], search_truncated=True))
    assert empty.json()["result"]["status"] == "EMPTY"
    assert incomplete.json()["result"]["status"] == "INSUFFICIENT"
    assert incomplete.json()["result"]["follow_up"]
    assert not state["calls"]


def test_unmapped_document_is_not_treated_as_zero_result_success(api):
    client, state = api
    response = post(client, request_body(candidates=[], mapping_complete=False))
    assert response.status_code == 200
    assert response.json()["result"]["status"] == "INSUFFICIENT"
    assert response.json()["result"]["follow_up"]
    assert not state["calls"]


@pytest.mark.parametrize(
    "status, http_status, error",
    [("NOT_READY", 422, "invalid_ai_input"), ("FAILED", 422, "invalid_ai_input")],
)
def test_search_errors_are_not_empty_success(api, status, http_status, error):
    client, state = api
    response = post(client, request_body(search_status=status, candidates=[]))
    assert response.status_code == http_status
    assert response.json()["error"]["code"] == error
    assert not state["calls"]


def test_no_document_source_is_recommended_and_truncation_never_claims_complete(api):
    client, state = api
    item = candidate()
    item["design_item_version"].pop("source")
    item["design_source_verification"] = "UNVERIFIED"
    missing = post(client, request_body(candidates=[item]))
    assert missing.json()["result"]["status"] == "RECOMMENDED"
    assert missing.json()["result"]["candidates"][0]["sources"] == []
    assert missing.json()["result"]["candidates"][0]["design_source"] is None
    assert missing.json()["result"]["candidates"][0]["design_source_status"] == "ABSENT"
    truncated = post(client, request_body(search_truncated=True))
    assert truncated.json()["result"]["status"] == "INSUFFICIENT"
    assert truncated.json()["result"]["omissions"]["unexamined_count"] is None
    assert len(state["calls"]) == 2


@pytest.mark.parametrize(
    "output",
    [
        {"recommendations": [{"design_item_version_id": "999", "reason": "확인"}]},
        {"recommendations": [{"design_item_version_id": "311", "reason": "확인"}] * 2},
        {
            "recommendations": [
                {"design_item_version_id": "311", "reason": "확인", "sources": []}
            ]
        },
        {"recommendations": [{"design_item_version_id": "311", "reason": ""}]},
    ],
)
def test_invented_or_invalid_model_output_is_rejected_and_usage_preserved(api, output):
    client, state = api
    state["response"] = output
    response = post(client, request_body())
    assert response.status_code == 502
    assert response.json()["usage"][0]["failed"] is True
    assert response.json()["usage"][0]["request_key"] == "globally-unique-call-1"
    assert "result" not in response.json()


def test_timeout_reports_failure_and_usage(api):
    client, state = api
    state["error"] = ProviderError("model_timeout")
    response = post(client, request_body())
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "model_timeout"
    assert response.json()["usage"][0]["failed"]


def test_input_and_auth_rejected_before_provider(api):
    client, state = api
    duplicate = post(client, request_body(candidates=[candidate(), candidate()]))
    assert duplicate.status_code == 422
    assert duplicate.json()["error"]["code"] == "invalid_ai_input"
    unapproved = candidate()
    unapproved["design_item_version"]["status"] = "DRAFT"
    assert post(client, request_body(candidates=[unapproved])).status_code == 400
    assert post(client, request_body(execution_budget_ms=0)).status_code == 400
    forbidden = client.post("/v1/design-reference-recommendations", json=request_body())
    assert forbidden.status_code == 401
    assert not state["calls"]


def test_rollout_guard_blocks_model_until_explicitly_enabled(api, monkeypatch):
    client, state = api
    monkeypatch.setitem(
        app.dependency_overrides,
        get_settings,
        lambda: Settings(_env_file=None, ai_internal_token="internal"),
    )
    response = post(client, request_body())
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "recommendation_not_enabled"
    assert not state["calls"]


@pytest.mark.parametrize("location", ["", "   ", None, "__MISSING__"])
def test_missing_source_location_is_retained_as_unverified(api, location):
    client, state = api
    item = candidate()
    source = item["design_item_version"]["source"]
    if location == "__MISSING__":
        source.pop("location")
    else:
        source["location"] = location
    item["design_source_verification"] = "UNVERIFIED"

    response = post(client, request_body(candidates=[item]))

    assert response.status_code == 200
    assert response.json()["result"]["status"] == "RECOMMENDED"
    returned = response.json()["result"]["candidates"][0]
    assert returned["sources"] == []
    assert returned["design_source"]["verification"] == "UNVERIFIED"
    assert len(state["calls"]) == 1


def test_be_design_hash_is_preserved_with_unverified_source(api):
    client, state = api
    usable = candidate()
    usable["design_item_version"]["content_hash"] = "sha256:" + "a" * 64
    incomplete = candidate()
    incomplete["design_item_version"]["id"] = "312"
    incomplete["design_item_version"]["design_item_id"] = "302"
    incomplete["design_item_version"]["source"]["location"] = ""
    incomplete["design_source_verification"] = "UNVERIFIED"
    incomplete["design_item_version"]["content_hash"] = "sha256:" + "b" * 64

    response = post(client, request_body(candidates=[usable, incomplete]))

    assert response.status_code == 200
    result = response.json()["result"]
    assert result["status"] == "RECOMMENDED"
    assert len(result["candidates"]) == 1
    assert (
        result["candidates"][0]["design_item_version"] == usable["design_item_version"]
    )
    assert result["candidates"][0]["sources"] == [
        usable["design_item_version"]["source"]
    ]
    model_payload = json.loads(state["calls"][0].messages[1].content)
    assert [item["id"] for item in model_payload["candidates"]] == ["311", "312"]
    assert all("source" not in item for item in model_payload["candidates"])


def test_model_cannot_select_an_oversized_omitted_candidate(api):
    client, state = api
    incomplete = candidate()
    incomplete["design_item_version"]["id"] = "312"
    incomplete["design_item_version"]["design_item_id"] = "302"
    incomplete["design_item_version"]["content"] = {"description": "x" * 17000}
    state["response"] = {
        "recommendations": [{"design_item_version_id": "312", "reason": "확인"}]
    }

    response = post(client, request_body(candidates=[candidate(), incomplete]))

    assert response.status_code == 502
    assert response.json()["usage"][0]["failed"] is True
    assert "result" not in response.json()


def test_unsupported_candidate_fields_still_fail_before_model(api):
    client, state = api
    item = candidate()
    item["design_item_version"]["unexpected"] = "not part of the contract"

    response = post(client, request_body(candidates=[item]))

    assert response.status_code == 400
    assert not state["calls"]


def test_saved_source_and_search_evidence_stay_separate_and_unverified(api):
    client, state = api
    item = candidate()
    item["design_source_verification"] = "UNVERIFIED"
    item["design_item_version"]["source"]["location"] = "  저장 위치  "
    item["search_evidence"] = [
        {
            "source": {
                "document_id": "201",
                "document_version_id": "212",
                "version_no": 2,
                "location": "검색된 5행",
            },
            "verification": "VERIFIED",
            "association": "EXPLICIT_LINK",
        }
    ]
    response = post(client, request_body(candidates=[item]))
    assert response.status_code == 200
    result = response.json()["result"]["candidates"][0]
    assert result["design_source"]["source"]["location"] == "  저장 위치  "
    assert result["design_source"]["verification"] == "UNVERIFIED"
    assert result["sources"] == []
    assert result["search_evidence"] == item["search_evidence"]
    assert (
        "source"
        not in json.loads(state["calls"][0].messages[1].content)["candidates"][0]
    )


def test_unlinked_document_and_unexamined_scope_are_not_normal_empty(api):
    client, _ = api
    unlinked = post(client, request_body(candidates=[], unlinked_documents=True))
    assert unlinked.json()["result"]["status"] == "INSUFFICIENT"
    assert "UNLINKED_DOCUMENTS" in unlinked.json()["result"]["incomplete_reasons"]
    unknown = post(client, request_body(candidates=[], search_observation="UNKNOWN"))
    assert unknown.json()["result"]["status"] == "INSUFFICIENT"
    partial = post(client, request_body(search_truncated=True))
    omission = partial.json()["result"]["omissions"]
    assert partial.json()["result"]["status"] == "INSUFFICIENT"
    assert omission["unexamined_scope"] is True
    assert omission["unexamined_count"] is None
    assert omission["identified_candidates"] == []


def test_oversized_candidate_is_omitted_without_cutting_and_other_candidate_is_returned(
    api,
):
    client, state = api
    oversized = candidate()
    oversized["design_item_version"]["id"] = "312"
    oversized["design_item_version"]["design_item_id"] = "302"
    oversized["design_item_version"]["content"] = {"description": "x" * 17000}
    response = post(client, request_body(candidates=[candidate(), oversized]))
    assert response.status_code == 200
    result = response.json()["result"]
    assert result["status"] == "INSUFFICIENT"
    assert result["omissions"]["identified_candidates"] == [
        {
            "design_item_version_id": "312",
            "key": "API-001",
            "reason": "CONTENT_TOO_LARGE",
        }
    ]
    assert [c["design_item_version"]["id"] for c in result["candidates"]] == ["311"]
    assert [
        c["id"] for c in json.loads(state["calls"][0].messages[1].content)["candidates"]
    ] == ["311"]


def test_candidate_limit_reports_known_omissions(api):
    client, state = api
    items = []
    for index in range(21):
        item = candidate()
        item["design_item_version"]["id"] = str(311 + index)
        item["design_item_version"]["design_item_id"] = str(301 + index)
        item["design_item_version"]["key"] = f"API-{index + 1:03}"
        items.append(item)
    response = post(client, request_body(candidates=items))
    assert response.status_code == 200
    result = response.json()["result"]
    assert result["status"] == "INSUFFICIENT"
    assert result["omissions"]["identified_candidates"] == [
        {"design_item_version_id": "331", "key": "API-021", "reason": "CANDIDATE_LIMIT"}
    ]
    assert len(json.loads(state["calls"][0].messages[1].content)["candidates"]) == 20


def test_all_oversized_candidates_return_known_key_without_model_call(api):
    client, state = api
    oversized = candidate()
    oversized["design_item_version"]["content"] = {"description": "x" * 17000}
    response = post(client, request_body(candidates=[oversized]))
    assert response.status_code == 200
    result = response.json()["result"]
    assert result["status"] == "INSUFFICIENT"
    assert result["omissions"]["identified_candidates"] == [
        {
            "design_item_version_id": "311",
            "key": "API-001",
            "reason": "CONTENT_TOO_LARGE",
        }
    ]
    assert not state["calls"]


def test_be_omissions_are_preserved_and_false_verified_location_rejected(api):
    client, state = api
    omitted = {
        "identified_candidates": [{"design_item_version_id": "777", "reason": "OTHER"}],
        "unexamined_scope": True,
        "unexamined_count": None,
    }
    response = post(client, request_body(omissions=omitted))
    assert response.json()["result"]["omissions"] == omitted
    assert response.json()["result"]["status"] == "INSUFFICIENT"
    invalid = candidate()
    invalid["design_item_version"]["source"]["location"] = "  "
    assert post(client, request_body(candidates=[invalid])).status_code == 422
    assert len(state["calls"]) == 1


@pytest.mark.parametrize("provided_key", ["API-007", None])
def test_be_omitted_candidate_returns_key_only_when_supplied(api, provided_key):
    client, _ = api
    omitted = {
        "identified_candidates": [
            {"design_item_version_id": "777", "key": provided_key, "reason": "OTHER"}
        ],
        "unexamined_scope": True,
        "unexamined_count": None,
    }
    response = post(client, request_body(omissions=omitted))
    assert response.status_code == 200
    result = response.json()["result"]
    assert result["status"] == "INSUFFICIENT"
    identified = result["omissions"]["identified_candidates"]
    assert identified == [
        {"design_item_version_id": "777", "reason": "OTHER"}
        | ({"key": provided_key} if provided_key is not None else {})
    ]
    assert result["omissions"]["unexamined_scope"] is True
    assert result["omissions"]["unexamined_count"] is None


def test_same_document_version_association_must_match_saved_source(api):
    client, state = api
    item = candidate()
    item["search_evidence"] = [
        {
            "source": {
                "document_id": "201",
                "document_version_id": "212",
                "version_no": 2,
                "location": "5행",
            },
            "verification": "UNVERIFIED",
            "association": "SAME_DOCUMENT_VERSION",
        }
    ]
    assert post(client, request_body(candidates=[item])).status_code == 422
    assert not state["calls"]


def test_search_without_document_may_still_recommend_explicit_candidates(api):
    client, state = api
    response = post(client, request_body(search_observation="NO_DOCUMENTS"))
    assert response.status_code == 200
    assert response.json()["result"]["status"] == "RECOMMENDED"
    assert len(state["calls"]) == 1


def test_model_rejecting_supplied_candidates_is_not_empty_search(api):
    client, state = api
    state["response"] = {"recommendations": []}
    response = post(client, request_body(search_observation="NO_DOCUMENTS"))
    assert response.status_code == 200
    assert response.json()["result"]["status"] == "INSUFFICIENT"
    assert "NO_RECOMMENDATIONS" in response.json()["result"]["incomplete_reasons"]
    assert (
        response.json()["result"]["follow_up"]
        == "요청 조건을 보완하거나 변경해 다시 시도해 주세요."
    )


def test_documents_found_without_candidates_or_explanation_is_invalid(api):
    client, state = api
    response = post(client, request_body(candidates=[]))
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "invalid_ai_input"
    assert not state["calls"]


def test_unexamined_count_is_unknown_when_truncated_and_reasons_match(api):
    client, _ = api
    request = request_body(
        search_truncated=True,
        omissions={
            "unexamined_scope": True,
            "unexamined_count": 4,
        },
    )
    result = post(client, request).json()["result"]
    assert result["omissions"]["unexamined_count"] is None
    assert result["omissions"]["unexamined_scope"] is True
    assert "SEARCH_TRUNCATED" in result["incomplete_reasons"]
    assert "UNEXAMINED_SCOPE" in result["incomplete_reasons"]

    # The AI also marks an unseen scope when only search_truncated was set.
    result = post(client, request_body(search_truncated=True)).json()["result"]
    assert result["omissions"]["unexamined_scope"] is True
    assert "UNEXAMINED_SCOPE" in result["incomplete_reasons"]


def test_known_total_unexamined_count_survives_only_without_truncation(api):
    client, _ = api
    response = post(
        client,
        request_body(
            omissions={
                "unexamined_scope": True,
                "unexamined_count": 4,
            }
        ),
    )
    assert response.status_code == 200
    assert response.json()["result"]["omissions"]["unexamined_count"] == 4
    assert "UNEXAMINED_SCOPE" in response.json()["result"]["incomplete_reasons"]
    invalid = post(
        client,
        request_body(
            omissions={
                "unexamined_scope": False,
                "unexamined_count": 4,
            }
        ),
    )
    assert invalid.status_code == 422
    assert invalid.json()["error"]["code"] == "invalid_ai_input"


def test_document_kind_contract_and_semantic_vs_format_error(api):
    client, state = api
    bad_kind = candidate()
    bad_kind["design_item_version"]["kind"] = "UNKNOWN_KIND"
    response = post(client, request_body(candidates=[bad_kind]))
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "validation_failed"
    search_failed_with_candidates = post(client, request_body(search_status="FAILED"))
    assert search_failed_with_candidates.status_code == 422
    assert search_failed_with_candidates.json()["error"]["code"] == "invalid_ai_input"
    assert not state["calls"]


def test_documented_request_and_response_match_implemented_contract(api):
    client, state = api
    doc = (
        Path(__file__).parents[2] / "docs/design/design-reference-recommendation-185.md"
    ).read_text(encoding="utf-8")
    blocks = re.findall(r"```json\s*\n(.*?)\n```", doc, re.DOTALL)
    assert len(blocks) == 2
    request, expected = (json.loads(block) for block in blocks)
    state["response"]["recommendations"][0]["reason"] = expected["result"][
        "candidates"
    ][0]["reason"]
    response = post(client, request)
    assert response.status_code == 200
    assert response.json()["result"] == expected["result"]
