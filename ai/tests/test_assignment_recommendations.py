"""Recommendation accepts only scoped candidates and never performs assignment."""

import copy
import json

import pytest
from pydantic import ValidationError

from app.schemas.assignment_recommendations import AssignmentRequest
from app.services.assignment_recommendations import recommend_assignments
from app.services.llm_client import LLMClient, LLMError, ProviderResponse


def request_data():
    return {
        "project_id": "101",
        "request_key": "assignment-1",
        "tasks": [
            {
                "id": "501",
                "project_id": "101",
                "status": "READY",
                "current_plan_revision": {
                    "task_id": "501",
                    "plan": {
                        "title": "로그인 API 구현",
                        "goal": "승인된 인증 계약을 구현한다.",
                        "included_scope": ["로그인 API"],
                        "approach": "기존 인증 사용",
                        "dependency_task_ids": ["500", "999"],
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
        "current_tasks": [
            {
                "id": "500",
                "project_id": "101",
                "status": "IN_PROGRESS",
                "assignee_member_id": "21",
            },
            {
                "id": "502",
                "project_id": "101",
                "status": "WAITING",
                "assignee_member_id": "21",
            },
            {
                "id": "503",
                "project_id": "101",
                "status": "COMPLETED",
                "assignee_member_id": "22",
            },
        ],
        "current_tasks_complete": True,
    }


def ranked_output(*member_ids, task_id="501", basis="AREA"):
    return {
        "recommendations": [
            {
                "task_id": task_id,
                "candidates": [
                    {"member_id": item, "basis": basis} for item in member_ids
                ],
            }
        ]
    }


class Provider:
    def __init__(self, output=None):
        self.output = output if output is not None else ranked_output("21", "22")
        self.calls = []

    async def complete(self, request, *, timeout_seconds):
        self.calls.append(request)
        return ProviderResponse(json.dumps(self.output), request.model, 20, 10)


@pytest.mark.asyncio
async def test_recommendations_and_alternatives_include_snapshot_based_reasons():
    raw = request_data()
    original = copy.deepcopy(raw)
    provider = Provider()
    result = await recommend_assignments(
        AssignmentRequest.model_validate(raw), LLMClient(provider), "test-model"
    )
    assert raw == original
    assert result["origin"] == "MODEL"
    assert result["usage"][0]["feature"] == "TASK_ASSIGNMENT"
    assert result["usage"][0]["request_key"].startswith("llm-assignment-")
    suggestion = result["result"]["suggestions"][0]
    assert suggestion["task_id"] == "501" and suggestion["member_id"] == "21"
    assert "backend" in suggestion["reason"]
    assert "다른 초안·준비·진행·대기 작업은 2건" in suggestion["reason"]
    assert "선행 작업 1건 중 이 사람이 담당한 작업은 1건" in suggestion["reason"]
    assert "선행 작업 1건의 상태와 담당자는 확인되지 않았습니다" in suggestion["reason"]
    alternative = suggestion["alternatives"][0]
    assert alternative["member_id"] == "22" and alternative["reason"].strip()
    assert "다른 초안·준비·진행·대기 작업은 0건" in alternative["reason"]
    sent = json.loads(provider.calls[0].messages[1].content)
    assert sent["workload_complete"] is True
    assert sent["members"][0]["observed_open_tasks"] == 2
    assert sent["unknown_dependency_ids"] == ["999"]
    assert "basis" in provider.calls[0].messages[0].content
    assert "모델이 작업 내용과 담당 영역" in suggestion["reason"]
    assert "IN_PROGRESS" == sent["dependencies"][0]["status"]


@pytest.mark.asyncio
async def test_unknown_workload_is_never_presented_as_zero():
    raw = request_data()
    raw.pop("current_tasks")
    raw.pop("current_tasks_complete")
    provider = Provider()
    result = await recommend_assignments(
        AssignmentRequest.model_validate(raw), LLMClient(provider), "test-model"
    )
    suggestion = result["result"]["suggestions"][0]
    assert "현재 업무량은 제공되지 않아 확인할 수 없습니다" in suggestion["reason"]
    assert "0건" not in suggestion["reason"].split("선행")[0]
    assert (
        json.loads(provider.calls[0].messages[1].content)["members"][0][
            "observed_open_tasks"
        ]
        is None
    )
    assert result["result"]["unresolved"]


@pytest.mark.asyncio
async def test_partial_workload_is_described_as_observed_not_total():
    raw = request_data()
    raw["current_tasks_complete"] = False
    provider = Provider()
    result = await recommend_assignments(
        AssignmentRequest.model_validate(raw), LLMClient(provider), "test-model"
    )
    reason = result["result"]["suggestions"][0]["reason"]
    assert "제공된 목록의 다른 초안·준비·진행·대기 작업은 2건" in reason
    assert "전체 업무량은 확인되지 않았습니다" in reason


@pytest.mark.asyncio
async def test_draft_is_counted_but_unassigned_and_closed_tasks_are_not():
    raw = request_data()
    raw["current_tasks"].extend(
        [
            {
                "id": "504",
                "project_id": "101",
                "status": "DRAFT",
                "assignee_member_id": "22",
            },
            {"id": "505", "project_id": "101", "status": "DRAFT"},
            {
                "id": "506",
                "project_id": "101",
                "status": "CANCELLED",
                "assignee_member_id": "22",
            },
        ]
    )
    provider = Provider()
    result = await recommend_assignments(
        AssignmentRequest.model_validate(raw), LLMClient(provider), "test-model"
    )
    sent = json.loads(provider.calls[0].messages[1].content)
    assert sent["members"][1]["observed_open_tasks"] == 1
    alternative = result["result"]["suggestions"][0]["alternatives"][0]
    assert "다른 초안·준비·진행·대기 작업은 1건" in alternative["reason"]


@pytest.mark.asyncio
@pytest.mark.parametrize("status", ["READY", "DRAFT"])
async def test_current_target_is_not_counted_as_another_task(status):
    raw = request_data()
    raw["tasks"][0]["assignee_member_id"] = "21"
    raw["tasks"][0]["status"] = status
    raw["current_tasks"].append(
        {
            "id": "501",
            "project_id": "101",
            "status": status,
            "assignee_member_id": "21",
        }
    )
    provider = Provider()
    result = await recommend_assignments(
        AssignmentRequest.model_validate(raw), LLMClient(provider), "test-model"
    )
    sent = json.loads(provider.calls[0].messages[1].content)
    assert sent["members"][0]["observed_open_tasks"] == 3
    assert sent["tasks"][0]["counted_in_workload"] is True
    assert (
        "다른 초안·준비·진행·대기 작업은 2건"
        in result["result"]["suggestions"][0]["reason"]
    )


@pytest.mark.asyncio
async def test_multiple_tasks_may_suggest_the_same_member_without_assigning():
    raw = request_data()
    second = copy.deepcopy(raw["tasks"][0])
    second["id"] = "601"
    second["current_plan_revision"]["task_id"] = "601"
    raw["tasks"].append(second)
    output = ranked_output("21", "22")
    output["recommendations"].append(
        ranked_output("21", "22", task_id="601")["recommendations"][0]
    )
    before = copy.deepcopy(raw)
    result = await recommend_assignments(
        AssignmentRequest.model_validate(raw), LLMClient(Provider(output)), "test-model"
    )
    assert [entry["member_id"] for entry in result["result"]["suggestions"]] == [
        "21",
        "21",
    ]
    assert raw == before


@pytest.mark.asyncio
async def test_empty_or_single_member_is_honest_about_alternatives():
    raw = request_data()
    raw["members"] = []
    provider = Provider()
    empty = await recommend_assignments(
        AssignmentRequest.model_validate(raw), LLMClient(provider), "test-model"
    )
    assert empty["origin"] == "TEMPLATE" and not empty["result"]["suggestions"]
    assert empty["result"]["unresolved"] and not provider.calls
    raw["members"] = request_data()["members"][:1]
    provider = Provider(ranked_output("21"))
    single = await recommend_assignments(
        AssignmentRequest.model_validate(raw), LLMClient(provider), "test-model"
    )
    assert single["result"]["suggestions"][0]["alternatives"] == []
    assert any("대안" in text for text in single["result"]["unresolved"])


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "output",
    [
        ranked_output("21", "22", task_id="foreign"),
        ranked_output("21", "foreign"),
        ranked_output("21", "21"),
        ranked_output("21"),
        {"recommendations": []},
    ],
)
async def test_invalid_model_ids_duplicates_and_missing_alternatives_are_rejected(
    output,
):
    provider = Provider(output)
    with pytest.raises(LLMError) as error:
        await recommend_assignments(
            AssignmentRequest.model_validate(request_data()),
            LLMClient(provider),
            "test-model",
        )
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed is True
    assert "foreign" not in json.dumps(error.value.to_api_dict())


@pytest.mark.asyncio
async def test_long_plan_is_clipped_with_a_visible_limit_warning():
    raw = request_data()
    raw["tasks"][0]["current_plan_revision"]["plan"]["goal"] = "긴" * 5000
    provider = Provider()
    result = await recommend_assignments(
        AssignmentRequest.model_validate(raw), LLMClient(provider), "test-model"
    )
    sent = json.loads(provider.calls[0].messages[1].content)
    plan = sent["tasks"][0]["plan"]
    assert len(plan["goal"]) == 800 and plan["text_truncated"] is True
    assert any("일부만" in text for text in result["result"]["unresolved"])


@pytest.mark.asyncio
@pytest.mark.parametrize("basis", ["AREA", "WORKLOAD", "DEPENDENCY"])
async def test_model_cannot_claim_a_basis_that_was_not_supplied(basis):
    raw = request_data()
    if basis == "AREA":
        raw["members"][0].pop("domain")
        raw["members"][0].pop("role_label")
    elif basis == "WORKLOAD":
        raw.pop("current_tasks")
        raw["current_tasks_complete"] = False
    else:
        raw["tasks"][0]["current_plan_revision"]["plan"]["dependency_task_ids"] = [
            "999"
        ]
    provider = Provider(ranked_output("21", "22", basis=basis))
    with pytest.raises(LLMError) as error:
        await recommend_assignments(
            AssignmentRequest.model_validate(raw), LLMClient(provider), "test-model"
        )
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed is True


@pytest.mark.parametrize(
    "variant",
    [
        "foreign_target",
        "foreign_workload",
        "duplicate_member",
        "inconsistent_target",
        "false_complete",
        "false_complete_draft",
    ],
)
def test_snapshot_rejects_inconsistent_scope_and_completeness(variant):
    raw = request_data()
    if variant == "foreign_target":
        raw["tasks"][0]["project_id"] = "other"
    elif variant == "foreign_workload":
        raw["current_tasks"][0]["project_id"] = "other"
    elif variant == "duplicate_member":
        raw["members"].append(raw["members"][0])
    elif variant == "inconsistent_target":
        raw["current_tasks"].append(
            {"id": "501", "project_id": "101", "status": "WAITING"}
        )
    elif variant == "false_complete":
        raw["tasks"][0]["assignee_member_id"] = "21"
    elif variant == "false_complete_draft":
        raw["tasks"][0]["status"] = "DRAFT"
        raw["tasks"][0]["assignee_member_id"] = "21"
    with pytest.raises(ValidationError):
        AssignmentRequest.model_validate(raw)
