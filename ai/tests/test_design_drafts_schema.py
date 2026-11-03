"""#134 schema invariants: DRAFT only, kind-specific content, mirrored changes."""

import pytest
from pydantic import ValidationError

from app.schemas.design_drafts import (
    AnsweredQuestion,
    DesignDraftRequest,
    DesignDraftResult,
    DesignItemContent,
    DraftChange,
    DraftItem,
    ExistingDesignItem,
    FieldChange,
    check_content_for_kind,
)
from app.schemas.documents import SourceLocator
from app.schemas.planning_questions import (
    ChoiceAnswerFormat,
    DocumentRef,
    EvidenceItem,
    QuestionAnswer,
    ShortAnswerFormat,
    SupplementQuestion,
)

REF = DocumentRef(document_id="301", document_version_id="311", version_no=1)


def evidence(block="blk_a", quote="한도는 30 MB이다.", line=3, **overrides):
    fields = {
        "document_id": "301",
        "document_version_id": "311",
        "version_no": 1,
        "block_id": block,
        "locator": SourceLocator(start_line=line, end_line=line),
        "location": f"L{line}-L{line}",
        "quote": quote,
    }
    fields.update(overrides)
    return EvidenceItem(**fields)


def question(key="q-1", **overrides):
    fields = {
        "question_key": key,
        "candidate_key": key.removeprefix("q-"),
        "rank": 1,
        "question_type": "SHORT_ANSWER",
        "text": "한도 단위는 무엇인가요?",
        "answer_format": ShortAnswerFormat(),
        "origin": "MODEL",
        "basis": "REVIEW_CANDIDATE",
    }
    fields.update(overrides)
    return SupplementQuestion(**fields)


def answered(key="q-1", **overrides):
    fields = {
        "question": question(key),
        "evidence": (evidence(),),
        "status": "ANSWERED",
        "answer": QuestionAnswer(
            question_key=key, answer={"type": "SHORT_ANSWER", "text": "MiB"}
        ),
    }
    fields.update(overrides)
    return AnsweredQuestion(**fields)


def existing(version="1101", **overrides):
    fields = {
        "design_item_version_id": version,
        "design_item_id": "1001",
        "key": f"PLN-{version}",
        "kind": "CONSTRAINT",
        "version_no": 1,
        "status": "APPROVED",
        "content": DesignItemContent(description="한도는 30 MB이다."),
    }
    fields.update(overrides)
    return ExistingDesignItem(**fields)


def draft(**overrides):
    fields = {
        "candidate_key": "draft-1",
        "kind": "CONSTRAINT",
        "content": DesignItemContent(description="한도는 30 MiB이다."),
        "sources": (evidence(),),
        "answer_refs": ("q-1",),
        "support": "DOCUMENT_AND_ANSWER",
        "rationale": "답변이 단위를 확정했습니다.",
    }
    fields.update(overrides)
    return DraftItem(**fields)


def change(**overrides):
    fields = {
        "candidate_key": "draft-1",
        "change": "CREATE",
        "after": DesignItemContent(description="한도는 30 MiB이다."),
        "answer_refs": ("q-1",),
    }
    fields.update(overrides)
    return DraftChange(**fields)


def result(**overrides):
    fields = {
        "document": REF,
        "kind": "PLAN",
        "text_hash": "a" * 64,
        "parser_profile": "markdown-v1",
        "max_items": 50,
        "items": (draft(),),
        "changes": (change(),),
    }
    fields.update(overrides)
    return DesignDraftResult(**fields)


def test_content_required_fields_depend_on_kind():
    check_content_for_kind("REQUIREMENT", DesignItemContent(description="x"))
    with pytest.raises(ValueError):
        check_content_for_kind("API_CONTRACT", DesignItemContent(description="x"))
    check_content_for_kind(
        "API_CONTRACT",
        DesignItemContent(description="x", method="GET", path="/api/projects"),
    )
    with pytest.raises(ValueError):
        check_content_for_kind("DATA_CONTRACT", DesignItemContent(description="x"))
    check_content_for_kind(
        "DATA_CONTRACT",
        DesignItemContent(description="x", entity="account", fields=[{"name": "id"}]),
    )
    with pytest.raises(ValidationError):
        existing(kind="API_CONTRACT")


