"""Test-only AI-A02 server for Java AiClient's #77 DELTA producer fixture.

Runs the actual validation route and common LLM client. No external provider or
credentials are used. The fake model rejects the request unless the completion
report and its check reached the model input intact.
"""

import json
import os
import sys
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from fastapi.responses import PlainTextResponse

from app.core.config import Settings, get_settings
from app.routers import validation
from app.services.llm_client import LLMClient, ProviderResponse


class FakeProvider:
    async def complete(self, request, *, timeout_seconds):
        data = json.loads(request.messages[-1].content)
        if (
            data.get("completion_delta")
            != [{"kind": "CHANGED", "text": "fixture completion differs from plan"}]
            or not any(
                check["key"] == "completion-delta"
                and check["kind"] == "PLAN_COMPLETENESS"
                and check["design_item_version_ids"] == ["311"]
                for check in data.get("checks", [])
            )
            or not data.get("evidence")
            or "completion-delta" not in request.messages[0].content
        ):
            raise ValueError("fixture delta did not reach the model")
        output = {
            "analysis_status": "COMPLETE",
            "checked_keys": ["completion-delta"],
            "unassessed": [],
            "findings": [
                {
                    "check_key": "completion-delta",
                    "kind": "MISMATCH",
                    "severity": "BLOCKING",
                    "message": "fixture completion differs from plan",
                    "design_item_id": "301",
                    "design_item_version_id": "311",
                    "sources": [data["evidence"][0]["source"]],
                    "evidence_ids": [data["evidence"][0]["evidence_id"]],
                    "suggested_action": "Review the completion report.",
                }
            ],
        }
        return ProviderResponse(json.dumps(output), request.model, 11, 7)


@asynccontextmanager
async def fake_client(_settings):
    yield LLMClient(FakeProvider())


token = os.environ["BEEZ_VALIDATION_CONTRACT_TOKEN"]
nonce = os.environ["BEEZ_VALIDATION_CONTRACT_NONCE"]
settings = Settings(_env_file=None, ai_internal_token=token)
validation.get_validation_client = fake_client
app = FastAPI()
app.dependency_overrides[get_settings] = lambda: settings
app.include_router(validation.router)


@app.get("/__ready", include_in_schema=False)
async def ready():
    return PlainTextResponse(nonce)


if __name__ == "__main__":
    uvicorn.run(
        app,
        host="127.0.0.1",
        port=int(sys.argv[1]),
        access_log=False,
        log_level="error",
    )
