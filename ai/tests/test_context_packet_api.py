"""HTTP boundary of #132 packet composition; fake selectors only, no network."""

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers.context_selection import get_context_selector
from app.schemas.context_packet import DevelopmentContextPacket
from app.schemas.retrieval import EmbeddingUsage
from app.services.retrieval.context_selection import (
    DirectMatchTruncation,
    SelectionProposal,
)

EXAMPLES = Path(__file__).resolve().parents[1] / "examples/context-packet-132"
HEADERS = {"Authorization": "Bearer test-internal"}


def usage():
    return EmbeddingUsage(
        request_key="opaque-call",
        model="fake",
        latency_ms=1,
        failed=False,
        tokens_known=True,
        input_tokens=12,
        output_tokens=0,
    )


class Selector:
    def __init__(self, items=("v1",), evidence=("e1",)):
        self.calls = []
        self.items, self.evidence = items, evidence
        self.truncation = DirectMatchTruncation(items=0, evidence=0)

    async def select(self, data):
        self.calls.append(data)
        return SelectionProposal(
            design_item_version_ids=self.items,
            evidence_ids=self.evidence,
            usage=(usage(),),
            direct_match_truncation=self.truncation,
        )


def request_body(*, metadata=True):
    body = {
        "request_key": "development-context-test",
        "project_id": "p1",
        "baseline_id": "b1",
        "request": "비공개 요청 업로드",
        "task_id": "t1",
        "document_version_ids": ["d1"],
        "items": [
            {
                "design_item_id": "i1",
                "design_item_version_id": "v1",
                "project_id": "p1",
                "baseline_id": "b1",
                "status": "APPROVED",
                "text": "비공개 설계 업로드",
            }
        ],
        "evidence": [
            {
                "evidence_id": "e1",
                "document_version_id": "d1",
                "project_id": "p1",
                "baseline_id": "b1",
                "text": "비공개 원문",
            }
        ],
        "relations": [],
        "task_design_refs": [],
        "search_status": "COMPLETE",
    }
    if metadata:
        body["metadata"] = {
            "design_items": [
                {
                    "design_item_version_id": "v1",
                    "design_item_id": "i1",
                    "key": "API-01",
                    "kind": "API_CONTRACT",
                    "version_no": 1,
                    "source": {
                        "document_id": "doc1",
                        "document_version_id": "d1",
                        "version_no": 1,
                        "location": "1. 업로드 / UP-01",
                    },
                    "description": "비공개 설명",
                }
            ],
            "evidence": [
                {
                    "evidence_id": "e1",
                    "document_id": "doc1",
                    "document_version_id": "d1",
                    "version_no": 1,
                    "location": "1. 업로드 / UP-01",
                }
            ],
            "task": {"task_id": "t1", "plan_revision_id": "pr1", "status": "READY"},
        }
    return body


@pytest.fixture
def api():
    settings = Settings(
        _env_file=None,
        ai_internal_token="test-internal",
        gms_key="fake",
        context_selection_enabled=True,
        context_selection_min_similarity="0.6",
        context_selection_max_items="5",
        context_selection_max_evidence="5",
        context_selection_total_timeout_seconds="10",
    )
    selector = Selector()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_context_selector] = lambda: lambda _: selector
    with TestClient(app) as client:
        yield client, selector
    app.dependency_overrides.clear()


def test_metadata_adds_packet_content_without_raw_text(api):
    client, selector = api
    response = client.post(
        "/v1/development-context-selections", json=request_body(), headers=HEADERS
    )
    assert response.status_code == 200
    body = response.json()
    assert body["result"]["design_items"] == [
        {"design_item_version_id": "v1", "reasons": ["MATCHED"]}
    ]
    packet = body["packet"]
    assert packet["design_item_version_ids"] == ["v1"]
    assert packet["design_items"][0]["key"] == "API-01"
    assert packet["related_tasks"] == [
        {"task_id": "t1", "plan_revision_id": "pr1", "status": "READY"}
    ]
    assert packet["sources"] == [
        {
            "document_id": "doc1",
            "document_version_id": "d1",
            "version_no": 1,
            "location": "1. 업로드 / UP-01",
        }
    ]
    assert packet["constraints"] == [] and packet["unresolved"] == []
    # The request itself is packet content; candidate texts and descriptions are not.
    assert "비공개 설계" not in response.text
    assert "비공개 원문" not in response.text
    assert "비공개 설명" not in response.text
    assert len(selector.calls) == 1


