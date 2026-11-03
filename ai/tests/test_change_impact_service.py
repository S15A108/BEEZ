"""Internal #188 groundwork: fake provider only; no API or live-model claims."""

import asyncio
import json
from copy import deepcopy

import pytest
from pydantic import ValidationError

from app.services.change_impact import (
    ImpactAnalysisService,
    ImpactInput,
    ImpactModelConfig,
)
from app.services.llm_client import LLMClient, LLMError, ProviderError, ProviderResponse

SOURCE = {
    "document_id": "201",
    "document_version_id": "211",
    "version_no": 1,
    "location": "API / 회원 조회",
}


def input_data():
    return ImpactInput.model_validate(
        {
            "project_id": "101",
            "request_key": "impact-request-1",
            "proposal_id": "71",
            "changes": [
                {
                    "operation": "UPDATE",
                    "design_item_id": "301",
                    "base_design_item_version_id": "311",
                    "schema_version": 1,
                    "proposed_content": {"path": "/members/{memberId}"},
                }
            ],
            "approved_items": [
                {
                    "id": "311",
                    "design_item_id": "301",
                    "status": "APPROVED",
                    "content": {"path": "/members/{userId}"},
                    "source": SOURCE,
                }
            ],
            "explicit_dependencies": [],
            "open_tasks": [
                {
                    "id": "501",
                    "project_id": "101",
                    "assignee_member_id": "21",
                    "current_plan_revision": {
                        "id": "511",
                        "plan": {"goal": "회원 조회"},
                    },
                }
            ],
        }
    )


def candidate():
    return {
        "task_id": "501",
        "member_id": "21",
        "design_item_id": "301",
        "reason": "회원 조회 경로의 식별자가 바뀌어 계획을 확인해야 합니다.",
        "sources": [SOURCE],
    }


class FakeProvider:
    def __init__(self, output=None, failure=None):
        self.output = output
        self.failure = failure
        self.requests = []

    async def complete(self, request, *, timeout_seconds):
        self.requests.append(request)
        if self.failure:
            raise self.failure
        return ProviderResponse(
            content=json.dumps(self.output, ensure_ascii=False),
            model=request.model,
            input_tokens=12,
            output_tokens=7,
        )


_loop = None


@pytest.fixture(scope="module", autouse=True)
def shared_event_loop():
    # Avoid creating a fresh Windows socket pair for every test.
    global _loop
    _loop = asyncio.new_event_loop()
    yield
    _loop.close()
    _loop = None


def invoke(provider, data=None):
    service = ImpactAnalysisService(LLMClient(provider))
    config = ImpactModelConfig(
        model="test-model", call_key="impact-call-1", timeout_seconds=2
    )
    return _loop.run_until_complete(service.analyze(data or input_data(), config))


def test_candidate_uses_only_input_records_and_preserves_call_usage():
    provider = FakeProvider(
        {"candidates": [candidate()], "evidence_status": "SUFFICIENT"}
    )
    data = input_data()
    # The task's validation is optional when the supplied design and plan are sufficient.
    assert data.open_tasks[0].validation is None
    response = invoke(provider, data)

    assert response.result == {
        "candidates": [candidate()],
        "evidence_status": "SUFFICIENT",
    }
    assert response.usage[0].feature == "CHANGE_IMPACT"
    assert response.usage[0].request_key == "impact-call-1"
    assert response.usage[0].project_id == "101"
    assert not response.usage[0].failed
    assert provider.requests[0].response_format == {"type": "json_object"}
    assert provider.requests[0].model_options == {"max_completion_tokens": 1024}
    assert json.loads(provider.requests[0].messages[1].content)["proposal_id"] == "71"


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("task_id", "other-task"),
        ("member_id", "other-member"),
        ("design_item_id", "other-item"),
        ("sources", [{**SOURCE, "document_version_id": "other-version"}]),
        ("reason", " "),
    ],
)
def test_rejects_unprovided_identifiers_and_sources_with_usage(field, value):
    item = deepcopy(candidate())
    item[field] = value
    with pytest.raises(LLMError) as error:
        invoke(FakeProvider({"candidates": [item], "evidence_status": "SUFFICIENT"}))
    assert error.value.code == "invalid_output"
    assert len(error.value.usage) == 1
    assert error.value.usage[0].failed
    assert error.value.usage[0].input_tokens == 12


