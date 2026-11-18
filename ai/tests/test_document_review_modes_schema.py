"""BE chat/request snapshots extend the existing review contract safely."""

import copy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.schemas.document_review import DocumentReviewRequest


@pytest.fixture
def snapshot():
    fixture = (
        Path(__file__).resolve().parents[2]
        / "backend/src/test/resources/document/review-contract-complete.json"
    )
    return json.loads(fixture.read_text(encoding="utf-8"))["input"]


def suggestion(kind="PATCH"):
    return {
        "id": "12",
        "type": kind,
        "step": "BODY",
        "explanation": "보완이 필요해요",
        "original_text": "로그인한다." if kind == "PATCH" else None,
        "replacement_text": "" if kind == "PATCH" else None,
        "decision": "PENDING" if kind == "PATCH" else None,
    }


def discuss(snapshot):
    return {
        **copy.deepcopy(snapshot),
        "mode": "DISCUSS",
        "suggestion": suggestion(),
        "messages": [
            {"author_kind": "BEE", "text": "기존 응답", "proposed_text": ""},
            {"author_kind": "HUMAN", "text": "어떻게 수정하나요?"},
        ],
    }


def test_existing_review_request_remains_compatible(snapshot):
    body = DocumentReviewRequest.model_validate(snapshot)
    assert body.mode is None
    assert body.suggestion is None and body.messages is None
    assert (
        DocumentReviewRequest.model_validate({**snapshot, "mode": "REVIEW"}).mode
        == "REVIEW"
    )


@pytest.mark.parametrize("kind", ["PATCH", "COMMENT"])
def test_discuss_accepts_be_nullable_suggestion_and_history(snapshot, kind):
    payload = discuss(snapshot)
    payload["suggestion"] = suggestion(kind)
    body = DocumentReviewRequest.model_validate(payload)
    assert body.mode == "DISCUSS"
    assert body.suggestion.type == kind
    assert body.messages[0].proposed_text == ""
    assert body.messages[-1].author_kind == "HUMAN"


def test_request_accepts_be_payload(snapshot):
    body = DocumentReviewRequest.model_validate(
        {**snapshot, "mode": "REQUEST", "request_text": "이 부분을 고쳐 줘"}
    )
    assert body.request_text == "이 부분을 고쳐 줘"


@pytest.mark.parametrize(
    "changes",
    [
        {"mode": "RERUN"},
        {"mode": "DISCUSS"},
        {"mode": "REQUEST"},
        {"mode": "REQUEST", "request_text": "요청", "messages": []},
        {"mode": "REQUEST", "request_text": "요청", "suggestion": suggestion()},
        {"request_text": "모드가 빠짐"},
    ],
)
def test_missing_or_mixed_mode_context_is_rejected(snapshot, changes):
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate({**snapshot, **changes})


def test_discuss_requires_last_human_message_and_limits_history(snapshot):
    payload = discuss(snapshot)
    payload["messages"] = [{"author_kind": "BEE", "text": "응답"}]
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate(payload)
    payload["messages"] = [{"author_kind": "HUMAN", "text": "질문"}] * 40
    assert len(DocumentReviewRequest.model_validate(payload).messages) == 40
    payload["messages"].append({"author_kind": "HUMAN", "text": "질문"})
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate(payload)


def test_request_length_is_java_utf16_units(snapshot):
    payload = {**snapshot, "mode": "REQUEST", "request_text": "🐝" * 2500}
    DocumentReviewRequest.model_validate(payload)
    payload["request_text"] += "a"
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate(payload)


def test_request_blank_matches_java_character_whitespace(snapshot):
    # Java's isBlank intentionally treats no-break spaces as nonblank.
    payload = {**snapshot, "mode": "REQUEST", "request_text": "\u00a0"}
    DocumentReviewRequest.model_validate(payload)
    payload["request_text"] = "\u2003\t"
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate(payload)


def test_human_and_bee_text_limits_match_be(snapshot):
    payload = discuss(snapshot)
    payload["messages"] = [{"author_kind": "HUMAN", "text": "🐝" * 2500}]
    DocumentReviewRequest.model_validate(payload)
    payload["messages"][0]["text"] += "a"
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate(payload)
    payload["messages"] = [
        {"author_kind": "BEE", "text": "🐝" * 5000},
        {"author_kind": "HUMAN", "text": "질문"},
    ]
    DocumentReviewRequest.model_validate(payload)
    payload["messages"][0]["text"] += "a"
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate(payload)


def test_review_snapshot_hash_is_still_enforced_for_chat(snapshot):
    payload = discuss(snapshot)
    payload["content"] += "변조"
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate(payload)


def test_discuss_rejects_unknown_or_invalid_suggestion_fields(snapshot):
    payload = discuss(snapshot)
    payload["suggestion"]["unknown"] = True
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate(payload)
    payload["suggestion"] = suggestion("COMMENT")
    payload["suggestion"]["replacement_text"] = "금지"
    with pytest.raises(ValidationError):
        DocumentReviewRequest.model_validate(payload)
