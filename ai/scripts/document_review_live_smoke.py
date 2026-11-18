"""Opt-in, paid GMS smoke test using only the repository's synthetic fixtures.

Run from ai/: python -m scripts.document_review_live_smoke --execute
The actual provider is used, while internal authentication uses an ephemeral token.
No credential, source text, model output, or raw upstream error is printed.
"""

import argparse
import json
import sys
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.routers.document_review import router

_FIXTURES = Path(__file__).resolve().parents[2] / "backend/src/test/resources/document"
_SAFE_CODES = {
    "invalid_ai_input",
    "validation_failed",
    "file_too_large",
    "model_timeout",
    "model_unavailable",
    "invalid_output",
    "invalid_input",
    "invalid_internal_token",
}


def _check_response(body: dict, payload: dict, expected: dict) -> None:
    if body.get("origin") != "MODEL" or not isinstance(body.get("result"), dict):
        raise ValueError("invalid envelope")
    result = body["result"]
    for name in (
        "document_id",
        "revision",
        "content_hash",
        "reference_hash",
        "request_key",
    ):
        if result.get(name) != payload[name]:
            raise ValueError("identifier mismatch")
    if result.get("review_status") != expected["review_status"]:
        raise ValueError("review status mismatch")
    actual_steps = result.get("steps")
    if not isinstance(actual_steps, list) or len(actual_steps) != 4:
        raise ValueError("steps mismatch")
    steps = {step.get("step"): step for step in actual_steps if isinstance(step, dict)}
    if len(steps) != 4:
        raise ValueError("duplicate steps")
    for expected_step in expected["steps"]:
        step = steps.get(expected_step["step"])
        allowed = (
            {"SKIPPED", "PARTIAL"}
            if expected_step["reason"] == "REFERENCE_UNAVAILABLE"
            else {expected_step["status"]}
        )
        if (
            not step
            or step.get("status") not in allowed
            or step.get("reason") != expected_step["reason"]
            or set(step.get("reference_ids", [])) != set(expected_step["reference_ids"])
        ):
            raise ValueError("step mismatch")
    if set(result.get("references_used", [])) != set(expected["references_used"]):
        raise ValueError("reference mismatch")
    usage = body.get("usage")
    if (
        not isinstance(usage, list)
        or not usage
        or not all(isinstance(item, dict) for item in usage)
        or not any(
            item.get("feature") == "BEE_DRAFT"
            and item.get("failed") is False
            and str(item.get("request_key", "")).startswith(payload["request_key"])
            and isinstance(item.get("model"), str)
            for item in usage
        )
    ):
        raise ValueError("successful model usage missing")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run two synthetic paid GMS document review calls."
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Explicitly enable the live provider calls",
    )
    parser.add_argument("--case", choices=("complete", "partial", "all"), default="all")
    args = parser.parse_args()
    if not args.execute:
        print("Live GMS calls are disabled. Pass --execute to run synthetic fixtures.")
        return 2
    try:
        token = uuid4().hex + uuid4().hex
        settings = Settings(ai_internal_token=token)
        if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
            print("GMS_KEY is not configured; no model call was made.")
            return 2
        app = FastAPI()
        app.include_router(router)
        app.dependency_overrides[get_settings] = lambda: settings
        cases = ("complete", "partial") if args.case == "all" else (args.case,)
        failures = 0
        with TestClient(app) as client:
            for case in cases:
                fixture = json.loads(
                    (_FIXTURES / f"review-contract-{case}.json").read_text(
                        encoding="utf-8"
                    )
                )
                payload = fixture["input"]
                payload["request_key"] = "document-review-smoke-" + uuid4().hex
                response = client.post(
                    "/v1/document-reviews",
                    json=payload,
                    headers={"Authorization": f"Bearer {token}"},
                )
                body = response.json()
                if response.status_code != 200:
                    code = (
                        body.get("error", {}).get("code")
                        if isinstance(body, dict)
                        else None
                    )
                    safe_code = code if code in _SAFE_CODES else "unclassified_error"
                    print(f"{case}: FAIL HTTP {response.status_code}, code={safe_code}")
                    failures += 1
                    continue
                try:
                    _check_response(body, payload, fixture["response"]["result"])
                except (TypeError, ValueError, KeyError):
                    print(f"{case}: FAIL response contract")
                    failures += 1
                    continue
                usage = body["usage"][0]
                print(
                    f"{case}: PASS status={body['result']['review_status']}, "
                    f"suggestions={len(body['result']['suggestions'])}, "
                    f"tokens_known={usage.get('tokens_known')}, latency_ms={usage.get('latency_ms')}"
                )
        return 1 if failures else 0
    except Exception:  # noqa: BLE001 - Provider errors may contain secrets or document content.
        print(
            "Live smoke test could not finish; inspect local configuration and network access."
        )
        return 1


if __name__ == "__main__":
    sys.exit(main())
