import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.routers.document_review import get_review_client
from app.routers.meeting_revision import router
from app.schemas.documents import SourceInput
from app.services.document_parsing.normalization import text_digest
from app.services.document_parsing.pipeline import parse_source
from app.services.llm_client import LLMClient, ProviderResponse


class Provider:
    def __init__(self, block_id):
        self.calls = 0
        self.block_id = block_id

    async def complete(self, request, *, timeout_seconds):
        self.calls += 1
        output = {
            "changes": [
                {
                    "block_id": self.block_id,
                    "replacement": "팀장만 삭제합니다.\n",
                    "reason": "결정 반영",
                    "evidence": ["팀장만 삭제"],
                }
            ],
            "unresolved": [],
        }
        return ProviderResponse(
            json.dumps(output, ensure_ascii=False), request.model, 10, 5
        )


def test_internal_endpoint_uses_shared_modules_and_rejects_unconfirmed_links():
    text = "모든 사용자가 삭제합니다.\n"
    digest = text_digest(text)
    source = SourceInput(
        project_id="1",
        document_version_id="21",
        kind="PLAN",
        media_type="text/plain",
        content=text,
        content_hash=digest,
        text_hash=digest,
        normalization_version="utf8-lf-v1",
        parser_profile="plain-v1",
    )
    block_id = parse_source(source).blocks[0].block_id
    body = {
        "project_id": "1",
        "meeting_id": "9",
        "document_id": "2",
        "document_version_id": "21",
        "source": source.model_dump(),
        "entries": [{"id": "91", "meeting_id": "9"}],
        "decisions": [
            {
                "id": "summary:1:decision:0",
                "text": "팀장만 삭제",
                "evidence_entry_ids": ["91"],
            }
        ],
        "selected_block_ids": [block_id],
        "instruction": "삭제 권한 반영",
        "request_key": "test",
    }
    provider = Provider(block_id)
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    app.dependency_overrides[get_review_client] = lambda: LLMClient(provider)
    with TestClient(app) as client:
        assert client.post("/v1/meeting-revisions", json=body).status_code == 401
        headers = {"Authorization": "Bearer test-internal"}
        invalid = {
            **body,
            "decisions": [{**body["decisions"][0], "evidence_entry_ids": ["999"]}],
        }
        assert (
            client.post(
                "/v1/meeting-revisions", json=invalid, headers=headers
            ).status_code
            == 422
        )
        assert provider.calls == 0
        response = client.post("/v1/meeting-revisions", json=body, headers=headers)
        assert response.status_code == 200, response.text
        assert (
            response.json()["draft"]["result"]["after_content"]
            == "팀장만 삭제합니다.\n"
        )
        assert response.json()["meeting"]["decisions"] == body["decisions"]
        assert provider.calls == 1
