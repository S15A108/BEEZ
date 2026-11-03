"""#223: a .txt upload is indexed as written; plain-v1 interprets no markup."""

import json
from hashlib import sha256
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.schemas.documents import SourceInput
from app.services.document_parsing.errors import SourceParsingError
from app.services.document_parsing.pipeline import parse_source, resolve_source
from app.services.retrieval.tokenization import EmbeddingTokenCounter

USER_TAG = "The result contains <user> tags.\n"
PARAGRAPHS = (
    "첫 문단 <div>태그</div>\n"
    "둘째 줄 ![그림](a.png)\n"
    "\n"
    "  \t\n"
    "# 제목처럼 보이는 줄\n"
    "\n"
    "- 목록처럼\n"
    "> 인용처럼\n"
    "| 표 | 처럼 |\n"
    "```\n"
    "닫히지 않은 fence\n"
)
MARKDOWN = {"media_type": "text/markdown", "parser_profile": "markdown-v1"}


def normalized(content):
    return content.removeprefix("﻿").replace("\r\n", "\n").replace("\r", "\n")


def source_for(content, **overrides):
    fields = {
        "project_id": "project-1",
        "document_version_id": "version-1",
        "kind": "PLAN",
        "media_type": "text/plain",
        "content": content,
        "content_hash": sha256(content.encode("utf-8")).hexdigest(),
        "text_hash": sha256(normalized(content).encode("utf-8")).hexdigest(),
        "normalization_version": "utf8-lf-v1",
        "parser_profile": "plain-v1",
    }
    return SourceInput(**(fields | overrides))


def assert_complete_coverage(document):
    lines = document.normalized_text.split("\n")
    if lines[-1] == "":
        lines.pop()
    covered = []
    for item in (*document.blocks, *document.excluded_ranges):
        covered.extend(range(item.locator.start_line, item.locator.end_line + 1))
    assert sorted(covered) == list(range(1, len(lines) + 1))


def test_html_like_text_is_one_verbatim_paragraph():
    document = parse_source(source_for(USER_TAG))
    assert document.media_type == "text/plain"
    assert document.parser_profile == "plain-v1"
    assert document.parser_version.startswith("beez-plain/")
    assert [b.block_type for b in document.blocks] == ["paragraph"]
    assert resolve_source(document, document.blocks[0].locator) == USER_TAG
    assert document.excluded_ranges == ()
    assert document.diagnostics == ()
    assert_complete_coverage(document)


def test_same_text_fails_as_markdown_but_is_plain_text_source():
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for(USER_TAG, **MARKDOWN))
    assert exc.value.code == "unsupported_construct"
    assert parse_source(source_for(USER_TAG)).blocks


def test_markup_is_never_interpreted_and_only_blank_lines_are_excluded():
    content = "﻿" + PARAGRAPHS.replace("\n", "\r\n")
    document = parse_source(source_for(content))
    assert document.normalized_text == PARAGRAPHS
    assert [b.block_type for b in document.blocks] == ["paragraph"] * 3
    assert [(b.locator.start_line, b.locator.end_line) for b in document.blocks] == [
        (1, 2),
        (5, 5),
        (7, 11),
    ]
    assert all(
        b.heading_path == () and b.container_path == () and b.cells == ()
        for b in document.blocks
    )
    assert [
        (e.locator.start_line, e.locator.end_line, e.reason)
        for e in document.excluded_ranges
    ] == [(3, 4, "blank"), (6, 6, "blank")]
    assert resolve_source(document, document.blocks[2].locator) == (
        "- 목록처럼\n> 인용처럼\n| 표 | 처럼 |\n```\n닫히지 않은 fence\n"
    )
    assert document.diagnostics == ()
    assert_complete_coverage(document)


def test_unicode_whitespace_line_is_content_and_last_line_needs_no_newline():
    document = parse_source(source_for("앞 뒤\n　\n\n끝"))
    assert [(b.locator.start_line, b.locator.end_line) for b in document.blocks] == [
        (1, 2),
        (4, 4),
    ]
    assert resolve_source(document, document.blocks[0].locator) == "앞 뒤\n　\n"
    assert resolve_source(document, document.blocks[1].locator) == "끝"
    assert [e.reason for e in document.excluded_ranges] == ["blank"]
    assert_complete_coverage(document)


