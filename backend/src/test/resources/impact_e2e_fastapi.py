"""Isolated #188 FastAPI server for a real BE-to-AI HTTP integration test.

Default: deterministic fake LLM, no credentials and no external network calls.
Real GMS is optional and enabled only by an explicit BEEZ_* test environment.
The production app, route, request validation and common LLM client are used.
"""

import json
import os
import sys

import uvicorn
from fastapi.responses import PlainTextResponse
from pydantic import SecretStr

import app.main as main_module
from app.core.config import Settings, get_settings
from app.routers.document_chunks import get_chunk_counter
from app.routers.embeddings import get_counter, get_provider
from app.routers.impact import get_impact_client
from app.schemas.retrieval import (
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)
from app.services.llm_client import LLMClient, ProviderError, ProviderResponse
from app.services.retrieval.tokenization import TOKENIZER_ID


def _safe_exception_hook(exception_type, _value, _traceback):
    name = exception_type.__name__
    if name == "KeyError":
        code = "KEY_ERROR"
    elif name == "ValidationError":
        code = "VALIDATION_ERROR"
    elif name in {"ImportError", "ModuleNotFoundError"}:
        code = "IMPORT_ERROR"
    else:
        code = "OTHER"
    print(f"BEEZ_E2E_STARTUP_ERROR_{code}", file=sys.stderr, flush=True)


sys.excepthook = _safe_exception_hook


class CharacterCounter:
    tokenizer_id = TOKENIZER_ID
    max_input_tokens = 8192

    def count(self, text):
        return len(text)


class FakeEmbeddingProvider:
    async def embed(self, texts, profile, purpose, request_key):
        return ProviderEmbeddingBatch(
            profile=profile,
            vectors=tuple(
                EmbeddingVector(key=text.key, embedding=(1.0, 0.0, 0.0))
                for text in texts
            ),
            usage=EmbeddingUsage(
                request_key=request_key,
                model=profile.model,
                latency_ms=1,
                failed=False,
                tokens_known=True,
                input_tokens=3,
                output_tokens=0,
            ),
        )


class FakeImpactProvider:
    async def complete(self, request, *, timeout_seconds):
        body = json.loads(request.messages[-1].content)
        if body.get("reason") == "E2E_TIMEOUT":
            raise ProviderError("model_timeout", input_tokens=3, output_tokens=1)
        first = body["approved_items"][0]
        # Exercise the owner-evaluation transport without pretending to assess a model.
        # The live GMS run does not use this provider.
        reason = body.get("reason", "")
        owner_eval_reasons = {
            "회원 응답 식별자 변경",
            "회원 조회 설명 문구 정리",
            "회원 응답 필드 변경",
            "회원 응답 ID 변경",
        }
        if reason in owner_eval_reasons:
            candidates = []
            if reason != "회원 조회 설명 문구 정리":
                for task in body["open_tasks"]:
                    plan = task.get("current_plan_revision", {}).get("plan", {})
                    if "userId" not in plan.get("title", ""):
                        continue
                    candidates.append(
                        {
                            "task_id": task["id"],
                            "member_id": task["assignee_member_id"],
                            "design_item_id": first["design_item_id"],
                            "reason": "가상 시험: userId 필드를 사용하는 작업입니다.",
                            "sources": [first["source"]] if first.get("source") else [],
                        }
                    )
            return ProviderResponse(
                json.dumps({"candidates": candidates, "evidence_status": "SUFFICIENT"}),
                request.model,
                11,
                7,
            )
        result = {
            "candidates": [
                {
                    "design_item_id": first["design_item_id"],
                    "reason": "Fixture reports a possible design impact.",
                    "sources": [first["source"]] if first.get("source") else [],
                }
            ],
            "evidence_status": "INSUFFICIENT",
        }
        return ProviderResponse(json.dumps(result), request.model, 11, 7)


internal_token = os.environ["BEEZ_IMPACT_E2E_INTERNAL_TOKEN"]
readiness_nonce = os.environ["BEEZ_IMPACT_E2E_READINESS_NONCE"]
real_gms = os.getenv("BEEZ_IMPACT_E2E_REAL_GMS") == "true"
kwargs = {}
if real_gms:
    key = os.getenv("BEEZ_IMPACT_E2E_GMS_KEY", "").strip()
    if not key:
        raise RuntimeError("Real GMS test key missing")
    kwargs.update(
        impact_llm_endpoint="https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions",
        impact_llm_api_key=SecretStr(key),
        impact_llm_model="gpt-6-luna",
        impact_llm_timeout_seconds="15",
        impact_llm_response_format="json_schema",
    )
else:
    kwargs.update(impact_llm_model="test-fixture-model", impact_llm_timeout_seconds="5")

settings = Settings(
    _env_file=None,
    ai_internal_token=SecretStr(internal_token),
    gms_embedding_dimension=3,
    **kwargs,
)
# Tokenizer startup is unrelated to AI-A03; production startup is unchanged.
main_module.EmbeddingTokenCounter = lambda: None
main_module.app.dependency_overrides[get_settings] = lambda: settings
main_module.app.dependency_overrides[get_chunk_counter] = lambda: CharacterCounter()
main_module.app.dependency_overrides[get_counter] = lambda: CharacterCounter()
main_module.app.dependency_overrides[get_provider] = lambda: FakeEmbeddingProvider()
if not real_gms:
    main_module.app.dependency_overrides[get_impact_client] = lambda: LLMClient(
        FakeImpactProvider()
    )


@main_module.app.get("/__e2e_ready", include_in_schema=False)
async def e2e_ready():
    return PlainTextResponse(readiness_nonce)


if __name__ == "__main__":
    uvicorn.run(
        main_module.app,
        host="127.0.0.1",
        port=int(sys.argv[1]),
        log_level="error",
        access_log=False,
        http="httptools",
    )
