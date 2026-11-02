"""The AI-A08 envelope preserves BE snapshots without inventing input facts."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from app.schemas.meeting_analysis import MeetingAnalysisRequest, MeetingAnalysisResult


def request():
    return {
        "project_id": "101",
        "request_key": "meeting-123",
        "mode": "MINUTES",
        "entries": [
            {
                "id": "911",
                "meeting_id": "901",
                "kind": "TEXT",
                "text": "  기존 오류 응답을 유지합니다.\n",
                "author_account_id": "11",
                "created_at": "2026-10-23T03:00:00Z",
            }
        ],
        "approved_items": [
            {
                "id": "311",
                "design_item_id": "301",
                "key": "API-001",
                "kind": "API_CONTRACT",
                "version_no": 1,
                "status": "APPROVED",
                "schema_version": 1,
                "content": {"method": "POST", "path": "/api/auth/login"},
                "source": {
                    "document_id": "201",
                    "document_version_id": "211",
                    "version_no": 1,
                    "location": " lines 3-5 ",
                },
            }
        ],
    }


def test_accepts_current_document_example_and_preserves_source_and_text():
    body = request()
    parsed = MeetingAnalysisRequest.model_validate(body)
    assert parsed.entries[0].author_kind is None
    assert parsed.entries[0].text == body["entries"][0]["text"]
    assert parsed.approved_items[0].source.location == " lines 3-5 "
    assert parsed.model_dump(exclude_none=True) == body


def test_accepts_be_entry_metadata_and_unlinked_source():
    body = request()
    body["entries"][0].update(
        {
            "author_kind": "HUMAN",
            "author_member_id": "21",
            "author_name": "홍길동",
            "refs": [{"type": "TASK", "id": "51", "label": "로그인"}],
        }
    )
    body["approved_items"][0].pop("source")
    assert (
        MeetingAnalysisRequest.model_validate(body).entries[0].author_member_id == "21"
    )


@pytest.mark.parametrize("location", ["", "  ", "\t"])
def test_accepts_unspecified_input_location_and_preserves_document_identity(location):
    body = request()
    body["approved_items"][0]["source"]["location"] = location
    source = MeetingAnalysisRequest.model_validate(body).approved_items[0].source
    assert source.document_id == "201"
    assert source.document_version_id == "211"
    assert source.version_no == 1
    assert source.location == location


@pytest.mark.parametrize("change", ["duplicate", "other_meeting", "no_entries"])
def test_ambiguous_or_empty_meeting_snapshots_are_rejected(change):
    body = request()
    if change == "no_entries":
        body["entries"] = []
    else:
        entry = deepcopy(body["entries"][0])
        if change == "other_meeting":
            entry.update(id="912", meeting_id="902")
        body["entries"].append(entry)
    with pytest.raises(ValidationError):
        MeetingAnalysisRequest.model_validate(body)


def test_unsupported_summary_is_business_validation_failure():
    body = request()
    body["mode"] = "SUMMARY"
    with pytest.raises(ValidationError) as caught:
        MeetingAnalysisRequest.model_validate(body)
    assert caught.value.errors()[0]["type"] == "value_error"


def test_draft_design_or_non_json_proposal_is_rejected():
    body = request()
    body["approved_items"][0]["status"] = "DRAFT"
    with pytest.raises(ValidationError):
        MeetingAnalysisRequest.model_validate(body)
    body = request()
    body["change_proposal"] = {"bad": float("nan")}
    with pytest.raises(ValidationError):
        MeetingAnalysisRequest.model_validate(body)


def test_output_is_structured_and_unknown_fields_are_rejected():
    body = {
        "text": "오류 응답 구조를 유지하기로 했다.",
        "decisions": ["기존 구조 유지"],
        "unresolved": [],
        "action_items": [{"text": "설명 보완", "member_id": "21"}],
        "sources": [request()["approved_items"][0]["source"]],
    }
    parsed = MeetingAnalysisResult.model_validate(body)
    assert parsed.action_items[0].member_id == "21"
    assert parsed.sources[0].location == " lines 3-5 "
    body["approved"] = True
    with pytest.raises(ValidationError):
        MeetingAnalysisResult.model_validate(body)


@pytest.mark.parametrize("location", ["", "  ", "\t"])
def test_unspecified_location_cannot_be_returned_as_citation(location):
    source = {**request()["approved_items"][0]["source"], "location": location}
    with pytest.raises(ValidationError):
        MeetingAnalysisResult.model_validate(
            {
                "text": "추가 협의 필요",
                "decisions": [],
                "unresolved": [],
                "action_items": [],
                "sources": [source],
            }
        )


def test_action_item_does_not_guess_a_responsible_member():
    result = MeetingAnalysisResult.model_validate(
        {
            "text": "추가 협의 필요",
            "decisions": [],
            "unresolved": ["담당자 미정"],
            "action_items": [{"text": "담당자 확인"}],
            "sources": [],
        }
    )
    assert result.action_items[0].member_id is None