def test_only_the_kind_that_requires_a_field_constrains_its_type():
    # The BE contract validates kind-specific fields only; a POLICY may carry
    # a free-form `method`, and an existing item like that must be accepted.
    manual = DesignItemContent.model_validate({"description": "x", "method": "manual"})
    check_content_for_kind("POLICY", manual)
    assert existing(kind="POLICY", content=manual).content.extra_fields() == {
        "method": "manual"
    }
    free_entity = DesignItemContent.model_validate({"description": "x", "entity": " "})
    check_content_for_kind("REQUIREMENT", free_entity)
    with pytest.raises(ValueError):
        check_content_for_kind("DATA_CONTRACT", free_entity)


@pytest.mark.parametrize(
    "content",
    [
        {"description": "x", "method": "get", "path": "/a"},
        {"description": "x", "method": "GET", "path": "api"},
        {"description": "x", "method": "GET", "path": "/a b"},
        {"description": "x", "method": "GET", "path": "/a?x=1"},
        {"description": "x", "method": "GET", "path": "/a\x85b"},
        {"description": "x", "method": "GET"},
    ],
)
def test_invalid_api_fields_are_rejected_for_api_contracts(content):
    with pytest.raises(ValueError):
        check_content_for_kind(
            "API_CONTRACT", DesignItemContent.model_validate(content)
        )


@pytest.mark.parametrize(
    "content",
    [
        {"description": "x", "entity": " ", "fields": []},
        {"description": "x", "entity": "account", "fields": "id"},
        {"description": "x", "entity": "account"},
    ],
)
def test_invalid_data_fields_are_rejected_for_data_contracts(content):
    with pytest.raises(ValueError):
        check_content_for_kind(
            "DATA_CONTRACT", DesignItemContent.model_validate(content)
        )


def test_blank_description_is_rejected_for_every_kind():
    with pytest.raises(ValidationError):
        DesignItemContent.model_validate({"description": " "})
    with pytest.raises(ValidationError):
        DesignItemContent.model_validate({"method": "GET", "path": "/a"})


def test_extra_content_fields_are_preserved_null_values_included():
    # Stored content is never rewritten: a null-valued field the BE holds
    # must come back exactly as supplied.
    content = DesignItemContent.model_validate(
        {"description": "x", "note": "kept", "empty": None}
    )
    assert content.model_dump(mode="json") == {
        "description": "x",
        "note": "kept",
        "empty": None,
    }
    assert content.extra_fields() == {"note": "kept", "empty": None}
    with pytest.raises(ValidationError):
        DesignItemContent.model_validate({"description": "x", " ": "blank name"})


def test_content_follows_the_backend_json_rules():
    # DesignJson.canonical rejects blank names at any depth, nesting deeper
    # than 32 levels, non-JSON values and more than 1 MiB; so does this side,
    # or a draft could come back that the BE can never save.
    with pytest.raises(ValidationError):
        DesignItemContent.model_validate(
            {"description": "x", "metadata": {"": "value"}}
        )
    with pytest.raises(ValidationError):
        DesignItemContent.model_validate({"description": "x", "fields": [{" ": 1}]})
    nested = "deep"
    for _ in range(31):
        nested = {"n": nested}
    assert DesignItemContent.model_validate({"description": "x", "n": nested})
    with pytest.raises(ValidationError):
        DesignItemContent.model_validate({"description": "x", "n": {"n": nested}})
    with pytest.raises(ValidationError):
        DesignItemContent(description="x", when=(1, 2))
    with pytest.raises(ValidationError):
        DesignItemContent(description="x", ratio=float("nan"))
    with pytest.raises(ValidationError):
        DesignItemContent(description="x", blob="x" * 1_048_577)
    assert DesignItemContent(description="x", blob="x" * 1_000_000).extra_fields()


