import json

import pytest

from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMResult,
    LLMUsage,
    ProviderError,
    ProviderResponse,
)
from app.services.retrieval.context_selection import (
    ApprovedEvidence,
    ApprovedItem,
    DesignRelation,
    DirectMatchTruncation,
    SelectedItem,
    SelectionInput,
    SelectionProposal,
    SelectionResult,
    _selection_binding,
    select_context,
)
from app.services.retrieval.context_unresolved import (
    LLMUnresolvedAssessor,
    review_unresolved,
)


def snapshot():
    return SelectionInput(
        project_id="p",
        baseline_id="b",
        request="업로드 제한 확인",
        task_id=None,
        document_version_ids=("d1", "d2"),
        items=(
            ApprovedItem(
                design_item_id="i1",
                design_item_version_id="v1",
                project_id="p",
                baseline_id="b",
                status="APPROVED",
                text="업로드 한도는 30 MB입니다.",
            ),
        ),
        evidence=(
            ApprovedEvidence(
                evidence_id="e1",
                document_version_id="d1",
                project_id="p",
                baseline_id="b",
                text="최대 20 MB입니다.",
            ),
            ApprovedEvidence(
                evidence_id="e2",
                document_version_id="d2",
                project_id="p",
                baseline_id="b",
                text="내부 전용 원문",
            ),
        ),
        relations=(),
        task_design_refs=(),
        search_status="COMPLETE",
    )


def selection():
    selected = SelectionResult(
        design_items=(SelectedItem(design_item_version_id="v1", reasons=("MATCHED",)),),
        evidence_ids=("e1",),
        unresolved=(),
    )
    return bind(snapshot(), selected)


def bind(data, selected):
    return selected.model_copy(
        update={
            "snapshot_binding": _selection_binding(
                data,
                selected.design_items,
                selected.evidence_ids,
                selected.unresolved,
                selected.direct_match_truncation,
            )
        }
    )


class Assessor:
    def __init__(self, result):
        self.result = result
        self.input = None

    async def assess(self, request):
        self.input = request
        usage = LLMUsage(
            request_key="call",
            feature="BEE_CHAT",
            project_id="p",
            model="fake",
            latency_ms=1,
            failed=False,
            tokens_known=True,
            input_tokens=10,
            output_tokens=5,
        )
        return LLMResult(result=self.result, model="fake", usage=(usage,))


def conflict():
    return {
        "kind": "POSSIBLE_CONFLICT",
        "description": "업로드 한도가 서로 다를 수 있습니다.",
        "references": [
            {"kind": "design_item", "id": "v1", "quote": "30 MB"},
            {"kind": "evidence", "id": "e1", "quote": "20 MB"},
        ],
    }


@pytest.mark.asyncio
async def test_conflict_is_a_candidate_with_selected_exact_citations_and_usage():
    assessor = Assessor({"candidates": [conflict()]})
    result = await review_unresolved(snapshot(), selection(), assessor)
    assert result.result["scope"] == "selected_context_only"
    assert result.result["candidates"][0]["kind"] == "POSSIBLE_CONFLICT"
    assert result.usage[0].request_key == "call" and not result.usage[0].failed
    assert [e.evidence_id for e in assessor.input.evidence] == ["e1"]


@pytest.mark.parametrize(
    "mutation",
    ["foreign", "fabricated_quote", "single_source", "blank", "unknown_kind"],
)
@pytest.mark.asyncio
async def test_rejects_unselected_forged_or_unverifiable_conflicts(mutation):
    candidate = conflict()
    if mutation == "foreign":
        candidate["references"][1] = {
            "kind": "evidence",
            "id": "e2",
            "quote": "내부 전용",
        }
    elif mutation == "fabricated_quote":
        candidate["references"][1]["quote"] = "200 MB"
    elif mutation == "single_source":
        candidate["references"].pop()
    elif mutation == "blank":
        candidate["description"] = "  "
    else:
        candidate["kind"] = "CONFIRMED_CONFLICT"
    with pytest.raises(LLMError) as error:
        await review_unresolved(
            snapshot(), selection(), Assessor({"candidates": [candidate]})
        )
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed
    assert error.value.usage[0].input_tokens == 10


