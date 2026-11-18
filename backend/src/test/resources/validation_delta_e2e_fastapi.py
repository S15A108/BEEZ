"""Local AI-A02 fixture: real HTTP route and LLM client, deterministic model.

The process rejects requests unless the #77 completion report reached the model.
No external provider or credentials are used.
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
        delta = data.get("completion_delta")
        valid_reports = (
            [{"kind": "ADDED_FIELD", "text": "fixture completion differs"}],
            [{"kind": "ADDED_FIELD", "text": "fixture completion matches"}],
        )
        checks = data.get("checks", [])
        if delta not in valid_reports or not any(
            check.get("key") == "completion-delta"
            and check.get("kind") == "PLAN_COMPLETENESS"
            for check in checks
        ):
            raise ValueError("completion delta did not reach the model")
        findings = []
        if delta[0]["text"] == "fixture completion differs":
            findings.append(
                {
                    "check_key": "completion-delta",
                    "kind": "MISMATCH",
                    "severity": "BLOCKING",
                    "message": "fixture completion differs",
                    "sources": [],
                    "evidence_ids": [],
                    "suggested_action": "Review the completion report.",
                }
            )
        output = {
            "analysis_status": "COMPLETE",
            "checked_keys": [check["key"] for check in checks],
            "unassessed": [],
            "findings": findings,
        }
        return ProviderResponse(json.dumps(output), request.model, 11, 7)


@asynccontextmanager
async def fake_client(_settings):
    yield LLMClient(FakeProvider())


settings = Settings(_env_file=None, ai_internal_token=os.environ["BEEZ_DELTA_E2E_TOKEN"])
validation.get_validation_client = fake_client
app = FastAPI()
app.dependency_overrides[get_settings] = lambda: settings
app.include_router(validation.router)


@app.get("/__ready", include_in_schema=False)
async def ready():
    return PlainTextResponse(os.environ["BEEZ_DELTA_E2E_NONCE"])


if __name__ == "__main__":
    uvicorn.run(
        app,
        host="127.0.0.1",
        port=int(sys.argv[1]),
        access_log=False,
        log_level="error",
    )
