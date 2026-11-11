"""Internal task breakdown transport and approved-snapshot guarantees."""

import json

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers.document_review import get_review_client
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse

PATH = "/v1/bee/task-breakdown"
HEADERS = {"Authorization": "Bearer internal"}


def snapshot():
    return {
        "project_id": "3",
        "request_key": "breakdown-test",
        "documents": [{"title": "가입 기획서", "content": "이메일로 회원가입한다."}],
        "design_items": [
            {
                "design_item_id": "7",
                "design_item_version_id": "11",
                "title": "회원가입 API",
                "kind": "API",
                "content": '{"endpoint":"POST /signup"}',
            }
        ],
        "assignment_areas": ["Backend", "Frontend"],
    }


def ticket(key, assignment_area, dependencies):
    return {
        "key": key,
        "title": f"가입 구현 {key}",
        "goal": "이메일 가입을 구현한다.",
        "included_scope": ["이메일 가입"],
        "excluded_scope": [],
        "approach": "승인된 API 계약을 따른다.",
        "completion_criteria": ["유효한 이메일로 가입할 수 있다."],
        "design_refs": [
            {
                "design_item_id": "7",
                "design_item_version_id": "11",
                "relation": "IMPLEMENTS",
            }
        ],
        "dependency_keys": dependencies,
        "assignment_area": assignment_area,
    }


class Provider:
    def __init__(self):
        self.calls = []
        self.error = None
        self.output = {
            "tasks": [
                ticket("signup-api", "Backend", []),
                ticket("signup-form", "Frontend", ["signup-api"]),
            ]
        }

    async def complete(self, request, *, timeout_seconds):
        self.calls.append((request, timeout_seconds))
        if self.error:
            raise ProviderError(self.error)
        return ProviderResponse(json.dumps(self.output), request.model, 11, 17)


@pytest.fixture
def transport():
    provider = Provider()
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="internal"
    )
    app.dependency_overrides[get_review_client] = lambda: LLMClient(provider)
    try:
        yield TestClient(app), provider
    finally:
        app.dependency_overrides.pop(get_settings)
        app.dependency_overrides.pop(get_review_client)


def test_route_generates_ticket_and_dependency_contract(transport):
    api, provider = transport
    assert api.post(PATH, json=snapshot()).status_code == 401
    assert not provider.calls

    response = api.post(PATH, headers=HEADERS, json=snapshot())
    assert response.status_code == 200
    payload = response.json()
    assert payload["result"] == provider.output
    assert payload["origin"] == "MODEL"
    assert payload["usage"][0]["feature"] == "BEE_DRAFT"
    assert len(provider.calls) == 1
    request, provider_timeout = provider.calls[0]
    assert request.timeout_seconds > 0
    assert 0 < provider_timeout <= request.timeout_seconds
    assert request.model_options["max_completion_tokens"] > 0
    assert request.context.project_id == "3"
    assert [message.role for message in request.messages] == ["system", "user"]
    assert (
        json.loads(request.messages[1].content)["documents"] == snapshot()["documents"]
    )


@pytest.mark.parametrize(
    "invalid",
    ["foreign-ref", "foreign-area", "cycle", "duplicate-key", "null-character"],
)
def test_route_rejects_unusable_model_output(transport, invalid):
    api, provider = transport
    first, second = provider.output["tasks"]
    if invalid == "foreign-ref":
        first["design_refs"][0]["design_item_version_id"] = "99"
    elif invalid == "foreign-area":
        first["assignment_area"] = "unknown-secret-area"
    elif invalid == "cycle":
        first["dependency_keys"] = ["signup-form"]
    elif invalid == "duplicate-key":
        second["key"] = first["key"]
    else:
        first["included_scope"] = ["invalid\0text"]

    response = api.post(PATH, headers=HEADERS, json=snapshot())
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "invalid_output"
    assert response.json()["usage"][0]["failed"] is True
    assert "unknown-secret-area" not in response.text
    assert "result" not in response.json()


def test_route_keeps_valid_tickets_when_other_dependency_chains_are_unusable(transport):
    api, provider = transport
    bad_reference = ticket("bad-reference", "Backend", [])
    bad_reference["design_refs"][0]["design_item_version_id"] = "99"
    duplicate = ticket("ambiguous", "Backend", [])
    duplicate_with_invalid_shape = {"key": "ambiguous", "title": None}
    api_ticket = ticket("signup-api", "Backend", [])
    form_ticket = ticket("signup-form", "Frontend", ["signup-api"])
    provider.output["tasks"] = [
        ticket("indirect-dependent", "Frontend", ["direct-dependent"]),
        ticket("direct-dependent", "Frontend", ["bad-reference"]),
        bad_reference,
        ticket("cycle-a", "Backend", ["cycle-b"]),
        ticket("cycle-b", "Backend", ["cycle-a"]),
        ticket("cycle-dependent", "Frontend", ["cycle-a"]),
        duplicate,
        duplicate_with_invalid_shape,
        ticket("ambiguous-dependent", "Frontend", ["ambiguous"]),
        ticket("unknown-dependent", "Frontend", ["missing"]),
        form_ticket,
        api_ticket,
    ]

    response = api.post(PATH, headers=HEADERS, json=snapshot())

    assert response.status_code == 200
    payload = response.json()
    assert payload["result"]["tasks"] == [form_ticket, api_ticket]
    assert payload["usage"][0]["failed"] is False


def test_route_rejects_bad_snapshot_and_preserves_timeout(transport):
    api, provider = transport
    response = api.post(
        PATH,
        headers=HEADERS,
        json={**snapshot(), "assignment_areas": ["Backend", "Backend"]},
    )
    assert response.status_code == 422
    assert not provider.calls

    response = api.post(
        PATH,
        headers={**HEADERS, "Content-Type": "application/json"},
        content=" " * (512 * 1024 + 1),
    )
    assert response.status_code == 413
    assert not provider.calls

    provider.error = "model_timeout"
    response = api.post(PATH, headers=HEADERS, json=snapshot())
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "model_timeout"
    assert "result" not in response.json()
