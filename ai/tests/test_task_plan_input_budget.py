import asyncio
import json

import pytest
from test_task_plan_drafts import RecordingProvider, call, model_output, request_body

from app.schemas.task_plan_drafts import TaskPlanDraftRequest
from app.services.task_plan_drafts.input_budget import pack_input
from app.services.task_plan_drafts.proposer import (
    ModelDraft,
    PlanProposal,
    plan_payload,
)
from app.services.task_plan_drafts.service import generate_task_plan_draft


class CharacterCounter:
    def count(self, text):
        return len(text)


def request():
    body = request_body()
    body["excerpts"][0]["text"] = "원문 근거" * 100
    body["excerpts"][1]["text"] = "두 번째 원문" * 100
    body["design_items"][0]["summary"] = "설계 요약" * 40
    return TaskPlanDraftRequest.model_validate_json(json.dumps(body))


def test_packing_accounts_for_system_json_and_reserve_and_keeps_whole_excerpts():
    body = request()
    counter = CharacterCounter()
    full = len(plan_payload(body)) + len("system") + 50
    packed = pack_input(body, "system", full - 100, 50, counter)
    assert packed.after_tokens <= full - 100 < packed.before_tokens
    assert packed.after_tokens == len(plan_payload(packed.request)) + len("system") + 50
    assert packed.request.excerpts == (body.excerpts[0],)
    assert packed.request.design_items == body.design_items
    assert packed.request.excerpts[0].text == body.excerpts[0].text


def test_oversized_first_excerpt_does_not_prevent_a_smaller_lower_ranked_one():
    body = request()
    body = body.model_copy(
        update={
            "excerpts": (
                body.excerpts[0],
                body.excerpts[1].model_copy(update={"text": "짧은 근거"}),
            )
        }
    )
    packed = pack_input(body, "system", 500, 20, CharacterCounter())
    assert packed.request.excerpts == (body.excerpts[1],)


def test_budget_too_small_fails_before_paid_call():
    provider = RecordingProvider(model_output())
    response = call(
        provider,
        settings_kwargs={
            "task_plan_drafts_input_token_limit": "200",
            "task_plan_drafts_input_reserve_tokens": "128",
        },
    )
    assert response.status_code == 422
    assert provider.requests == []


@pytest.mark.parametrize(
    "setting,value",
    [
        ("task_plan_drafts_input_token_limit", "128"),
        ("task_plan_drafts_input_reserve_tokens", "-1"),
        ("task_plan_drafts_input_token_limit", "abc"),
    ],
)
def test_invalid_budget_is_configuration_failure(setting, value):
    provider = RecordingProvider(model_output())
    assert call(provider, settings_kwargs={setting: value}).status_code == 503
    assert provider.requests == []


def test_output_is_verified_against_the_context_actually_sent_after_packing():
    body = request()
    selected = body.model_copy(
        update={"excerpts": (body.excerpts[0],), "design_items": ()}
    )
    output = model_output(
        evidence=[{"field": "goal", "excerpt_id": "e2", "quote": "두 번째 원문"}]
    )

    class Proposer:
        async def propose(self, request):
            return PlanProposal(
                ModelDraft.model_validate(output), "fixture", (), selected
            )

    result = asyncio.run(generate_task_plan_draft(body, Proposer())).result
    assert result.evidence == ()
    assert result.design_ref_candidates == ()
    assert "goal" in result.unsupported_fields