def test_merged_drafts_keep_every_reference_and_conflicts_only_on_updates():
    many = tuple(f"q-{index}" for index in range(1, 12))
    assert len(draft(sources=(), answer_refs=many, support="ANSWER").answer_refs) == 11
    with pytest.raises(ValidationError):
        change(conflicting_fields=("path",))
    update = change(
        change="UPDATE",
        base_design_item_version_id="1101",
        before=DesignItemContent(description="한도는 30 MB이다."),
        changed_fields=(
            FieldChange(change="CHANGED", field="description", before="a", after="b"),
        ),
        conflicting_fields=("description",),
    )
    assert update.conflicting_fields == ("description",)
    # Conflicts that net out to the stored value: an update with no field
    # change is allowed then, so the disagreement reaches the reviewer.
    settle = change(
        change="UPDATE",
        base_design_item_version_id="1101",
        before=DesignItemContent(description="한도는 30 MiB이다."),
        conflicting_fields=("description",),
    )
    assert settle.changed_fields == () and settle.after == settle.before
    with pytest.raises(ValidationError):
        FieldChange(change="EDITED", field="description")
    with pytest.raises(ValidationError):
        change(
            change="UPDATE",
            base_design_item_version_id="1101",
            before=DesignItemContent(description="한도는 30 MB이다."),
            changed_fields=(
                FieldChange(
                    change="CHANGED", field="description", before="a", after="b"
                ),
            ),
            conflicting_fields=("a", "a"),
        )


def test_limits_follow_the_backend_content_validator():
    # The BE caps only description (10,000) and path (2,000); entity and
    # field names are any non-blank text, so stored items are never rejected.
    long_entity = DesignItemContent(description="x", entity="e" * 300, fields=[])
    check_content_for_kind("DATA_CONTRACT", long_entity)
    stored = existing(kind="DATA_CONTRACT", content=long_entity)
    assert stored.content.extra_fields()["entity"] == "e" * 300
    assert FieldChange(change="CHANGED", field="f" * 200).field == "f" * 200
    with pytest.raises(ValidationError):
        FieldChange(change="CHANGED", field="")


def test_answered_question_needs_a_fitting_answer():
    with pytest.raises(ValidationError):
        answered(answer=None)
    with pytest.raises(ValidationError):
        answered(status="OPEN")
    with pytest.raises(ValidationError):
        answered(
            answer=QuestionAnswer(
                question_key="q-9", answer={"type": "SHORT_ANSWER", "text": "x"}
            )
        )
    choice = question(
        "q-1",
        question_type="CHOICE",
        answer_format=ChoiceAnswerFormat(options=("MB", "MiB")),
    )
    with pytest.raises(ValidationError):
        answered(
            question=choice,
            answer=QuestionAnswer(
                question_key="q-1", answer={"type": "CHOICE", "selected": ["GB"]}
            ),
        )
    assert answered(status="DISMISSED", answer=None).answer is None


