"""Evaluate synthetic inputs through FastAPI and the configured real GMS model.

No project data or credentials are written to the report. --run-live performs
one billable call per selected case. Outputs contain only synthetic examples.
"""

import argparse
import asyncio
import contextvars
import copy
import json
import os
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from pathlib import Path
from time import time_ns
from types import SimpleNamespace

import httpx
from pydantic import SecretStr

from app.core.config import get_settings
from app.main import app
from app.routers import validation as route
from app.schemas.validation import ValidateRequest
from app.services.llm_client import LLMClient, OpenAICompatibleProvider
from app.services.validation import validate_input
from evaluations.policy_gap.fixtures import fixtures

capture_var = contextvars.ContextVar("policy_eval_capture")


class Capture:
    def __init__(self, provider, output):
        self.provider, self.output = provider, output

    async def complete(self, request, *, timeout_seconds):
        response = await self.provider.complete(
            request, timeout_seconds=timeout_seconds
        )
        self.output.append(response.content)
        return response


def score(case, response):
    result = response.get("result")
    if not isinstance(result, dict):
        return False, "no validated model result"
    assessments = result.get("policy_assessments", [])
    states = {a.get("state") for a in assessments}
    findings = result.get("findings", [])
    policy_findings = [f for f in findings if f.get("check_key") == "policy-gap-v1"]
    incomplete = any(
        u.get("check_key") == "policy-gap-v1"
        and u.get("reason") == "EVIDENCE_INSUFFICIENT"
        for u in result.get("unassessed", [])
    )
    checked = "policy-gap-v1" in result.get("checked_keys", [])
    expected = case["expectation"]
    if expected == "INSUFFICIENT":
        ok = incomplete and not any(f.get("kind") == "MISSING_POLICY" for f in findings)
    elif expected == "NO_REQUIREMENTS":
        ok = (
            checked
            and not assessments
            and bool(result.get("no_policy_requirements_reason"))
            and not policy_findings
        )
    elif expected == "SUPPORTED":
        ok = checked and "SUPPORTED" in states and not policy_findings
    elif expected == "EXPLICITLY_UNDECIDED":
        ok = (
            checked
            and expected in states
            and any(
                f.get("kind") == "MISSING_POLICY" and f.get("evidence_ids")
                for f in policy_findings
            )
        )
    elif expected == "CONTRADICTORY":
        ok = (
            checked
            and expected in states
            and any(f.get("kind") == "MISMATCH" for f in policy_findings)
        )
    elif expected == "CONFLICT":
        ok = (
            checked
            and "CONTRADICTORY" in states
            and any(
                f.get("kind") == "CONFLICT" and f.get("conflicting_design_refs")
                for f in policy_findings
            )
        )
    else:
        raise ValueError("Unknown synthetic expectation")
    return (
        ok,
        f"expected={expected}; states={sorted(states)}; policy_unassessed={incomplete}",
    )


async def run(key, model, selected, response_format):
    provider = OpenAICompatibleProvider(endpoint=route.GMS_CHAT_URL, api_key=key)
    old_client, overrides = route.get_validation_client, app.dependency_overrides.copy()
    token = "synthetic-policy-evaluation-token"
    settings = SimpleNamespace(
        ai_internal_token=SecretStr(token),
        bee_default_model=model,
        validation_response_format=response_format,
    )
    app.dependency_overrides[get_settings] = lambda: settings

    @asynccontextmanager
    async def injected(_settings):
        yield LLMClient(Capture(provider, capture_var.get()))

    route.get_validation_client = injected
    limit = asyncio.Semaphore(3)
    run_id = time_ns()

    async def evaluate(http, index, case):
        async with limit:
            raw = []
            context_token = capture_var.set(raw)
            try:
                case["request"]["validation_id"] = str(run_id + index)
                case["request"]["request_key"] = f"policy-eval-{run_id}-{index}"
                response = await http.post(
                    "/v1/validate",
                    json=case["request"],
                    headers={"Authorization": f"Bearer {token}"},
                )
                body, status = response.json(), response.status_code
            except Exception as exc:  # noqa: BLE001 - report per-case errors without transport details
                status, body = (
                    None,
                    {
                        "error": {
                            "code": "evaluation_transport_failure",
                            "type": type(exc).__name__,
                        }
                    },
                )
            finally:
                capture_var.reset(context_token)
            passed, detail = score(case, body)
            print(
                f"{case['name']} #{case['repetition']}: {'PASS' if passed and status == 200 else 'FAIL'} ({detail})",
                flush=True,
            )
            return {
                "name": case["name"],
                "split": case["split"],
                "expectation": case["expectation"],
                "repetition": case["repetition"],
                "passed": passed and status == 200,
                "detail": detail,
                "http_status": status,
                "response": body,
                "raw_model_content": raw,
            }

    try:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://policy-eval"
        ) as http:
            results = await asyncio.gather(
                *(evaluate(http, i, case) for i, case in enumerate(selected))
            )
    finally:
        route.get_validation_client = old_client
        app.dependency_overrides.clear()
        app.dependency_overrides.update(overrides)
        await provider.aclose()
    return {
        "created_at": datetime.now(UTC).isoformat(),
        "provider_host": "gms.ssafy.io",
        "model": model,
        "response_format": response_format,
        "scope": "synthetic FastAPI ASGI route and real GMS model; no BE, database or retrieval",
        "cases": results,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-live", action="store_true")
    parser.add_argument("--case", action="append")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--response-format",
        choices=["json_object", "json_schema"],
        default="json_object",
    )
    args = parser.parse_args()
    selected = [
        case for case in fixtures() if not args.case or case["name"] in args.case
    ]
    if not selected or (
        args.case and set(args.case) != {case["name"] for case in selected}
    ):
        raise ValueError("Unknown evaluation case")
    if not 1 <= args.repeat <= 5:
        raise ValueError("Repeat must be between 1 and 5")
    selected = [
        dict(copy.deepcopy(case), repetition=number)
        for case in selected
        for number in range(1, args.repeat + 1)
    ]
    for case in selected:
        validate_input(ValidateRequest.model_validate(case["request"]))
    if not args.run_live:
        print(f"Validated {len(selected)} synthetic inputs; no model calls.")
        return
    key = os.environ.get("GMS_KEY", "")
    if not key:
        raise ValueError("GMS_KEY must be set in the process environment")
    report = asyncio.run(run(key, "gpt-6-luna", selected, args.response_format))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"Passed {sum(case['passed'] for case in report['cases'])}/{len(report['cases'])}; synthetic report saved."
    )


if __name__ == "__main__":
    main()
