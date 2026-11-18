"""Paid GMS probe: summarize synthetic discussion, assume confirmation, revise."""

import argparse
import asyncio
import json

from app.core.config import Settings
from app.routers.document_review import _valid_gms_endpoint
from app.schemas.documents import SourceInput
from app.schemas.meeting_analysis import MeetingAnalysisRequest
from app.services.document_parsing.normalization import text_digest
from app.services.document_parsing.pipeline import parse_source
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider
from app.services.meeting_analysis import analyze
from app.services.meeting_revision_bridge import generate_meeting_revision


async def run(env_file):
    settings = Settings(_env_file=env_file)
    if settings.gms_key is None or not _valid_gms_endpoint(settings.gms_chat_endpoint):
        raise ValueError("GMS settings are not configured")
    text = "서비스 정책입니다.\n\n기록은 30일 보관합니다.\n\n변경 이력은 보존합니다.\n"
    digest = text_digest(text)
    source = SourceInput(
        project_id="101",
        document_version_id="211",
        kind="PLAN",
        media_type="text/plain",
        content=text,
        content_hash=digest,
        text_hash=digest,
        normalization_version="utf8-lf-v1",
        parser_profile="plain-v1",
    )
    request = MeetingAnalysisRequest.model_validate(
        {
            "project_id": "101",
            "request_key": "191-chain-summary",
            "mode": "SUMMARY",
            "entries": [
                {
                    "id": "911",
                    "meeting_id": "901",
                    "kind": "TEXT",
                    "text": "기록을 90일 보관하기로 모두 동의하고 확정했습니다.",
                    "author_member_id": "21",
                    "created_at": "2026-11-05T00:00:00Z",
                }
            ],
            "approved_items": [],
            "related_tasks": [],
            "documents": [
                {"document_id": "201", "document_version_id": "211", "text": text}
            ],
        }
    )
    async with OpenAICompatibleProvider(
        settings.gms_chat_endpoint, settings.gms_key.get_secret_value()
    ) as provider:
        client = LLMClient(provider)
        try:
            summary = (await analyze(request, client, settings.bee_default_model))[
                "result"
            ]
            decisions = [
                decision for decision in summary["decisions"] if "90" in decision
            ]
            if len(decisions) != 1:
                print(json.dumps({"passed": False, "error": "decision_not_found"}))
                return False
            selected = next(
                block.block_id
                for block in parse_source(source).blocks
                if block.locator.start_line == 3
            )
            # Test fixture assumes the person confirms the generated decision and link.
            body = {
                "project_id": "101",
                "meeting_id": "901",
                "document_id": "201",
                "document_version_id": "211",
                "source": source.model_dump(),
                "entries": [{"id": "911", "meeting_id": "901"}],
                "decisions": [
                    {
                        "id": "summary:1:decision:0",
                        "text": decisions[0],
                        "evidence_entry_ids": ["911"],
                    }
                ],
                "selected_block_ids": [selected],
                "instruction": "확인된 회의 결정에 맞게 해당 문단을 수정하세요.",
                "request_key": "191-chain-revision",
            }
            result = (
                await generate_meeting_revision(
                    body, client, settings.bee_default_model
                )
            )["draft"]["result"]
            after = result["after_content"]
            passed = (
                bool(result["changes"])
                and "90" in after
                and after.startswith("서비스 정책입니다.\n\n")
                and after.endswith("\n\n변경 이력은 보존합니다.\n")
            )
            print(
                json.dumps(
                    {
                        "passed": passed,
                        "changes": len(result["changes"]),
                        "confirmation": "assumed_synthetic",
                    }
                )
            )
            return passed
        except LLMError as exc:
            print(json.dumps({"passed": False, "error": exc.code}))
            return False


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", required=True)
    args = parser.parse_args()
    raise SystemExit(0 if asyncio.run(run(args.env_file)) else 1)
