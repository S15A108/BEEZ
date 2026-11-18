"""Synthetic AI-route evidence for AC-02 and AC-03; not the BE acceptance verdict.

Without --run-live only validates inputs. Live mode makes two billable calls.
The inputs are synthetic and outputs still require human semantic review.
"""

import argparse
import asyncio
import json
from copy import deepcopy
from hashlib import sha256
from pathlib import Path

from app.schemas.validation import ValidateRequest
from app.services.validation import validate_input
from evaluations.validation import run as evaluation
from evaluations.validation.fixtures import fixtures

_SNAPSHOT_KEYS = (
    "project_id",
    "plan_revision",
    "approved_items",
    "related_plans",
    "rule_findings",
    "unresolved",
    "checks",
    "evidence",
)


def acceptance_cases():
    existing = {item["name"]: item for item in fixtures()}
    # AC-02's direction matters: approval has userId while the plan has memberId.
    mismatch = deepcopy(existing["normal"])
    mismatch["name"] = "ac02_mismatch"
    mismatch["expectation"] = (
        "approved userId versus plan memberId, with evidence and an action"
    )
    request = mismatch["request"]
    request["request_key"] = "acceptance-209-ac02"
    request["validation_id"] = "20902"
    request["approved_items"][0]["content"]["request_fields"][0]["name"] = "userId"
    evidence = request["evidence"][0]
    evidence["text"] = evidence["text"].replace(
        "요청 JSON: memberId", "요청 JSON: userId"
    )
    evidence["text_hash"] = sha256(evidence["text"].encode("utf-8")).hexdigest()
    snapshot = {key: request[key] for key in _SNAPSHOT_KEYS}
    serialized = json.dumps(
        snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )
    request["input_snapshot_hash"] = (
        "sha256:" + sha256(serialized.encode("utf-8")).hexdigest()
    )

    policy = deepcopy(existing["policy_unresolved"])
    policy["name"] = "ac03_policy_unresolved"
    policy["expectation"] = "insufficient policy evidence, not a fabricated approval"
    return [mismatch, policy]


def assess(case, response):
    """Check observable AI output only; BE status and semantic correctness are separate."""
    result = response.get("result")
    if not isinstance(result, dict):
        return False, "model call or output validation failed"
    if case == "ac02_mismatch":
        ok = result.get("analysis_status") == "COMPLETE" and any(
            finding.get("kind") == "MISMATCH"
            and finding.get("severity") == "BLOCKING"
            and finding.get("design_item_id") == "301"
            and finding.get("design_item_version_id") == "311"
            and "userid" in finding.get("message", "").lower()
            and "memberid" in finding.get("message", "").lower()
            and bool(finding.get("suggested_action", "").strip())
            and "e-member-api" in finding.get("evidence_ids", [])
            and any(
                source.get("document_id") == "201"
                and source.get("document_version_id") == "211"
                for source in finding.get("sources", [])
            )
            for finding in result.get("findings", [])
        )
        return ok, "blocking mismatch, approved version, document evidence and action"
    if case == "ac03_policy_unresolved":
        ok = (
            result.get("analysis_status") == "PARTIAL"
            and any(
                item.get("reason") == "EVIDENCE_INSUFFICIENT"
                for item in result.get("unassessed", [])
            )
            and not any(
                finding.get("kind") == "MISSING_POLICY"
                for finding in result.get("findings", [])
            )
        )
        return (
            ok,
            "partial AI result with insufficient evidence; verify BE status separately",
        )
    raise ValueError("unknown acceptance case")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--run-live", action="store_true", help="Make two billable LLM calls"
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cases = acceptance_cases()
    for case in cases:
        validate_input(ValidateRequest.model_validate(case["request"]))
    if not args.run_live:
        print("Validated two synthetic 209 inputs. No LLM call was made.")
        return
    endpoint, model, key = evaluation.configuration()
    report = asyncio.run(
        evaluation.run(endpoint, model, key, cases=cases, assessor=assess)
    )
    report["scope"] = (
        "Synthetic AC-02/03 FastAPI route with injected provider; "
        "no BE verdict, DB, deployed GMS route, or human semantic review"
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    for case in report["cases"]:
        print(
            f"{case['name']}: {'MATCH' if case['passed'] else 'CHECK'} ({case['criterion']})"
        )
    print("Synthetic AI output saved; BE verdict and human review remain separate.")


if __name__ == "__main__":
    main()
