"""Representative scoped revisions, without a network or persistence."""

import asyncio
import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.routers.document_review import get_review_client
from app.routers.document_revision import router
from app.schemas.documents import SourceInput
from app.services.document_parsing.normalization import text_digest
from app.services.document_parsing.pipeline import parse_source
from app.services.document_revision import RevisionRequest, generate_revision
from app.services.llm_client import LLMClient, LLMError, ProviderResponse


class Provider:
    def __init__(self, output):
        self.output = output
        self.calls = 0

    async def complete(self, request, *, timeout_seconds):
        self.calls += 1
        return ProviderResponse(
            json.dumps(self.output, ensure_ascii=False), request.model, 10, 5
        )


def source(version="21"):
    text = "첫 문단은 그대로 둡니다.\n\n삭제 권한은 모든 사용자에게 있습니다.\n\n마지막 문단입니다.\n"
    digest = text_digest(text)
    return SourceInput(
        project_id="1",
        document_version_id=version,
        kind="PLAN",
        media_type="text/plain",
        content=text,
        content_hash=digest,
        text_hash=digest,
        normalization_version="utf8-lf-v1",
        parser_profile="plain-v1",
    )


def make_request():
    original = source()
    blocks = parse_source(original).blocks
    target = next(block for block in blocks if block.locator.start_line == 3)
    body = RevisionRequest(
        source=original,
        expected_document_version_id="21",
        selected_block_ids=[target.block_id],
        instruction="삭제 권한을 팀장으로 제한",
        agreements=["삭제 권한은 팀장만 가진다"],
        request_key="test-137",
    )
    return body, target.block_id


def run(body, output):
    provider = Provider(output)
    result = asyncio.run(generate_revision(body, LLMClient(provider), "test-model"))
    return result, provider


def test_selected_paragraph_changes_and_other_text_remains_identical():
    body, selected = make_request()
    result, provider = run(
        body,
        {
            "changes": [
                {
                    "block_id": selected,
                    "replacement": "삭제 권한은 팀장에게만 있습니다.\n",
                    "reason": "합의 반영",
                    "evidence": ["삭제 권한은 팀장만 가진다"],
                }
            ],
            "unresolved": [],
        },
    )
    assert result["result"]["after_content"] == body.source.content.replace(
        "삭제 권한은 모든 사용자에게 있습니다.", "삭제 권한은 팀장에게만 있습니다."
    )
    change = result["result"]["changes"][0]
    assert change["before"] == "삭제 권한은 모든 사용자에게 있습니다.\n"
    assert change["evidence"] == ["삭제 권한은 팀장만 가진다"]
    assert result["usage"][0]["feature"] == "BEE_DRAFT"
    assert provider.calls == 1


def test_missing_scope_and_version_mismatch_are_rejected():
    body, _ = make_request()
    with pytest.raises(ValidationError):
        RevisionRequest.model_validate({**body.model_dump(), "selected_block_ids": []})
    with pytest.raises(ValidationError):
        RevisionRequest.model_validate(
            {**body.model_dump(), "expected_document_version_id": "22"}
        )


def test_stale_block_rejected_before_model_call():
    body, _ = make_request()
    stale = parse_source(source("22")).blocks[0].block_id
    changed = body.model_copy(update={"selected_block_ids": [stale]})
    provider = Provider({"changes": [], "unresolved": []})
    with pytest.raises(ValueError):
        asyncio.run(generate_revision(changed, LLMClient(provider), "test-model"))
    assert provider.calls == 0


@pytest.mark.parametrize(
    "variant", ["unselected", "duplicate", "evidence", "boundary", "control"]
)
def test_invalid_model_output_is_rejected(variant):
    body, selected = make_request()
    other = next(
        b.block_id for b in parse_source(body.source).blocks if b.block_id != selected
    )
    change = {
        "block_id": selected,
        "replacement": "팀장만 삭제할 수 있습니다.\n",
        "reason": "합의 반영",
        "evidence": [],
    }
    if variant == "unselected":
        change["block_id"] = other
    elif variant == "evidence":
        change["evidence"] = ["없는 근거"]
    elif variant == "boundary":
        change["replacement"] = "줄바꿈을 없앰"
    elif variant == "control":
        change["replacement"] = "허용되지 않은 제어문자\x01\n"
    changes = [change, change] if variant == "duplicate" else [change]
    with pytest.raises(LLMError) as error:
        run(body, {"changes": changes, "unresolved": []})
    assert error.value.usage[-1].failed


