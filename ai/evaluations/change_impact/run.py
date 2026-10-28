"""Opt-in evaluation using synthetic data and the shared LLM client.

Without --execute this validates cases only and makes no model call.
"""

import argparse
import asyncio
import json
import os
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from app.services.change_impact import (
    ImpactAnalysisService,
    ImpactInput,
    ImpactModelConfig,
)
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider
from evaluations.change_impact.cases import CASES


async def evaluate(service, model, cases=CASES, *, response_format_mode="json_object"):
    rows = []
    for case in cases:
        request = ImpactInput.model_validate(
            {
                **case["request"],
                "request_key": f"impact-eval-{case['case_id']}-{uuid4()}",
            }
        )
        expected = case["expected_task_ids"]
        expected_evidence = case["expected_evidence_status"]
        try:
            response = await service.analyze(
                request,
                ImpactModelConfig(
                    model=model,
                    call_key=f"impact-eval-{case['case_id']}-{uuid4()}",
                    timeout_seconds=30,
                    response_format_mode=response_format_mode,
                ),
            )
            actual = sorted(
                {
                    candidate["task_id"]
                    for candidate in response.result["candidates"]
                    if candidate.get("task_id")
                }
            )
            rows.append(
                {
                    "case_id": case["case_id"],
                    "status": "completed",
                    "expected_task_ids": expected,
                    "actual_task_ids": actual,
                    "expected_evidence_status": expected_evidence,
                    "actual_evidence_status": response.result.get("evidence_status"),
                    "evidence_match": response.result.get("evidence_status")
                    == expected_evidence,
                    "target_match": None
                    if expected is None
                    else actual == sorted(expected),
                    "human_review": case["human_review"],
                    "result": response.result,
                    "usage": [usage.to_api_dict() for usage in response.usage],
                }
            )
        except LLMError as error:
            rows.append(
                {
                    "case_id": case["case_id"],
                    "status": "failed",
                    "error_code": error.code,
                    "usage": [usage.to_api_dict() for usage in error.usage],
                }
            )
    return rows


async def _execute(args):
    endpoint = os.environ.get("AI_IMPACT_EVAL_ENDPOINT")
    key = os.environ.get("AI_IMPACT_EVAL_API_KEY")
    model = os.environ.get("AI_IMPACT_EVAL_MODEL")
    if not endpoint or not key or not model:
        raise SystemExit(
            "Set AI_IMPACT_EVAL_ENDPOINT, AI_IMPACT_EVAL_API_KEY and AI_IMPACT_EVAL_MODEL."
        )
    async with OpenAICompatibleProvider(endpoint=endpoint, api_key=key) as provider:
        rows = await evaluate(
            ImpactAnalysisService(LLMClient(provider)),
            model,
            response_format_mode=args.response_format,
        )
    report = {
        "data": "synthetic",
        "created_at": datetime.now(UTC).isoformat(),
        "model": model,
        "response_format": args.response_format,
        "note": "Target matches do not assess explanation accuracy; human review required.",
        "cases": rows,
    }
    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(output + "\n", encoding="utf-8")
    else:
        print(output)


def main(args):
    for case in CASES:
        ImpactInput.model_validate(case["request"])
    if not args.execute:
        print(f"Validated {len(CASES)} synthetic cases; no model calls made.")
        return
    asyncio.run(_execute(args))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--execute", action="store_true", help="Run real model calls (may incur cost)."
    )
    parser.add_argument(
        "--output", help="Optional path for the synthetic evaluation report."
    )
    parser.add_argument(
        "--response-format",
        choices=("json_object", "json_schema"),
        default="json_object",
        help="Structured output mode; the provider must support the selected mode.",
    )
    main(parser.parse_args())
