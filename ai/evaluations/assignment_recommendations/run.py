"""Optional billable GMS checks using only synthetic task/member snapshots."""

import argparse
import asyncio
import copy
import json

from app.core.config import Settings
from app.schemas.assignment_recommendations import AssignmentRequest
from app.services.assignment_recommendations import recommend_assignments
from app.services.llm_client import LLMClient, LLMError, OpenAICompatibleProvider


def cases() -> list[tuple[str, dict, str]]:
    base = {
        "project_id": "101",
        "request_key": "assignment-136-synthetic",
        "tasks": [
            {
                "id": "501",
                "project_id": "101",
                "status": "READY",
                "current_plan_revision": {
                    "task_id": "501",
                    "plan": {
                        "title": "Python 백엔드 로그인 API 구현",
                        "goal": "서버 인증 API를 구현한다.",
                        "dependency_task_ids": [],
                    },
                },
            }
        ],
        "members": [
            {"id": "21", "role": "MEMBER", "domain": "backend", "role_label": "백엔드"},
            {
                "id": "22",
                "role": "MEMBER",
                "domain": "frontend",
                "role_label": "프론트엔드",
            },
        ],
        "current_tasks": [],
        "current_tasks_complete": True,
    }
    domain = copy.deepcopy(base)
    load = copy.deepcopy(base)
    load["members"][1]["domain"] = "backend"
    load["members"][1]["role_label"] = "백엔드"
    load["current_tasks"] = [
        {
            "id": str(i),
            "project_id": "101",
            "status": "IN_PROGRESS",
            "assignee_member_id": "21",
        }
        for i in (511, 512, 513)
    ]
    dependency = copy.deepcopy(base)
    dependency["members"][1]["domain"] = "backend"
    dependency["members"][1]["role_label"] = "백엔드"
    dependency["tasks"][0]["current_plan_revision"]["plan"]["dependency_task_ids"] = [
        "510"
    ]
    dependency["current_tasks"] = [
        {
            "id": "510",
            "project_id": "101",
            "status": "COMPLETED",
            "assignee_member_id": "22",
        }
    ]
    unknown = copy.deepcopy(base)
    unknown.pop("current_tasks")
    unknown["current_tasks_complete"] = False
    return [
        ("domain", domain, "21"),
        ("workload", load, "22"),
        ("dependency", dependency, "22"),
        ("unknown_workload", unknown, "21"),
    ]


async def run(env_file: str) -> int:
    settings = Settings(_env_file=env_file)
    if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
        print("GMS key is not configured")
        return 2
    provider = OpenAICompatibleProvider(
        endpoint="https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions",
        api_key=settings.gms_key.get_secret_value(),
    )
    failed = False
    async with provider:
        client = LLMClient(provider)
        for name, data, expected in cases():
            try:
                outcome = await recommend_assignments(
                    AssignmentRequest.model_validate(data),
                    client,
                    settings.bee_default_model,
                )
                suggestion = outcome["result"]["suggestions"][0]
                print(
                    json.dumps(
                        {
                            "case": name,
                            "status": "OK",
                            "selected": suggestion["member_id"],
                            "expected_for_fixture": expected,
                            "matched": suggestion["member_id"] == expected,
                            "reason": suggestion["reason"],
                            "alternatives": suggestion["alternatives"],
                            "unresolved": outcome["result"]["unresolved"],
                            "usage": outcome["usage"],
                        },
                        ensure_ascii=False,
                    ),
                    flush=True,
                )
            except LLMError as exc:
                failed = True
                print(
                    json.dumps(
                        {
                            "case": name,
                            "status": "ERROR",
                            "error_code": exc.code,
                            "usage": [item.to_api_dict() for item in exc.usage],
                        }
                    ),
                    flush=True,
                )
    return 1 if failed else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--env-file", required=True, help="local settings file (never committed)"
    )
    args = parser.parse_args()
    raise SystemExit(asyncio.run(run(args.env_file)))


if __name__ == "__main__":
    main()