def test_unresolved_keeps_source_unchanged():
    body, _ = make_request()
    result, _ = run(
        body, {"changes": [], "unresolved": ["선택 범위에서 요구사항을 확인할 수 없음"]}
    )
    assert result["result"]["after_content"] == body.source.content


def test_repeated_long_request_keys_have_distinct_usage_call_keys():
    body, _ = make_request()
    body = body.model_copy(update={"request_key": "x" * 128})
    output = {"changes": [], "unresolved": ["검토 필요"]}
    first, _ = run(body, output)
    second, _ = run(body, output)
    assert first["usage"][0]["request_key"] != second["usage"][0]["request_key"]
    assert len(first["usage"][0]["request_key"]) <= 128


def markdown_request(text, line=1):
    digest = text_digest(text)
    original = SourceInput(
        project_id="1",
        document_version_id="21",
        kind="PLAN",
        media_type="text/markdown",
        content=text,
        content_hash=digest,
        text_hash=digest,
        normalization_version="utf8-lf-v1",
        parser_profile="markdown-v1",
    )
    target = next(
        b for b in parse_source(original).blocks if b.locator.start_line == line
    )
    return RevisionRequest(
        source=original,
        expected_document_version_id="21",
        selected_block_ids=[target.block_id],
        instruction="수정",
        request_key="md-test",
    ), target.block_id


@pytest.mark.parametrize(
    ("text", "replacement"),
    [
        ("```py\nprint(1)\n```\n\n다음 문단\n", "```py\nprint(2)\n"),
        ("첫 문단\n\n다음 문단\n", "# 바뀐 제목\n"),
        ("# 원래 제목\n\n다음 문단\n", "# 바뀐 제목\n"),
    ],
)
def test_markdown_change_cannot_alter_unselected_structure(text, replacement):
    body, selected = markdown_request(text)
    with pytest.raises(LLMError):
        run(
            body,
            {
                "changes": [
                    {
                        "block_id": selected,
                        "replacement": replacement,
                        "reason": "수정",
                        "evidence": [],
                    }
                ],
                "unresolved": [],
            },
        )


def test_markdown_paragraph_change_preserving_other_structure_is_allowed():
    body, selected = markdown_request("첫 문단\n\n다음 문단\n")
    result, _ = run(
        body,
        {
            "changes": [
                {
                    "block_id": selected,
                    "replacement": "수정 문단\n",
                    "reason": "수정",
                    "evidence": [],
                }
            ],
            "unresolved": [],
        },
    )
    assert result["result"]["after_content"] == "수정 문단\n\n다음 문단\n"


def test_markdown_ordered_list_start_cannot_change_unselected_numbering():
    body, selected = markdown_request("1. 첫 항목\n2. 다음 항목\n")
    with pytest.raises(LLMError):
        run(
            body,
            {
                "changes": [
                    {
                        "block_id": selected,
                        "replacement": "8. 첫 항목\n",
                        "reason": "수정",
                        "evidence": [],
                    }
                ],
                "unresolved": [],
            },
        )


def test_markdown_reference_cannot_change_unselected_link_destination():
    body, selected = markdown_request("[site]: https://example.com\n\n[링크][site]\n")
    with pytest.raises(LLMError):
        run(
            body,
            {
                "changes": [
                    {
                        "block_id": selected,
                        "replacement": "[site]: https://other.example\n",
                        "reason": "수정",
                        "evidence": [],
                    }
                ],
                "unresolved": [],
            },
        )


def test_unused_reference_can_change_without_affecting_unselected_text():
    text = "[site]: https://example.com\n\n다른 문단\n"
    body, selected = markdown_request(text)
    result, _ = run(
        body,
        {
            "changes": [
                {
                    "block_id": selected,
                    "replacement": "[site]: https://other.example\n",
                    "reason": "수정",
                    "evidence": [],
                }
            ],
            "unresolved": [],
        },
    )
    assert result["result"]["after_content"] == text.replace(
        "https://example.com", "https://other.example"
    )


def test_new_reference_cannot_turn_unselected_text_into_link():
    body, selected = markdown_request("선택 문단\n\n[site]\n")
    with pytest.raises(LLMError):
        run(
            body,
            {
                "changes": [
                    {
                        "block_id": selected,
                        "replacement": "[site]: https://example.com\n",
                        "reason": "수정",
                        "evidence": [],
                    }
                ],
                "unresolved": [],
            },
        )


def test_markdown_list_tightness_cannot_change_unselected_item():
    body, selected = markdown_request("- 첫 항목\n- 다음 항목\n")
    with pytest.raises(LLMError):
        run(
            body,
            {
                "changes": [
                    {
                        "block_id": selected,
                        "replacement": "- 첫 수정 항목\n\n",
                        "reason": "수정",
                        "evidence": [],
                    }
                ],
                "unresolved": [],
            },
        )


