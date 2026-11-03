"""Run four synthetic cases through the real common LLM and validation service.

This invokes the FastAPI validation route with a test-injected provider. It does
not exercise the BE, database, or configured production GMS provider. It makes
four billable external calls only with --run-live.
"""

import argparse
import asyncio
import json
import os
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from pathlib import Path
from time import time_ns
from types import SimpleNamespace
from urllib.parse import urlsplit

import httpx
from dotenv import dotenv_values
from pydantic import SecretStr

from app.core.config import get_settings
from app.main import app
from app.routers import validation as validation_router
from app.schemas.validation import ValidateRequest
from app.services.llm_client import LLMClient, OpenAICompatibleProvider
from app.services.validation import validate_input
from evaluations.validation.fixtures import fixtures

AI_ROOT = Path(__file__).resolve().parents[2]
ALLOWED_HOSTS = {"api.openai.com", "gms.ssafy.io"}


class CaptureProvider:
    def __init__(self, provider):
        self.provider = provider
        self.content = []

    async def complete(self, request, *, timeout_seconds):
        response = await self.provider.complete(
            request, timeout_seconds=timeout_seconds
        )
        self.content.append(response.content)
        return response


def assess(case, response):
    """Check the stated scenario, not only whether output happens to parse."""
    if "result" not in response:
        return False, "model call or output validation failed"
    result = response["result"]
    findings = result["findings"]
    if case == "normal":
        ok = result["analysis_status"] == "COMPLETE" and not any(
            f["severity"] == "BLOCKING" for f in findings
        )
        return ok, "COMPLETE without blocking findings"
    if case == "mismatch":
        ok = any(
            f["kind"] == "MISMATCH"
            and f["severity"] == "BLOCKING"
            and f.get("design_item_version_id") == "311"
            and "userid" in f["message"].lower()
            and "memberid" in f["message"].lower()
            for f in findings
        )
        return ok, "BLOCKING mismatch with version and both field names"
    if case == "policy_unresolved":
        ok = result["analysis_status"] == "PARTIAL" and any(
            item["reason"] == "EVIDENCE_INSUFFICIENT" for item in result["unassessed"]
        )
        return ok, "PARTIAL and EVIDENCE_INSUFFICIENT"
    if case == "document_conflict":
        ok = any(
            f["kind"] in {"CONFLICT", "MISMATCH"}
            and f.get("design_item_version_id") == "311"
            and "e-member-api" in f["evidence_ids"]
            for f in findings
        )
        return ok, "conflict references approved version and document evidence"
    raise ValueError("unknown case")


def configuration():
    # The key is read by name and is never printed or written into results.
    env = {**dotenv_values(AI_ROOT / ".env"), **os.environ}
    endpoint = env.get("VALIDATION_EVAL_ENDPOINT")
    model = env.get("VALIDATION_EVAL_MODEL")
    key_name = env.get("VALIDATION_EVAL_KEY_NAME")
    if not endpoint or not model or key_name not in {"OPENAI_API_KEY", "GMS_KEY"}:
        raise ValueError(
            "Set VALIDATION_EVAL_ENDPOINT, VALIDATION_EVAL_MODEL, and VALIDATION_EVAL_KEY_NAME (OPENAI_API_KEY or GMS_KEY)."
        )
    host = urlsplit(endpoint).hostname
    expected = {"OPENAI_API_KEY": "api.openai.com", "GMS_KEY": "gms.ssafy.io"}[key_name]
    if (
        urlsplit(endpoint).scheme != "https"
        or host != expected
        or host not in ALLOWED_HOSTS
    ):
        raise ValueError(
            "Endpoint host and credential type do not match the supported provider."
        )
    key = env.get(key_name)
    if not key:
        raise ValueError("The selected provider key is missing.")
    return endpoint, model, key


async def run(endpoint, model, key):
    records = []
    provider = OpenAICompatibleProvider(endpoint=endpoint, api_key=key)
    previous_provider = validation_router.get_validation_client
    previous_overrides = app.dependency_overrides.copy()
    test_token = "validation-eval-synthetic-internal-token"
    output_format = os.environ.get("VALIDATION_EVAL_RESPONSE_FORMAT", "json_object")
    if output_format not in {"json_object", "json_schema"}:
        raise ValueError("Unsupported validation evaluation response format.")
    test_settings = SimpleNamespace(
        ai_internal_token=SecretStr(test_token),
        bee_default_model=model,
        validation_response_format=output_format,
    )
    app.dependency_overrides[get_settings] = lambda: test_settings
    try:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://validation-test"
        ) as http:
            run_id = time_ns()
            for index, case in enumerate(fixtures()):
                # Live calls must have distinct validation/usage keys across runs.
                case["request"]["validation_id"] = str(run_id + index)
                case["request"]["request_key"] = f"eval-{run_id}-{index}"
                capture = CaptureProvider(provider)

                @asynccontextmanager
                async def injected_client(settings, provider_capture=capture):
                    yield LLMClient(provider_capture)

                validation_router.get_validation_client = injected_client
                try:
                    http_response = await http.post(
                        "/v1/validate",
                        json=case["request"],
                        headers={"Authorization": f"Bearer {test_token}"},
                    )
                    status = http_response.status_code
                    response = http_response.json()
                except Exception as exc:  # noqa: BLE001 - report all per-case failures safely
                    status = None
                    # Do not include exception messages: a transport exception can
                    # contain an endpoint or credential in provider-specific details.
                    response = {
                        "error": {
                            "code": "evaluation_transport_failure",
                            "type": type(exc).__name__,
                        }
                    }
                passed, criterion = assess(case["name"], response)
                records.append(
                    {
                        "name": case["name"],
                        "expectation": case["expectation"],
                        "criterion": criterion,
                        "passed": passed and status == 200,
                        "http_status": status,
                        "response": response,
                        "raw_model_content": capture.content,
                    }
                )
    finally:
        validation_router.get_validation_client = previous_provider
        app.dependency_overrides.clear()
        app.dependency_overrides.update(previous_overrides)
        await provider.aclose()
    return {
        "created_at": datetime.now(UTC).isoformat(),
        "provider_host": urlsplit(endpoint).hostname,
        "requested_model": model,
        "response_format": output_format,
        "scope": "FastAPI route with test-injected provider; no BE/DB or configured GMS route integration",
        "cases": records,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--run-live",
        action="store_true",
        help="Explicitly perform four billable LLM calls",
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Path for synthetic evaluation results",
    )
    args = parser.parse_args()
    # Check the request contract without a provider or network, even in dry-run mode.
    for case in fixtures():
        validate_input(ValidateRequest.model_validate(case["request"]))
    if not args.run_live:
        print("Validated four synthetic input fixtures. No LLM call was made.")
        return
    endpoint, model, key = configuration()
    report = asyncio.run(run(endpoint, model, key))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    for case in report["cases"]:
        print(
            f"{case['name']}: {'PASS' if case['passed'] else 'FAIL'} ({case['criterion']})"
        )
    print(
        "FastAPI test-injected provider results saved; BE/DB and configured GMS route were not tested."
    )


if __name__ == "__main__":
    main()
