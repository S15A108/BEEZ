"""Opt-in GMS smoke test with synthetic, explicitly pre-confirmed decisions."""

import argparse
import asyncio
import json

from app.core.config import Settings
from app.routers.document_review import _valid_gms_endpoint
from app.schemas.documents import SourceInput
from app.services.document_parsing.normalization import text_digest
from app.services.document_parsing.pipeline import parse_source
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider
from app.services.meeting_revision_bridge import generate_meeting_revision


async def run(env_file):
    settings = Settings(_env_file=env_file)
    if settings.gms_key is None or not _valid_gms_endpoint(settings.gms_chat_endpoint):
        raise ValueError("GMS settings are not configured")
    cases = [
        (
            "permissions",
            "삭제 권한은 모든 사용자에게 있습니다.",
            "삭제 권한은 팀장만 가집니다.",
            "팀장",
        ),
        (
            "retention",
            "기록은 30일 동안 보관합니다.",
            "기록 보관 기간을 90일로 변경합니다.",
            "90",
        ),
        (
            "notifications",
            "변경 알림은 모든 구성원에게 발송합니다.",
            "변경 알림은 담당자에게만 발송합니다.",
            "담당자",
        ),
    ]
    outcomes = []
    async with OpenAICompatibleProvider(
        settings.gms_chat_endpoint, settings.gms_key.get_secret_value()
    ) as provider:
        for name, before, decision, expected in cases:
            text = f"유지할 앞 문단입니다.\n\n{before}\n\n유지할 뒤 문단입니다.\n"
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
            selected = next(
                block.block_id
                for block in parse_source(source).blocks
                if block.locator.start_line == 3
            )
            data = {
                "project_id": "101",
                "meeting_id": "901",
                "document_id": "201",
                "document_version_id": "211",
                "source": source.model_dump(),
                "entries": [{"id": "911", "meeting_id": "901"}],
                "decisions": [
                    {
                        "id": "summary:1:decision:0",
                        "text": decision,
                        "evidence_entry_ids": ["911"],
                    }
                ],
                "selected_block_ids": [selected],
                "instruction": "확인된 회의 결정에 맞게 해당 문단을 수정하세요.",
                "request_key": f"191-live-{name}",
            }
            try:
                response = await generate_meeting_revision(
                    data, LLMClient(provider), settings.bee_default_model
                )
                result = response["draft"]["result"]
                after = result["after_content"]
                passed = (
                    bool(result["changes"])
                    and expected in after
                    and after.startswith("유지할 앞 문단입니다.\n\n")
                    and after.endswith("\n\n유지할 뒤 문단입니다.\n")
                )
                outcomes.append(
                    {
                        "case": name,
                        "passed": passed,
                        "changes": len(result["changes"]),
                        "unresolved": len(result["unresolved"]),
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