def test_without_metadata_the_response_stays_selection_only(api):
    client, _ = api
    response = client.post(
        "/v1/development-context-selections",
        json=request_body(metadata=False),
        headers=HEADERS,
    )
    assert response.status_code == 200 and "packet" not in response.json()


@pytest.mark.parametrize("defect", ["unknown_item", "evidence_version", "task"])
def test_mismatched_metadata_is_rejected_before_selection(api, defect):
    client, selector = api
    body = request_body()
    if defect == "unknown_item":
        body["metadata"]["design_items"][0]["design_item_version_id"] = "v9"
    elif defect == "evidence_version":
        body["metadata"]["evidence"][0]["document_version_id"] = "d9"
    else:
        body["metadata"]["task"]["task_id"] = "other"
    response = client.post(
        "/v1/development-context-selections", json=body, headers=HEADERS
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "validation_failed"
    assert not selector.calls and "v9" not in response.text


@pytest.mark.parametrize("has_plan_refs", [False, True])
@pytest.mark.parametrize("task_field", ["omitted", "null"])
def test_task_without_metadata_returns_no_related_tasks(api, has_plan_refs, task_field):
    client, _ = api
    body = request_body()
    if task_field == "omitted":
        del body["metadata"]["task"]
    else:
        body["metadata"]["task"] = None
    if has_plan_refs:
        body["task_design_refs"] = [
            {"design_item_id": "i1", "design_item_version_id": "v1"}
        ]
    response = client.post(
        "/v1/development-context-selections", json=body, headers=HEADERS
    )
    assert response.status_code == 200
    packet = response.json()["packet"]
    assert packet["task_id"] == "t1"
    assert packet["related_tasks"] == []
    assert (
        DevelopmentContextPacket.model_validate_json(
            json.dumps(packet, ensure_ascii=False)
        ).to_save_packet_args()["related_tasks"]
        == []
    )


@pytest.mark.parametrize("status", ["DRAFT", "UNKNOWN"])
def test_unsupported_related_task_status_is_rejected_before_selection(api, status):
    client, selector = api
    body = request_body()
    body["metadata"]["task"]["status"] = status
    response = client.post(
        "/v1/development-context-selections", json=body, headers=HEADERS
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "validation_failed"
    assert not selector.calls


def test_examples_round_trip_through_the_route_and_be_arguments(api):
    client, selector = api
    selector.items, selector.evidence = ("311",), ("chunk-211-7",)
    # The example selector reported one evidence candidate cut by the limit.
    selector.truncation = DirectMatchTruncation(items=0, evidence=1)
    request = json.loads((EXAMPLES / "request.json").read_text(encoding="utf-8"))
    expected = json.loads((EXAMPLES / "packet.json").read_text(encoding="utf-8"))
    arguments = json.loads(
        (EXAMPLES / "save-packet-args.json").read_text(encoding="utf-8")
    )
    response = client.post(
        "/v1/development-context-selections", json=request, headers=HEADERS
    )
    assert response.status_code == 200
    packet = response.json()["packet"]
    # The example was produced with a review candidate; the route has none.
    assert {**packet, "unresolved": [], "unresolved_details": []} == {
        **expected,
        "unresolved": [],
        "unresolved_details": [],
    }
    assert (
        DevelopmentContextPacket.model_validate_json(
            json.dumps(expected, ensure_ascii=False)
        ).to_save_packet_args()
        == arguments
    )
    errors = json.loads((EXAMPLES / "errors.json").read_text(encoding="utf-8"))
    assert {entry["body"]["error"]["code"] for entry in errors} >= {
        "context_not_ready",
        "search_failed",
        "validation_failed",
        "packet_too_large",
    }


def test_openapi_inlines_metadata_schema(api):
    client, _ = api
    schema = client.get("/openapi.json").json()["paths"][
        "/v1/development-context-selections"
    ]["post"]["requestBody"]["content"]["application/json"]["schema"]
    metadata = schema["properties"]["metadata"]
    assert "#/$defs/" not in json.dumps(metadata)
    assert "design_items" in json.dumps(metadata)