def test_profiles_do_not_share_block_ids_for_the_same_text():
    plain = parse_source(source_for("같은 문장\n"))
    markdown = parse_source(source_for("같은 문장\n", **MARKDOWN))
    assert plain.blocks[0].text_hash == markdown.blocks[0].text_hash
    assert plain.blocks[0].block_id != markdown.blocks[0].block_id


@pytest.mark.parametrize(
    "overrides,code",
    [
        ({"parser_profile": "markdown-v1"}, "pipeline_version_mismatch"),
        ({"media_type": "text/markdown"}, "pipeline_version_mismatch"),
        ({"normalization_version": "trim-v1"}, "pipeline_version_mismatch"),
        ({"kind": "OTHER"}, "unsupported_media_type"),
        (
            {"media_type": "text/html", "parser_profile": "html-v1"},
            "unsupported_media_type",
        ),
    ],
)
def test_profile_must_match_media_type(overrides, code):
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for("문서\n", **overrides))
    assert exc.value.code == code


@pytest.mark.parametrize("content", ["", " \t\n\n", "﻿\r\n"])
def test_blank_only_text_is_not_success(content):
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for(content))
    assert exc.value.code == "no_extractable_content"


def test_paragraph_budget_fails_without_partial_result():
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for("x\n\n" * 4_001))
    assert exc.value.code == "document_limit_exceeded"
    assert exc.value.locator.start_line == 8_001
    assert len(parse_source(source_for("x\n\n" * 4_000)).blocks) == 4_000


@pytest.fixture(scope="module")
def client():
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None, ai_internal_token="test-internal"
    )
    with TestClient(app) as instance:
        yield instance
    app.dependency_overrides.clear()


def chunk_request(content, **changes):
    body = {
        "project_id": "101",
        "document_version_id": "202",
        "request_key": "plain-chunk-1",
        "kind": "PLAN",
        "media_type": "text/plain",
        "content": content,
        "content_hash": sha256(content.encode("utf-8")).hexdigest(),
        "text_hash": sha256(normalized(content).encode("utf-8")).hexdigest(),
        "normalization_profile": "utf8-lf-v1",
        "chunking_profile_id": "md-structure-v1",
    }
    return body | changes


def post(client, payload):
    return client.post(
        "/v1/document-chunks",
        json=payload,
        headers={"Authorization": "Bearer test-internal"},
    )


def assert_spans_rebuild_source(content, data):
    source = normalized(content)
    owners = [0] * len(source)
    for chunk in data["chunks"]:
        assert chunk["text"] == "".join(
            source[s["start_offset"] : s["end_offset"]] for s in chunk["source_spans"]
        )
        assert chunk["embedding_text"] == chunk["text"]
        assert chunk["context_refs"] == []
        for span in chunk["source_spans"]:
            for index in range(span["start_offset"], span["end_offset"]):
                owners[index] += 1
    line_starts = [0] + [i + 1 for i, ch in enumerate(source) if ch == "\n"]
    for excluded in data["excluded_ranges"]:
        assert excluded["reason"] == "blank"
        locator = excluded["locator"]
        start = line_starts[locator["start_line"] - 1]
        end = (
            line_starts[locator["end_line"]]
            if locator["end_line"] < len(line_starts)
            else len(source)
        )
        for index in range(start, end):
            owners[index] += 1
    assert all(owner == 1 for owner in owners)