@pytest.mark.asyncio
async def test_insufficient_evidence_is_not_a_claim_that_design_is_absent():
    candidate = {
        "kind": "INSUFFICIENT_EVIDENCE",
        "description": "보관 기간 근거가 부족합니다.",
        "references": [],
    }
    result = await review_unresolved(
        snapshot(), selection(), Assessor({"candidates": [candidate]})
    )
    assert result.result["candidates"][0]["kind"] == "INSUFFICIENT_EVIDENCE"


@pytest.mark.asyncio
async def test_rejects_unknown_selection_before_calling_assessor():
    selected = SelectionResult(
        design_items=(), evidence_ids=("unknown",), unresolved=()
    )
    selected = bind(snapshot(), selected)
    assessor = Assessor({"candidates": []})
    with pytest.raises(ValueError):
        await review_unresolved(snapshot(), selected, assessor)
    assert assessor.input is None


@pytest.mark.asyncio
async def test_failed_search_does_not_call_assessor_or_become_no_candidates():
    data = snapshot().model_copy(update={"search_status": "FAILED"})
    assessor = Assessor({"candidates": []})
    with pytest.raises(ValueError):
        await review_unresolved(data, selection(), assessor)
    assert assessor.input is None


@pytest.mark.asyncio
async def test_no_match_remains_visible_even_when_review_returns_no_candidates():
    selected = SelectionResult(
        design_items=(), evidence_ids=(), unresolved=("NO_MATCH",)
    )
    selected = bind(snapshot(), selected)
    result = await review_unresolved(snapshot(), selected, Assessor({"candidates": []}))
    assert result.result == {
        "scope": "selected_context_only",
        "selection_unresolved": ["NO_MATCH"],
        "candidates": [],
    }


@pytest.mark.asyncio
async def test_assessor_failure_is_not_returned_as_success():
    class Failure:
        async def assess(self, request):
            raise LLMError("model_unavailable")

    with pytest.raises(LLMError) as error:
        await review_unresolved(snapshot(), selection(), Failure())
    assert error.value.code == "model_unavailable"


@pytest.mark.asyncio
async def test_duplicate_selected_ids_are_rejected_before_assessment():
    selected = SelectionResult(
        design_items=(), evidence_ids=("e1", "e1"), unresolved=()
    )
    selected = bind(snapshot(), selected)
    assessor = Assessor({"candidates": []})
    with pytest.raises(ValueError):
        await review_unresolved(snapshot(), selected, assessor)
    assert assessor.input is None


class FakeModel:
    def __init__(self, value):
        self.value = value
        self.calls = []

    async def complete(self, request, *, timeout_seconds):
        self.calls.append(request)
        return ProviderResponse(
            content=json.dumps(self.value, ensure_ascii=False),
            model=request.model,
            input_tokens=12,
            output_tokens=4,
        )


@pytest.mark.asyncio
async def test_real_client_adapter_passes_only_selected_context_and_preserves_usage():
    provider = FakeModel({"candidates": [conflict()]})
    # BEE_CHAT is a test-only value; the #131 production feature is not agreed.
    context = LLMContext(
        project_id="p", task_id=None, feature="BEE_CHAT", request_key="req"
    )
    assessor = LLMUnresolvedAssessor(
        LLMClient(provider),
        context=context,
        model="fake",
        call_key="call",
        timeout_seconds=3,
    )
    result = await review_unresolved(snapshot(), selection(), assessor)
    assert result.result["candidates"][0]["kind"] == "POSSIBLE_CONFLICT"
    assert result.usage[0].input_tokens == 12 and not result.usage[0].failed
    assert len(provider.calls) == 1
    prompt = provider.calls[0].messages
    assert prompt[0].role == "system" and "untrusted" in prompt[0].content.lower()
    data = json.loads(prompt[1].content)
    assert [source["id"] for source in data["evidence"]] == ["e1"]
    assert "내부 전용 원문" not in prompt[1].content
    assert provider.calls[0].context == context
    assert provider.calls[0].response_format == {"type": "json_object"}
    assert provider.calls[0].model_options == {"max_completion_tokens": 1024}


