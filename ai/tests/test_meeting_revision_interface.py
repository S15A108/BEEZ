import pytest
from pydantic import ValidationError

from app.schemas.documents import SourceInput
from app.services.document_parsing.normalization import text_digest
from app.services.document_parsing.pipeline import parse_source
from app.services.meeting_revision_interface import prepare_meeting_revision


def fixture():
    text = "첫 문단\n\n기존 권한\n"
    digest = text_digest(text)
    source = SourceInput(
        project_id="1",
        document_version_id="2",
        kind="PLAN",
        media_type="text/plain",
        content=text,
        content_hash=digest,
        text_hash=digest,
        normalization_version="utf8-lf-v1",
        parser_profile="plain-v1",
    )
    return {
        "project_id": "1",
        "meeting_id": "3",
        "document_id": "4",
        "document_version_id": "2",
        "source": source.model_dump(),
        "entries": [{"id": "5", "meeting_id": "3"}],
        "decisions": [
            {
                "id": "summary:951:decision:0",
                "text": "팀장만 삭제",
                "evidence_entry_ids": ["5"],
            }
        ],
        "selected_block_ids": [parse_source(source).blocks[1].block_id],
        "instruction": "권한 수정",
        "request_key": "req-138",
    }


def test_conversion_preserves_decision_links_and_selected_scope():
    data = fixture()
    result = prepare_meeting_revision(data)
    assert result["meeting"]["decisions"] == data["decisions"]
    assert result["request"]["agreements"] == ["팀장만 삭제"]
    assert result["request"]["selected_block_ids"] == data["selected_block_ids"]
    assert result["request"]["source"] == data["source"]


@pytest.mark.parametrize(
    "decision, missing_field",
    [
        ({"text": "팀장만 삭제", "decided_by_member_id": "8"}, "id"),
        ({"id": "summary:951:decision:0", "text": "팀장만 삭제"}, "evidence_entry_ids"),
    ],
)
def test_raw_summary_decision_requires_confirmed_identity_and_evidence(
    decision, missing_field
):
    data = fixture()
    data["decisions"] = [decision]
    with pytest.raises(ValidationError) as exc:
        prepare_meeting_revision(data)
    assert any(
        error["loc"] == ("decisions", 0, missing_field) and error["type"] == "missing"
        for error in exc.value.errors()
    )


def test_document_change_evidence_is_not_used_for_a_decision():
    data = fixture()
    data["decisions"][0].pop("evidence_entry_ids")
    data["document_changes"] = [{"evidence_entry_ids": ["5"]}]
    with pytest.raises(ValidationError) as exc:
        prepare_meeting_revision(data)
    errors = {(error["loc"], error["type"]) for error in exc.value.errors()}
    assert (("decisions", 0, "evidence_entry_ids"), "missing") in errors
    assert (("document_changes",), "extra_forbidden") in errors


def test_missing_confirmed_evidence_holds_the_request():
    data = fixture()
    data["decisions"][0]["evidence_entry_ids"] = []
    with pytest.raises(ValidationError) as exc:
        prepare_meeting_revision(data)
    assert any(
        error["loc"] == ("decisions", 0, "evidence_entry_ids")
        and error["type"] == "too_short"
        for error in exc.value.errors()
    )


def test_snapshot_scoped_decision_id_is_preserved_verbatim():
    original = fixture()
    revised = fixture()
    revised["decisions"][0]["id"] = "summary:952:decision:0"
    original_id = prepare_meeting_revision(original)["meeting"]["decisions"][0]["id"]
    revised_id = prepare_meeting_revision(revised)["meeting"]["decisions"][0]["id"]
    assert original_id == "summary:951:decision:0"
    assert revised_id == "summary:952:decision:0"


@pytest.mark.parametrize(
    "variant",
    [
        "foreign_entry",
        "missing_evidence",
        "duplicate_decision",
        "stale_version",
        "stale_block",
        "empty_scope",
        "foreign_project",
    ],
)
def test_rejects_inconsistent_inputs(variant):
    data = fixture()
    if variant == "foreign_entry":
        data["entries"][0]["meeting_id"] = "99"
    if variant == "missing_evidence":
        data["decisions"][0]["evidence_entry_ids"] = ["99"]
    if variant == "duplicate_decision":
        data["decisions"].append(data["decisions"][0])
    if variant == "stale_version":
        data["document_version_id"] = "99"
    if variant == "stale_block":
        data["selected_block_ids"] = ["blk_" + "0" * 64]
    if variant == "empty_scope":
        data["selected_block_ids"] = []
    if variant == "foreign_project":
        data["project_id"] = "99"
    with pytest.raises(ValueError):
        prepare_meeting_revision(data)