@pytest.mark.parametrize(
    ("text", "line", "before", "replacement"),
    [
        (
            "| 제목 | 값 |\n| --- | --- |\n| 하나 | 이전 |\n| 둘 | 유지 |\n",
            3,
            "| 하나 | 이전 |\n",
            "| 하나 | 변경 |\n",
        ),
        ("- 첫 항목\n- 둘째 항목\n", 1, "- 첫 항목\n", "- 변경 항목\n"),
        ("1. 첫 항목\n2. 둘째 항목\n", 2, "2. 둘째 항목\n", "2. 변경 항목\n"),
        ("> 첫 문단\n>\n> 둘째 문단\n", 1, "> 첫 문단\n", "> 변경 문단\n"),
        ("- 상위\n  - 첫 하위\n  - 둘 하위\n", 2, "  - 첫 하위\n", "  - 수정 하위\n"),
    ],
)
def test_markdown_adjacent_block_edits_preserve_other_blocks(
    text, line, before, replacement
):
    body, selected = markdown_request(text, line)
    result, _ = run(
        body,
        {
            "changes": [
                {
                    "block_id": selected,
                    "replacement": replacement,
                    "reason": "수정",
                    "evidence": [],
                }
            ],
            "unresolved": [],
        },
    )
    assert result["result"]["after_content"] == text.replace(before, replacement, 1)


def test_markdown_multiple_selected_table_rows_preserve_middle_row():
    text = "| 항목 | 값 |\n| --- | --- |\n| 첫째 | 이전 |\n| 둘째 | 유지 |\n| 셋째 | 이전 |\n"
    body, first = markdown_request(text, 3)
    third = next(
        b.block_id
        for b in parse_source(body.source).blocks
        if b.locator.start_line == 5
    )
    body = body.model_copy(update={"selected_block_ids": [first, third]})
    result, _ = run(
        body,
        {
            "changes": [
                {
                    "block_id": first,
                    "replacement": "| 첫째 | 새 값 |\n",
                    "reason": "수정",
                    "evidence": [],
                },
                {
                    "block_id": third,
                    "replacement": "| 셋째 | 변경 |\n",
                    "reason": "수정",
                    "evidence": [],
                },
            ],
            "unresolved": [],
        },
    )
    assert result["result"]["after_content"] == text.replace(
        "| 첫째 | 이전 |", "| 첫째 | 새 값 |"
    ).replace("| 셋째 | 이전 |", "| 셋째 | 변경 |")


def test_markdown_validation_does_not_block_event_loop(monkeypatch):
    import threading

    import app.services.document_revision as revision

    body, selected = markdown_request("첫 문단\n\n다음 문단\n")
    started, release = threading.Event(), threading.Event()
    original = revision._check_markdown_scope

    def slow_validation(*args):
        started.set()
        if not release.wait(2):
            raise RuntimeError("event loop could not run")
        return original(*args)

    monkeypatch.setattr(revision, "_check_markdown_scope", slow_validation)
    provider = Provider(
        {
            "changes": [
                {
                    "block_id": selected,
                    "replacement": "수정 문단\n",
                    "reason": "수정",
                    "evidence": [],
                }
            ],
            "unresolved": [],
        }
    )

    async def exercise():
        task = asyncio.create_task(
            generate_revision(body, LLMClient(provider), "test-model")
        )
        try:
            while not started.is_set():
                await asyncio.sleep(0.001)
            release.set()
            return await task
        finally:
            release.set()

    assert (
        asyncio.run(exercise())["result"]["after_content"] == "수정 문단\n\n다음 문단\n"
    )


def test_endpoint_authenticates_and_rejects_missing_scope_before_generation():
    body, _ = make_request()
    provider = Provider({"changes": [], "unresolved": ["검토 필요"]})
    app = FastAPI()
    app.include_router(router)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    app.dependency_overrides[get_review_client] = lambda: LLMClient(provider)
    payload = body.model_dump(mode="json")
    with TestClient(app) as client:
        assert client.post("/v1/document-revisions", json=payload).status_code == 401
        headers = {"Authorization": "Bearer test-internal"}
        invalid = {**payload, "selected_block_ids": []}
        assert client.post(
            "/v1/document-revisions", json=invalid, headers=headers
        ).status_code in (400, 422)
        assert provider.calls == 0
        response = client.post("/v1/document-revisions", json=payload, headers=headers)
        assert response.status_code == 200, response.text
        assert response.json()["result"]["after_content"] == body.source.content
