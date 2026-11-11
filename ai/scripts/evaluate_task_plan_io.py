"""Replay a saved task-plan context. Offline by default; --live makes ONE GMS call.

Run BE TaskPlanDraftLoginReplayTest first to produce the selected input fixture.
Writes counts/usage and verified result; never writes credentials or provider bodies.
"""

import argparse
import asyncio
import json
import logging
import time
from pathlib import Path
from uuid import uuid4

from app.core.config import Settings
from app.routers.document_review import _valid_gms_endpoint
from app.routers.task_plan_drafts import require_task_plan_draft_config
from app.schemas.task_plan_drafts import TaskPlanDraftRequest
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    OpenAICompatibleProvider,
    ProviderError,
)
from app.services.retrieval.tokenization import EmbeddingTokenCounter
from app.services.task_plan_drafts import LLMPlanProposer, generate_task_plan_draft
from app.services.task_plan_drafts.input_budget import pack_input
from app.services.task_plan_drafts.proposer import system_prompt

ROOT = Path(__file__).resolve().parents[2]


def load_request(path: Path) -> TaskPlanDraftRequest:
    payload = json.loads(path.read_text(encoding="utf-8"))
    # Saved model payloads omit BE metadata. Synthetic IDs here are only for
    # replay/quote verification, never a replacement for runtime authorization.
    for item in payload["excerpts"]:
        item.update(document_id="1", document_version_id="1")
    return TaskPlanDraftRequest.model_validate_json(
        json.dumps(
            {
                "project_id": "1",
                "request_key": "io-replay-" + uuid4().hex,
                **payload,
            }
        )
    )


class MeasuredProvider:
    def __init__(self, provider):
        self.provider = provider
        self.diagnostics = {}

    async def complete(self, request, *, timeout_seconds):
        try:
            response = await self.provider.complete(
                request, timeout_seconds=timeout_seconds
            )
        except ProviderError as error:
            self.diagnostics = {
                "reasoning_tokens": error.reasoning_tokens,
                "finish_reason": error.finish_reason,
            }
            raise
        self.diagnostics = {
            "reasoning_tokens": response.reasoning_tokens,
            "finish_reason": response.finish_reason,
        }
        return response


async def evaluate(args):
    settings = Settings()
    config = require_task_plan_draft_config(settings)
    before = load_request(ROOT / "docs/experiments/task-plan-385/login-input.json")
    selected = load_request(ROOT / "backend/build/task-plan-385/login-selected.json")
    system = system_prompt(config.output_limits)
    counter = EmbeddingTokenCounter()
    packed_before = pack_input(
        before, system, 2**31 - 1, config.input_reserve_tokens, counter
    )
    packed = pack_input(
        selected, system, config.input_token_limit, config.input_reserve_tokens, counter
    )
    report = {
        "mode": "live-single-call" if args.live else "offline",
        "baseline_from_user": {
            "input_tokens": 4155,
            "output_tokens": 1500,
            "reasoning_tokens": 1500,
            "finish_reason": "length",
            "latency_ms": 14656,
        },
        "input": {
            "tokenizer": packed.tokenizer,
            "estimated_tokens_before": packed_before.after_tokens,
            "estimated_tokens_after": packed.after_tokens,
            "excerpts_before": len(before.excerpts),
            "excerpts_after": len(packed.request.excerpts),
            "design_items_before": len(before.design_items),
            "design_items_after": len(packed.request.design_items),
            "source_chars_before": sum(len(i.text) for i in before.excerpts),
            "source_chars_after": sum(len(i.text) for i in packed.request.excerpts),
        },
        "limits": {
            "input_tokens": config.input_token_limit,
            "output_tokens": config.output_token_limit,
            "reasoning_effort": config.reasoning_effort,
        },
    }
    if args.live:
        if settings.gms_key is None or not _valid_gms_endpoint(
            settings.gms_chat_endpoint
        ):
            raise SystemExit(
                "Configured GMS credential/endpoint required; no call made."
            )
        async with OpenAICompatibleProvider(
            settings.gms_chat_endpoint, settings.gms_key.get_secret_value()
        ) as provider:
            measured = MeasuredProvider(provider)
            proposer = LLMPlanProposer(
                LLMClient(measured),
                context=LLMContext("1", "BEE_DRAFT", selected.request_key),
                model=settings.bee_default_model,
                call_key="io-replay-" + uuid4().hex,
                timeout_seconds=config.timeout_seconds,
                output_token_limit=config.output_token_limit,
                output_token_parameter=config.output_token_parameter,
                reasoning_effort=config.reasoning_effort,
                output_limits=config.output_limits,
                input_token_limit=config.input_token_limit,
                input_reserve_tokens=config.input_reserve_tokens,
            )
            started = time.monotonic()
            try:
                outcome = await generate_task_plan_draft(selected, proposer)
                report["live"] = {
                    "success": True,
                    "result": outcome.result.model_dump(mode="json"),
                    "usage": [u.to_api_dict() for u in outcome.usage],
                }
            except LLMError as error:
                report["live"] = {
                    "success": False,
                    "code": error.code,
                    "usage": [u.to_api_dict() for u in error.usage],
                }
            report["live"].update(measured.diagnostics)
            report["live"]["latency_ms"] = int((time.monotonic() - started) * 1000)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "report": str(args.output),
                "input": report["input"],
                "live_success": report.get("live", {}).get("success"),
            },
            ensure_ascii=True,
        )
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true")
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / "docs/experiments/task-plan-385/io-evaluation.json",
    )
    arguments = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    asyncio.run(evaluate(arguments))