@pytest.mark.parametrize("scope", ["project", "task"])
@pytest.mark.asyncio
async def test_model_adapter_refuses_wrong_context_before_provider_call(scope):
    provider = FakeModel({"candidates": []})
    context = LLMContext(
        project_id="other" if scope == "project" else "p",
        task_id="other" if scope == "task" else None,
        feature="BEE_CHAT",
        request_key="req",
    )
    assessor = LLMUnresolvedAssessor(
        LLMClient(provider),
        context=context,
        model="fake",
        call_key="call",
        timeout_seconds=3,
    )
    with pytest.raises(LLMError) as error:
        await review_unresolved(snapshot(), selection(), assessor)
    assert error.value.code == "invalid_input"
    assert not provider.calls


@pytest.mark.asyncio
async def test_real_client_adapter_rejects_forged_model_output_and_keeps_usage():
    candidate = conflict()
    candidate["references"][1]["id"] = "e2"
    provider = FakeModel({"candidates": [candidate]})
    context = LLMContext(
        project_id="p", task_id=None, feature="BEE_CHAT", request_key="req"
    )
    assessor = LLMUnresolvedAssessor(
        LLMClient(provider),
        context=context,
        model="fake",
        call_key="call",
        timeout_seconds=3,
    )
    with pytest.raises(LLMError) as error:
        await review_unresolved(snapshot(), selection(), assessor)
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed and error.value.usage[0].input_tokens == 12


@pytest.mark.asyncio
async def test_real_client_adapter_preserves_failed_call_usage():
    class FailedModel:
        async def complete(self, request, *, timeout_seconds):
            raise ProviderError(
                "model_unavailable",
                model=request.model,
                input_tokens=7,
                output_tokens=0,
            )

    context = LLMContext(
        project_id="p", task_id=None, feature="BEE_CHAT", request_key="req"
    )
    assessor = LLMUnresolvedAssessor(
        LLMClient(FailedModel()),
        context=context,
        model="fake",
        call_key="call",
        timeout_seconds=3,
    )
    with pytest.raises(LLMError) as error:
        await review_unresolved(snapshot(), selection(), assessor)
    assert error.value.code == "model_unavailable"
    assert len(error.value.usage) == 1 and error.value.usage[0].failed
    assert error.value.usage[0].input_tokens == 7


@pytest.mark.parametrize("parameter", ["model", "stream", "unknown"])
@pytest.mark.asyncio
async def test_invalid_output_parameter_is_rejected_without_model_call(parameter):
    provider = FakeModel({"candidates": []})
    context = LLMContext(
        project_id="p", task_id=None, feature="BEE_CHAT", request_key="req"
    )
    assessor = LLMUnresolvedAssessor(
        LLMClient(provider),
        context=context,
        model="fake",
        call_key="call",
        timeout_seconds=3,
        output_token_parameter=parameter,
    )
    with pytest.raises(LLMError) as error:
        await review_unresolved(snapshot(), selection(), assessor)
    assert error.value.code == "invalid_input" and not provider.calls


@pytest.mark.parametrize("changed", ["request", "task", "baseline", "text"])
@pytest.mark.asyncio
async def test_selection_from_another_snapshot_is_rejected_before_assessment(changed):
    class Selector:
        async def select(self, request):
            return SelectionProposal(
                design_item_version_ids=("v1",), evidence_ids=("e1",)
            )

    original = snapshot()
    selected = await select_context(original, Selector())
    payload = original.model_dump(mode="json")
    if changed == "request":
        payload["request"] = "다른 요청"
    elif changed == "task":
        payload["task_id"] = "new-task"
    elif changed == "baseline":
        payload["baseline_id"] = "new-baseline"
        for item in payload["items"] + payload["evidence"]:
            item["baseline_id"] = "new-baseline"
    else:
        payload["items"][0]["text"] = "업로드 한도는 100 MB입니다."
    changed_snapshot = SelectionInput.model_validate_json(
        json.dumps(payload, ensure_ascii=False)
    )
    assessor = Assessor({"candidates": []})
    with pytest.raises(ValueError, match="selection snapshot"):
        await review_unresolved(changed_snapshot, selected, assessor)
    assert assessor.input is None