def test_request_rejects_duplicates_and_foreign_evidence():
    base = {
        "project_id": "101",
        "request_key": "r",
        "document": REF,
        "kind": "PLAN",
        "media_type": "text/markdown",
        "content": "# 제목\n",
        "content_hash": "a" * 64,
        "text_hash": "b" * 64,
        "normalization_version": "utf8-lf-v1",
        "parser_profile": "markdown-v1",
    }
    request = DesignDraftRequest(**base)
    assert request.max_items == 50 and request.existing_items == ()
    with pytest.raises(ValidationError):
        DesignDraftRequest(**base, existing_items=(existing(), existing()))
    with pytest.raises(ValidationError):
        DesignDraftRequest(
            **base, existing_items=(existing("1101"), existing("1102", key="PLN-1101"))
        )
    # Two versions of one design item: the BE sends only the current one.
    with pytest.raises(ValidationError):
        DesignDraftRequest(
            **base, existing_items=(existing("1101"), existing("1102", key="PLN-1102"))
        )
    two = DesignDraftRequest(
        **base,
        existing_items=(
            existing("1101"),
            existing("1102", key="PLN-1102", design_item_id="1002"),
        ),
    )
    assert len(two.existing_items) == 2
    with pytest.raises(ValidationError):
        DesignDraftRequest(**base, questions=(answered(), answered()))
    with pytest.raises(ValidationError):
        DesignDraftRequest(
            **base,
            questions=(answered(evidence=(evidence(document_version_id="999"),)),),
        )
    loaded = DesignDraftRequest.model_validate_json(
        request.model_dump_json().replace('"existing_items":[]', '"existing_items":[]')
    )
    assert loaded == request


def test_draft_item_has_no_status_but_draft_and_consistent_support():
    with pytest.raises(ValidationError):
        draft(status="APPROVED")
    with pytest.raises(ValidationError):
        draft(support="DOCUMENT")
    assert draft(sources=(), support="ANSWER").support == "ANSWER"
    assert draft(answer_refs=(), support="DOCUMENT").support == "DOCUMENT"
    assert draft(sources=(), answer_refs=(), support="NONE").support == "NONE"
    with pytest.raises(ValidationError):
        draft(base_design_item_version_id="1101")
    with pytest.raises(ValidationError):
        draft(sources=(evidence(), evidence()))
    with pytest.raises(ValidationError):
        draft(kind="API_CONTRACT")


def test_change_shape_follows_its_kind():
    with pytest.raises(ValidationError):
        change(base_design_item_version_id="1101")
    with pytest.raises(ValidationError):
        change(change="UPDATE", base_design_item_version_id="1101")
    with pytest.raises(ValidationError):
        change(
            change="UPDATE",
            base_design_item_version_id="1101",
            before=DesignItemContent(description="x"),
        )
    update = change(
        change="UPDATE",
        base_design_item_version_id="1101",
        before=DesignItemContent(description="한도는 30 MB이다."),
        changed_fields=(
            FieldChange(
                change="CHANGED",
                field="description",
                before="한도는 30 MB이다.",
                after="x",
            ),
        ),
    )
    assert update.changed_fields[0].field == "description"


def test_result_changes_mirror_items_and_bases_are_unique():
    with pytest.raises(ValidationError):
        result(changes=())
    with pytest.raises(ValidationError):
        result(changes=(change(after=DesignItemContent(description="다름")),))
    with pytest.raises(ValidationError):
        result(changes=(change(answer_refs=()),))
    update_item = draft(base_design_item_version_id="1101", base_status="APPROVED")
    update_change = change(
        change="UPDATE",
        base_design_item_version_id="1101",
        before=DesignItemContent(description="한도는 30 MB이다."),
        changed_fields=(
            FieldChange(change="CHANGED", field="description", before="a", after="b"),
        ),
    )
    ok = result(items=(update_item,), changes=(update_change,))
    assert ok.changes[0].change == "UPDATE"
    with pytest.raises(ValidationError):
        result(
            items=(update_item, draft(candidate_key="draft-2")),
            changes=(update_change, change(candidate_key="draft-2")),
            untouched_existing_ids=("1101",),
        )
    second = draft(
        candidate_key="draft-2",
        base_design_item_version_id="1101",
        base_status="APPROVED",
    )
    with pytest.raises(ValidationError):
        result(
            items=(update_item, second),
            changes=(
                update_change,
                update_change.model_copy(update={"candidate_key": "draft-2"}),
            ),
        )
    with pytest.raises(ValidationError):
        result(items=(draft(), draft()), changes=(change(), change()))


def test_result_round_trips_through_json():
    original = result()
    assert DesignDraftResult.model_validate_json(original.model_dump_json()) == (
        original
    )
