"""Opt-in billable AI-A08 smoke test using a synthetic meeting transcript.

Run from ai/: python -m evaluations.meeting_analysis.run --env-file <path>
Only the synthetic request below is sent. Never commit credentials or user data.
"""

import argparse
import asyncio
import json

from app.core.config import Settings
from app.schemas.meeting_analysis import MeetingAnalysisRequest
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider
from app.services.meeting_analysis import analyze


async def run(env_file: str, mode: str) -> int:
    settings = Settings(_env_file=env_file)
    if not settings.gms_key or not settings.gms_key.get_secret_value().strip():
        print("GMS key is not configured")
        return 2
    request = MeetingAnalysisRequest.model_validate(
        {
            "project_id": "101",
            "request_key": "meeting-190-synthetic-example",
            "mode": mode,
            "entries": [
                {
                    "id": "1",
                    "meeting_id": "9",
                    "kind": "TEXT",
                    "author_kind": "HUMAN",
                    "author_member_id": "21",
                    "text": "회원 조회 API 응답 필드는 memberId를 유지하겠습니다.",
                    "created_at": "2026-11-02T00:00:00Z",
                },
                {
                    "id": "2",
                    "meeting_id": "9",
                    "kind": "TEXT",
                    "author_kind": "HUMAN",
                    "author_member_id": "22",
                    "text": "동의합니다. 오류 응답의 재시도 정책은 아직 결정하지 않았습니다.",
                    "created_at": "2026-11-02T00:01:00Z",
                },
            ],
            "approved_items": [],
        }
    )
    provider = OpenAICompatibleProvider(
        endpoint="https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions",
        api_key=settings.gms_key.get_secret_value(),
    )
    try:
        async with provider:
            result = await analyze(
                request, LLMClient(provider), settings.bee_default_model
            )
    except LLMError as exc:
        print(
            json.dumps(
                {
                    "status": "ERROR",
                    "error_code": exc.code,
                    "usage": [item.to_api_dict() for item in exc.usage],
                }
            )
        )
        return 1
    summary = result["result"]
    checks = (
        {
            "member_id_decision_present": any(
                "memberId" in text for text in summary["decisions"]
            ),
            "retry_policy_unresolved": any(
                "재시도" in text for text in summary["unresolved"]
            ),
        }
        if mode == "MINUTES"
        else {}
    )
    print(json.dumps({"status": "OK", "checks": checks, **result}, indent=2))
    return 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--mode", choices=("QUESTIONS", "MINUTES"), default="MINUTES")
    args = parser.parse_args()
    raise SystemExit(asyncio.run(run(args.env_file, args.mode)))


if __name__ == "__main__":
    main()