@pytest.mark.asyncio
async def test_serialized_selection_cannot_be_used_as_snapshot_proof():
    class Selector:
        async def select(self, request):
            return SelectionProposal(
                design_item_version_ids=("v1",), evidence_ids=("e1",)
            )

    original = snapshot()
    selected = await select_context(original, Selector())
    exported = selected.model_dump(mode="json")
    assert "snapshot_binding" not in exported
    restored = SelectionResult.model_validate_json(json.dumps(exported))
    assessor = Assessor({"candidates": []})
    with pytest.raises(ValueError, match="selection snapshot"):
        await review_unresolved(original, restored, assessor)
    assert assessor.input is None


@pytest.mark.asyncio
async def test_changed_selected_ids_cannot_reuse_old_binding():
    class Selector:
        async def select(self, request):
            return SelectionProposal(
                design_item_version_ids=("v1",), evidence_ids=("e1",)
            )

    original = snapshot()
    selected = await select_context(original, Selector())
    changed = selected.model_copy(update={"evidence_ids": ("e2",)})
    assessor = Assessor({"candidates": []})
    with pytest.raises(ValueError, match="selection snapshot"):
        await review_unresolved(original, changed, assessor)
    assert assessor.input is None


@pytest.mark.asyncio
async def test_changed_direct_truncation_cannot_reuse_old_binding():
    class Selector:
        async def select(self, request):
            return SelectionProposal(
                design_item_version_ids=("v1",),
                evidence_ids=("e1",),
                direct_match_truncation=DirectMatchTruncation(items=0, evidence=1),
            )

    original = snapshot()
    selected = await select_context(original, Selector())
    valid = await review_unresolved(original, selected, Assessor({"candidates": []}))
    assert valid.result["candidates"] == []
    changed = selected.model_copy(
        update={"direct_match_truncation": DirectMatchTruncation(items=0, evidence=0)}
    )
    assessor = Assessor({"candidates": []})
    with pytest.raises(ValueError, match="selection snapshot"):
        await review_unresolved(original, changed, assessor)
    assert assessor.input is None


@pytest.mark.asyncio
async def test_unverified_dependency_is_preserved_and_cannot_be_removed_before_review():
    original = snapshot().model_copy(
        update={
            "relations": (
                DesignRelation(
                    from_design_item_id="i1",
                    from_design_item_version_id="v1",
                    to_design_item_id="private-missing",
                    to_design_item_version_id="v2",
                    relation="DEPENDS_ON",
                    origin="EXPLICIT",
                    status="CONFIRMED",
                ),
            )
        }
    )

    class Selector:
        async def select(self, request):
            return SelectionProposal(
                design_item_version_ids=("v1",), evidence_ids=("e1",)
            )

    selected = await select_context(original, Selector())
    assert selected.unresolved == ("DEPENDENCY_UNVERIFIED",)
    checked = await review_unresolved(original, selected, Assessor({"candidates": []}))
    assert checked.result["selection_unresolved"] == ["DEPENDENCY_UNVERIFIED"]
    assert "private-missing" not in json.dumps(checked.result)
    tampered = selected.model_copy(update={"unresolved": ()})
    assessor = Assessor({"candidates": []})
    with pytest.raises(ValueError, match="selection snapshot"):
        await review_unresolved(original, tampered, assessor)
    assert assessor.input is None


@pytest.mark.parametrize("binding", [None, "가" * 64, "?" * 64, 42])
@pytest.mark.asyncio
async def test_invalid_snapshot_binding_is_rejected_before_assessment(binding):
    class Selector:
        async def select(self, request):
            return SelectionProposal(
                design_item_version_ids=("v1",), evidence_ids=("e1",)
            )

    original = snapshot()
    selected = await select_context(original, Selector())
    changed = selected.model_copy(update={"snapshot_binding": binding})
    assessor = Assessor({"candidates": []})
    with pytest.raises(ValueError, match="selection snapshot"):
        await review_unresolved(original, changed, assessor)
    assert assessor.input is None