def test_rejects_member_from_another_task():
    data = input_data().model_dump()
    data["open_tasks"].append(
        {"id": "502", "project_id": "101", "assignee_member_id": "22"}
    )
    item = {**candidate(), "member_id": "22"}
    with pytest.raises(LLMError) as error:
        invoke(
            FakeProvider({"candidates": [item], "evidence_status": "SUFFICIENT"}),
            ImpactInput.model_validate(data),
        )
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed


def test_no_document_source_can_be_returned_as_empty_without_fabricating_one():
    data = input_data().model_dump()
    data["approved_items"][0]["source"] = None
    item = {
        **candidate(),
        "reason": "문서 출처가 없어 담당자 확인이 필요합니다.",
        "sources": [],
    }
    response = invoke(
        FakeProvider({"candidates": [item], "evidence_status": "INSUFFICIENT"}),
        ImpactInput.model_validate(data),
    )
    assert response.result["candidates"][0]["sources"] == []


def test_empty_candidates_are_data_not_a_pass_or_approval():
    response = invoke(FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"}))
    assert response.result == {"candidates": [], "evidence_status": "SUFFICIENT"}
    assert set(response.to_api_dict()) == {"result", "origin", "model", "usage"}


def test_insufficient_evidence_is_not_a_sufficient_empty_result():
    response = invoke(
        FakeProvider({"candidates": [], "evidence_status": "INSUFFICIENT"})
    )
    assert response.result["evidence_status"] == "INSUFFICIENT"


def test_wrong_baseline_is_insufficient_even_if_model_claims_sufficient():
    data = input_data().model_dump()
    data["changes"][0]["base_design_item_version_id"] = "different-version"
    response = invoke(
        FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"}),
        ImpactInput.model_validate(data),
    )
    assert response.result["evidence_status"] == "INSUFFICIENT"


def test_missing_plan_is_insufficient_even_if_model_claims_sufficient():
    data = input_data().model_dump()
    del data["open_tasks"][0]["current_plan_revision"]
    response = invoke(
        FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"}),
        ImpactInput.model_validate(data),
    )
    assert response.result["evidence_status"] == "INSUFFICIENT"


def test_empty_task_subset_is_insufficient_even_if_model_claims_sufficient():
    data = input_data().model_dump()
    data["open_tasks"] = []
    response = invoke(
        FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"}),
        ImpactInput.model_validate(data),
    )
    assert response.result["evidence_status"] == "INSUFFICIENT"


@pytest.mark.parametrize(
    "case",
    [
        "empty_approved",
        "empty_plan",
        "no_revision",
        "empty_sources",
        "metadata_only_plan",
    ],
)
def test_empty_or_uncited_evidence_is_never_sufficient(case):
    data = input_data().model_dump()
    item = candidate()
    if case == "empty_approved":
        data["approved_items"][0]["content"] = {"description": "   "}
    elif case == "empty_plan":
        data["open_tasks"][0]["current_plan_revision"]["plan"] = {"goal": " "}
    elif case == "no_revision":
        del data["open_tasks"][0]["current_plan_revision"]["id"]
    elif case == "metadata_only_plan":
        data["open_tasks"][0]["current_plan_revision"]["plan"] = {
            "no_contract_change": True
        }
    else:
        item["sources"] = []
    response = invoke(
        FakeProvider({"candidates": [item], "evidence_status": "SUFFICIENT"}),
        ImpactInput.model_validate(data),
    )
    assert response.result["evidence_status"] == "INSUFFICIENT"


def test_missing_evidence_status_is_failed_with_usage():
    with pytest.raises(LLMError) as error:
        invoke(FakeProvider({"candidates": []}))
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed


def test_only_validation_ids_supplied_in_task_are_accepted():
    data = input_data().model_dump()
    data["open_tasks"][0]["validation"] = {"id": "601", "status": "PASS"}
    data = ImpactInput.model_validate(data)
    item = {**candidate(), "validation_id": "601"}
    response = invoke(
        FakeProvider({"candidates": [item], "evidence_status": "SUFFICIENT"}), data
    )
    assert response.result["candidates"][0]["validation_id"] == "601"
    item["validation_id"] = "not-supplied"
    with pytest.raises(LLMError) as error:
        invoke(
            FakeProvider({"candidates": [item], "evidence_status": "SUFFICIENT"}), data
        )
    assert error.value.code == "invalid_output"


def test_provider_failure_propagates_common_error_and_usage():
    provider = FakeProvider(failure=ProviderError("model_unavailable"))
    with pytest.raises(LLMError) as error:
        invoke(provider)
    assert error.value.code == "model_unavailable"
    assert len(error.value.usage) == 1
    assert error.value.usage[0].failed


def test_wrong_project_is_rejected_before_model_call():
    data = input_data().model_dump()
    data["open_tasks"][0]["project_id"] = "other-project"
    with pytest.raises(ValidationError):
        ImpactInput.model_validate(data)


@pytest.mark.parametrize("parameter", ["max_completion_tokens", "max_tokens"])
def test_output_token_limit_passed_to_provider(parameter):
    provider = FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"})
    config = ImpactModelConfig(
        model="test-model",
        call_key="impact-call-1",
        timeout_seconds=2,
        output_token_limit=256,
        output_token_parameter=parameter,
    )
    _loop.run_until_complete(
        ImpactAnalysisService(LLMClient(provider)).analyze(input_data(), config)
    )
    assert provider.requests[0].model_options == {parameter: 256}


@pytest.mark.parametrize(
    "limit, parameter",
    [(0, "max_tokens"), (4097, "max_tokens"), (True, "max_tokens"), (10, "unexpected")],
)
def test_invalid_output_token_config_never_calls_provider(limit, parameter):
    provider = FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"})
    config = ImpactModelConfig(
        model="test-model",
        call_key="impact-call-1",
        timeout_seconds=2,
        output_token_limit=limit,
        output_token_parameter=parameter,
    )
    with pytest.raises(LLMError) as error:
        _loop.run_until_complete(
            ImpactAnalysisService(LLMClient(provider)).analyze(input_data(), config)
        )
    assert error.value.code == "invalid_input"
    assert not error.value.usage
    assert not provider.requests


@pytest.mark.parametrize("case", ["bytes", "list"])
def test_direct_service_limits_apply_before_model_call(case):
    data = input_data().model_dump()
    config = ImpactModelConfig(
        model="test-model",
        call_key="impact-call-1",
        timeout_seconds=2,
        max_input_bytes=1024 if case == "bytes" else 262144,
        max_items_per_list=1,
    )
    if case == "bytes":
        data["changes"][0]["proposed_content"]["padding"] = "x" * 1500
    else:
        data["explicit_dependencies"] = [{"id": "1"}, {"id": "2"}]
    provider = FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"})
    service = ImpactAnalysisService(LLMClient(provider))
    with pytest.raises(LLMError) as error:
        _loop.run_until_complete(
            service.analyze(ImpactInput.model_validate(data), config)
        )
    assert error.value.code == "invalid_input"
    assert not error.value.usage
    assert not provider.requests


@pytest.mark.parametrize(
    "field",
    [
        "design_item_id",
        "base_design_item_version_id",
        "schema_version",
        "proposed_content",
    ],
)
def test_update_without_required_field_is_rejected_before_model_call(field):
    data = input_data().model_dump()
    del data["changes"][0][field]
    provider = FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"})
    with pytest.raises(ValidationError):
        ImpactInput.model_validate(data)
    assert not provider.requests


@pytest.mark.parametrize("missing", ["kind", "schema_version", "proposed_content"])
def test_create_missing_contract_fields_is_rejected(missing):
    data = input_data().model_dump()
    change = {
        "operation": "CREATE",
        "kind": "API_CONTRACT",
        "schema_version": 1,
        "proposed_content": {"name": "api"},
    }
    del change[missing]
    data["changes"] = [change]
    with pytest.raises(ValidationError):
        ImpactInput.model_validate(data)


@pytest.mark.parametrize("missing", ["design_item_id", "base_design_item_version_id"])
def test_retire_missing_item_or_base_version_is_rejected(missing):
    data = input_data().model_dump()
    change = {
        "operation": "RETIRE",
        "design_item_id": "301",
        "base_design_item_version_id": "311",
    }
    del change[missing]
    data["changes"] = [change]
    with pytest.raises(ValidationError):
        ImpactInput.model_validate(data)


@pytest.mark.parametrize("schema_version", [0, -1, True, "1"])
def test_schema_version_must_be_positive_integer(schema_version):
    data = input_data().model_dump()
    data["changes"][0]["schema_version"] = schema_version
    with pytest.raises(ValidationError):
        ImpactInput.model_validate(data)


def test_empty_proposed_content_is_invalid_before_model_call():
    data = input_data().model_dump()
    data["changes"][0]["proposed_content"] = {}
    provider = FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"})
    with pytest.raises(ValidationError):
        ImpactInput.model_validate(data)
    assert not provider.requests


@pytest.mark.parametrize(
    "plan",
    [
        {"title": True},
        {"goal": 3},
        {"approach": {}},
        {"included_scope": [True]},
        {"completion_criteria": "done"},
    ],
)
def test_malformed_plan_fields_are_rejected_before_model_call(plan):
    data = input_data().model_dump()
    data["open_tasks"][0]["current_plan_revision"]["plan"] = plan
    provider = FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"})
    with pytest.raises(ValidationError):
        ImpactInput.model_validate(data)
    assert not provider.requests


def test_well_formed_empty_plan_is_insufficient_not_a_false_pass():
    data = input_data().model_dump()
    data["open_tasks"][0]["current_plan_revision"]["plan"] = {
        "goal": " ",
        "included_scope": [],
    }
    response = invoke(
        FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"}),
        ImpactInput.model_validate(data),
    )
    assert response.result["evidence_status"] == "INSUFFICIENT"


def test_strict_schema_is_opt_in_and_preserves_validation():
    provider = FakeProvider(
        {"candidates": [candidate()], "evidence_status": "SUFFICIENT"}
    )
    config = ImpactModelConfig(
        model="test-model",
        call_key="strict-call",
        timeout_seconds=2,
        response_format_mode="json_schema",
    )
    result = _loop.run_until_complete(
        ImpactAnalysisService(LLMClient(provider)).analyze(input_data(), config)
    )
    assert result.result["candidates"][0]["task_id"] == "501"
    response_format = provider.requests[0].response_format
    assert response_format["type"] == "json_schema"
    schema = response_format["json_schema"]
    assert schema["strict"] is True
    assert set(schema["schema"]["required"]) == {"evidence_status", "candidates"}
    candidate_schema = schema["schema"]["properties"]["candidates"]["items"]
    assert set(candidate_schema["required"]) == {
        "task_id",
        "member_id",
        "design_item_id",
        "validation_id",
        "reason",
        "sources",
    }


def test_invalid_format_fails_before_call():
    provider = FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"})
    config = ImpactModelConfig(
        model="test-model",
        call_key="invalid-format",
        timeout_seconds=2,
        response_format_mode="unknown",
    )
    with pytest.raises(LLMError) as error:
        _loop.run_until_complete(
            ImpactAnalysisService(LLMClient(provider)).analyze(input_data(), config)
        )
    assert error.value.code == "invalid_input"
    assert not provider.requests


@pytest.mark.parametrize(
    "extra",
    [
        {"design_item_id": "301"},
        {"base_design_item_version_id": "311"},
        {"design_item_id": "301", "base_design_item_version_id": "311"},
    ],
)
def test_create_with_existing_item_reference_is_rejected(extra):
    data = input_data().model_dump()
    data["changes"] = [
        {
            "operation": "CREATE",
            "kind": "API_CONTRACT",
            "schema_version": 1,
            "proposed_content": {"name": "new"},
            **extra,
        }
    ]
    provider = FakeProvider({"candidates": [], "evidence_status": "SUFFICIENT"})
    with pytest.raises(ValidationError):
        ImpactInput.model_validate(data)
    assert not provider.requests


def test_create_without_existing_item_reference_is_valid():
    data = input_data().model_dump()
    data["changes"] = [
        {
            "operation": "CREATE",
            "kind": "API_CONTRACT",
            "schema_version": 1,
            "proposed_content": {"name": "new"},
        }
    ]
    change = ImpactInput.model_validate(data).changes[0]
    assert change.design_item_id is None
    assert change.base_design_item_version_id is None