def test_user_tag_text_is_chunked_as_plain_text_with_the_real_chunker(client):
    response = post(client, chunk_request(USER_TAG))
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["origin"] == "PARSER" and payload["usage"] == []
    data = payload["result"]
    assert data["contract_version"] == "document-chunks-v1"
    assert data["parser_version"].startswith("beez-plain/")
    assert data["manifest"]["parser_profile"] == "plain-v1"
    assert data["manifest"]["parser_version"] == data["parser_version"]
    assert data["manifest"]["chunking_profile"]["profile_id"] == "md-structure-v1"
    assert data["manifest"]["chunking_profile"]["tokenizer_id"] == (
        EmbeddingTokenCounter.tokenizer_id
    )
    assert data["manifest"]["expected_chunk_keys"] == [
        c["chunk_key"] for c in data["chunks"]
    ]
    assert data["diagnostics"] == [] and data["excluded_ranges"] == []
    (chunk,) = data["chunks"]
    assert chunk["text"] == USER_TAG
    assert chunk["locator"] == {"type": "text_lines", "start_line": 1, "end_line": 1}
    assert chunk["token_count"] == EmbeddingTokenCounter().count(USER_TAG)
    assert_spans_rebuild_source(USER_TAG, data)

    rejected = post(client, chunk_request(USER_TAG, media_type="text/markdown"))
    assert rejected.status_code == 422
    assert rejected.json()["error"]["details"]["reason"] == "unsupported_construct"


def test_blank_line_paragraphs_keep_exact_spans_and_blank_exclusions(client):
    content = "﻿" + PARAGRAPHS.replace("\n", "\r\n")
    response = post(client, chunk_request(content))
    assert response.status_code == 200, response.text
    data = response.json()["result"]
    spans = [span for chunk in data["chunks"] for span in chunk["source_spans"]]
    assert [(s["locator"]["start_line"], s["locator"]["end_line"]) for s in spans] == [
        (1, 2),
        (5, 5),
        (7, 11),
    ]
    assert [
        (e["locator"]["start_line"], e["locator"]["end_line"])
        for e in data["excluded_ranges"]
    ] == [(3, 4), (6, 6)]
    assert "".join(c["text"] for c in data["chunks"]) == PARAGRAPHS.replace(
        "\n\n  \t\n", "\n"
    ).replace("\n\n", "\n")
    assert_spans_rebuild_source(content, data)


def test_long_single_line_is_split_within_the_token_budget(client):
    content = "결과에는 <user> 태그가 그대로 남는다. " * 150 + "\n"
    counter = EmbeddingTokenCounter()
    assert counter.count(content) > 600
    response = post(client, chunk_request(content))
    assert response.status_code == 200, response.text
    data = response.json()["result"]
    assert len(data["chunks"]) > 1
    assert "".join(c["text"] for c in data["chunks"]) == content
    for chunk in data["chunks"]:
        assert 0 < chunk["token_count"] <= 600
        assert chunk["token_count"] == counter.count(chunk["embedding_text"])
        assert chunk["locator"] == {
            "type": "text_lines",
            "start_line": 1,
            "end_line": 1,
        }
    assert data["excluded_ranges"] == []
    assert_spans_rebuild_source(content, data)


def test_parser_profile_defaults_from_media_type_and_must_match(client):
    omitted = post(client, chunk_request(USER_TAG))
    explicit = post(client, chunk_request(USER_TAG, parser_profile="plain-v1"))
    assert omitted.status_code == explicit.status_code == 200
    assert omitted.json() == explicit.json()
    assert omitted.json()["result"]["manifest"]["parser_profile"] == "plain-v1"

    mismatch = post(client, chunk_request(USER_TAG, parser_profile="markdown-v1"))
    assert mismatch.status_code == 422
    assert mismatch.json()["error"]["code"] == "invalid_ai_input"
    assert mismatch.json()["error"]["details"]["reason"] == (
        "pipeline_version_mismatch"
    )

    markdown = post(
        client, chunk_request("# 제목\n\n본문\n", media_type="text/markdown")
    )
    assert markdown.status_code == 200, markdown.text
    assert markdown.json()["result"]["manifest"]["parser_profile"] == "markdown-v1"


def test_published_plain_text_fixture_matches_http_contract(client):
    fixtures = Path(__file__).resolve().parents[2] / "docs/api/examples/document-chunks"
    payload = json.loads((fixtures / "plain-request.json").read_text(encoding="utf-8"))
    expected = json.loads(
        (fixtures / "plain-response.json").read_text(encoding="utf-8")
    )
    assert payload["media_type"] == "text/plain" and "parser_profile" not in payload
    assert "<user>" in payload["content"]
    response = post(client, payload)
    assert response.status_code == 200
    assert response.json() == expected
