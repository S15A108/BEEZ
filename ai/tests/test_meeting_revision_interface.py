import pytest

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
        "decisions": [{"id": "6", "text": "팀장만 삭제", "evidence_entry_ids": ["5"]}],
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
