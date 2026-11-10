"""Exercise the BE-proposed chat modes through the authenticated HTTP boundary."""

import copy
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


class FakeProvider:
    def __init__(self):
        self.requests = []
        self.output = {}

    async def complete(self, request, *, timeout_seconds):
        self.requests.append(request)
        return ProviderResponse(
            json.dumps(self.output, ensure_ascii=False), request.model, 12, 7
        )


@pytest.fixture(scope="module")
def shared_api():
    app = FastAPI()
    app.include_router(router)
    provider = FakeProvider()
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    app.dependency_overrides[get_review_client] = lambda: LLMClient(provider)
    with TestClient(app) as client:
        yield client, provider


@pytest.fixture
def api(shared_api):
    shared_api[1].requests.clear()
    shared_api[1].output = {}
    return shared_api


def input_for(mode):
    payload = json.loads(FIXTURE.read_text(encoding="utf-8"))["input"]
    payload["mode"] = mode
    if mode == "DISCUSS":
        payload["suggestion"] = {
            "id": "5",
            "type": "PATCH",
            "step": "BODY",
            "explanation": "내용을 보완해요",
            "original_text": "로그인한다.",
            "replacement_text": "사용자가 로그인한다.",
            "decision": "PENDING",
        }
        payload["messages"] = [{"author_kind": "HUMAN", "text": "다르게 써줘"}]
    else:
        payload["request_text"] = "로그인 주체를 명확히 해줘"
    return payload


def post(api, payload, token="test-internal"):
    return api[0].post(
        "/v1/document-reviews",
        json=payload,
        headers={"Authorization": f"Bearer {token}"},
    )


@pytest.mark.parametrize("mode", ["DISCUSS", "REQUEST"])
def test_mode_round_trip_preserves_be_identity_and_actual_usage(api, mode):
    payload = input_for(mode)
    api[1].output = (
        {"reply": {"text": "이렇게 바꿔볼게요.", "proposed_text": "수정 문장"}}
        if mode == "DISCUSS"
        else {
            "suggestions": [
                {
                    "type": "PATCH",
                    "step": "BODY",
                    "explanation": "주체를 명확히 합니다.",
                    "sources": [],
                    "original_text": "로그인한다.",
                    "replacement_text": "사용자가 로그인한다.",
                }
            ]
        }
    )
    response = post(api, payload)
    assert response.status_code == 200, response.text
    envelope = response.json()
    assert envelope["origin"] == "MODEL"
    assert envelope["usage"][0]["failed"] is False
    assert envelope["usage"][0]["feature"] == "BEE_DRAFT"
    assert len(api[1].requests) == 1
    for key in (
        "document_id",
        "revision",
        "content_hash",
        "reference_hash",
        "request_key",
    ):
        assert envelope["result"][key] == payload[key]
    if mode == "DISCUSS":
        assert envelope["result"]["reply"]["proposed_text"] == "수정 문장"
        assert not envelope["result"].get("suggestions")
    else:
        assert envelope["result"]["suggestions"][0]["original_text"] == "로그인한다."


@pytest.mark.parametrize("mode", ["DISCUSS", "REQUEST"])
def test_invalid_identity_or_unauthorized_request_never_reaches_provider(api, mode):
    payload = input_for(mode)
    altered = copy.deepcopy(payload)
    altered["content"] += "tampered"
    assert post(api, altered).status_code in (400, 422)
    assert post(api, payload, "wrong").status_code == 401
    assert api[1].requests == []


def test_request_rejects_unverifiable_model_patch_instead_of_success(api):
    payload = input_for("REQUEST")
    api[1].output = {
        "suggestions": [
            {
                "type": "PATCH",
                "step": "BODY",
                "explanation": "수정",
                "sources": [],
                "original_text": "없는 원문",
                "replacement_text": "변경",
            }
        ]
    }
    response = post(api, payload)
    assert response.status_code == 502
    assert response.json()["usage"][0]["failed"] is True


def test_discuss_rejects_deletion_of_entire_document(api):
    payload = input_for("DISCUSS")
    payload["suggestion"]["original_text"] = payload["content"]
    api[1].output = {"reply": {"text": "삭제해요.", "proposed_text": ""}}
    response = post(api, payload)
    assert response.status_code == 502
    assert response.json()["usage"][0]["failed"] is True
