"""Opt-in GMS smoke test for the upstream meeting SUMMARY contract."""

import argparse
import asyncio
import json

from app.core.config import Settings
from app.routers.document_review import _valid_gms_endpoint
from app.schemas.meeting_analysis import MeetingAnalysisRequest
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider
from app.services.meeting_analysis import analyze


async def run(env_file):
    settings = Settings(_env_file=env_file)
    if settings.gms_key is None or not _valid_gms_endpoint(settings.gms_chat_endpoint):
        raise ValueError("GMS settings are not configured")
    cases = [
        (
            "permissions",
            "모든 사용자가 삭제할 수 있습니다.",
            "팀장만 삭제하기로 결정했습니다.",
            "팀장",
        ),
        (
            "retention",
            "기록은 30일 보관합니다.",
            "기록을 90일 보관하기로 결정했습니다.",
            "90",
        ),
        (
            "notifications",
            "모든 구성원에게 알림을 보냅니다.",
            "알림은 담당자에게만 보내기로 결정했습니다.",
            "담당자",
        ),
    ]
    outcomes = []
    async with OpenAICompatibleProvider(
        settings.gms_chat_endpoint, settings.gms_key.get_secret_value()
    ) as provider:
        for name, before, decision, expected in cases:
            request = MeetingAnalysisRequest.model_validate(
                {
                    "project_id": "101",
                    "request_key": f"191-summary-{name}",
                    "mode": "SUMMARY",
                    "entries": [
                        {
                            "id": "911",
                            "meeting_id": "901",
                            "kind": "TEXT",
                            "text": decision,
                            "author_member_id": "21",
                            "created_at": "2026-11-05T00:00:00Z",
                        }
                    ],
                    "approved_items": [],
                    "related_tasks": [],
                    "documents": [
                        {
                            "document_id": "201",
                            "document_version_id": "211",
                            "text": f"{before}\n",
                        }
                    ],
                }
            )
            try:
                result = (
                    await analyze(
                        request, LLMClient(provider), settings.bee_default_model
                    )
                )["result"]
                passed = bool(result["decisions"]) and expected in " ".join(
                    result["decisions"]
                )
                outcomes.append(
                    {
                        "case": name,
                        "passed": passed,
                        "decisions": len(result["decisions"]),
                        "changes": len(result["document_changes"]),
                    }
                )
            except LLMError as exc:
                outcomes.append({"case": name, "passed": False, "error": exc.code})
    print(
        json.dumps(
            {"model": settings.bee_default_model, "cases": outcomes}, ensure_ascii=False
        )
    )
    return all(item["passed"] for item in outcomes)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", required=True)
    args = parser.parse_args()
    raise SystemExit(0 if asyncio.run(run(args.env_file)) else 1)
