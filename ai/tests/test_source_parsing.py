"""#127: 원문 보존·구조 경계·실패 시 전체 미반환 계약."""

from hashlib import sha256
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.schemas.documents import SourceDocument, SourceInput, SourceLocator
from app.services.document_parsing.errors import SourceParsingError
from app.services.document_parsing.pipeline import parse_source, resolve_source

FIXTURES = Path(__file__).parent / "fixtures" / "parsing"


def source_for(content, *, kind="PLAN", **overrides):
    normalized = (
        content.removeprefix("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    )
    fields = {
        "project_id": "project-1",
        "document_version_id": "version-1",
        "kind": kind,
        "media_type": "text/markdown",
        "content": content,
        "content_hash": sha256(content.encode("utf-8")).hexdigest(),
        "text_hash": sha256(normalized.encode("utf-8")).hexdigest(),
        "normalization_version": "utf8-lf-v1",
        "parser_profile": "markdown-v1",
    }
    fields.update(overrides)
    return SourceInput(**fields)


def fixture_source(name, kind):
    return source_for((FIXTURES / name).read_bytes().decode("utf-8"), kind=kind)


def assert_complete_coverage(document):
    # Every logical source line belongs to exactly one block or explicit exclusion.
    lines = document.normalized_text.split("\n")
    if lines[-1] == "":
        lines.pop()
    covered = []
    for item in (*document.blocks, *document.excluded_ranges):
        covered.extend(range(item.locator.start_line, item.locator.end_line + 1))
    assert sorted(covered) == list(range(1, len(lines) + 1))


@pytest.mark.parametrize(
    "name,kind",
    [
        ("plan.md", "PLAN"),
        ("requirements.md", "REQUIREMENTS"),
        ("api-spec.md", "API_SPEC"),
        ("data-design.md", "ERD"),
    ],
)
def test_source_roundtrip(name, kind):
    source = fixture_source(name, kind)
    document = parse_source(source)
    assert document.kind == kind
    assert document.project_id == source.project_id
    assert document.document_version_id == source.document_version_id
    assert document.content_hash == source.content_hash
    assert document.text_hash == source.text_hash
    assert document.blocks
    assert document == parse_source(source)
    assert SourceDocument.model_validate_json(document.model_dump_json()) == document
    lines = document.normalized_text.splitlines(keepends=True)
    for block in document.blocks:
        raw = resolve_source(document, block.locator)
        assert raw == "".join(
            lines[block.locator.start_line - 1 : block.locator.end_line]
        )
        assert sha256(raw.encode()).hexdigest() == block.text_hash
    assert_complete_coverage(document)


def test_normalization_preserves_whitespace_unicode_and_distinct_raw_hash():
    content = "\ufeff# 정책\r\n\r\n  Ａ 😀 e\u0301  \r다음 줄\r"
    document = parse_source(source_for(content))
    assert document.normalized_text == "# 정책\n\n  Ａ 😀 e\u0301  \n다음 줄\n"
    assert document.content_hash != document.text_hash
    assert (
        resolve_source(document, document.blocks[-1].locator)
        == "  Ａ 😀 e\u0301  \n다음 줄\n"
    )
    assert_complete_coverage(document)


def test_duplicate_text_keeps_distinct_occurrences_and_heading_references():
    document = parse_source(source_for("# 반복\n\n같은 문장\n\n# 반복\n\n같은 문장\n"))
    first_heading, first, second_heading, second = document.blocks
    assert first.block_id != second.block_id
    assert first.text_hash == second.text_hash
    assert first.heading_path == (first_heading.block_id,)
    assert second.heading_path == (second_heading.block_id,)
    assert second.locator.start_line == 7
    changed = parse_source(
        source_for(document.normalized_text, document_version_id="v2")
    )
    assert {b.block_id for b in document.blocks}.isdisjoint(
        b.block_id for b in changed.blocks
    )


def test_table_rows_preserve_cells_header_reference_and_raw_escaped_pipe():
    document = parse_source(fixture_source("requirements.md", "REQUIREMENTS"))
    header = next(b for b in document.blocks if b.block_type == "table_header")
    rows = [b for b in document.blocks if b.block_type == "table_row"]
    assert header.cells == ("ID", "요구사항", "조건")
    assert (header.locator.start_line, header.locator.end_line) == (5, 6)
    assert rows[1].cells == (
        "REQ-002",
        "`admin|owner`만 승인한다.",
        "팀에 소속되어야 함",
    )
    assert all(row.table_header_id == header.block_id for row in rows)
    assert "admin\\|owner" in resolve_source(document, rows[1].locator)


def test_api_endpoints_and_json_example_keep_heading_boundary():
    document = parse_source(fixture_source("api-spec.md", "API_SPEC"))
    headings = {
        b.block_id: b.heading_text for b in document.blocks if b.block_type == "heading"
    }
    code = next(b for b in document.blocks if b.block_type == "code_block")
    assert [headings[h] for h in code.heading_path] == [
        "인증 API",
        "POST /api/auth/login",
        "성공 응답",
    ]
    assert code.info == "json"
    assert (
        resolve_source(document, code.locator)
        == '```json\n{"access_token": "example", "expires_in": 3600}\n```\n'
    )
    assert [headings[h] for h in document.blocks[-1].heading_path] == [
        "인증 API",
        "GET /api/auth/login",
    ]


def test_data_tables_preserve_explicit_relationships_without_inference():
    document = parse_source(fixture_source("data-design.md", "ERD"))
    headings = {
        b.block_id: b.heading_text for b in document.blocks if b.block_type == "heading"
    }
    rows = [b for b in document.blocks if b.block_type == "table_row"]
    assert headings[rows[1].heading_path[-1]] == "users"
    assert rows[1].cells[3] == "-"
    assert headings[rows[-1].heading_path[-1]] == "documents"
    assert rows[-1].cells[3:] == ("FK", "users.id 참조")
    assert "RESTRICT" in resolve_source(document, document.blocks[-1].locator)


def test_nested_list_quote_and_reference_definitions_are_not_lost():
    document = parse_source(fixture_source("plan.md", "PLAN"))
    nested = next(
        b for b in document.blocks if "예외:" in resolve_source(document, b.locator)
    )
    assert nested.block_type == "list_item"
    assert len(nested.container_path) == 2
    assert any(
        b.container_path and b.container_path[-1].kind == "blockquote"
        for b in document.blocks
    )
    definition = next(
        b for b in document.blocks if b.block_type == "reference_definition"
    )
    assert "https://example.test/schedule" in resolve_source(
        document, definition.locator
    )


def test_hash_mismatch():
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for("# 원문", text_hash="0" * 64))
    assert exc.value.code == "source_hash_mismatch"


@pytest.mark.parametrize(
    "content",
    [
        "# 정상\n\n설명\n\n<table><tr><td>숨은 정책</td></tr></table>",
        "# 정상\n\n정책은 <span>관리자만</span> 허용",
        "# 정상\n\n설명\n\n![정책](policy.png)",
        "![관계][erd]\n\n[erd]: https://example.test/erd.png",
    ],
)
def test_unsupported_content_fails_entire_document_with_location(content):
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for(content))
    assert exc.value.code == "unsupported_construct"
    assert exc.value.locator is not None


@pytest.mark.parametrize("content", ["", " \t\n\n", "---\n\n***\n"])
def test_empty_content_is_not_success(content):
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for(content))
    assert exc.value.code == "no_extractable_content"


@pytest.mark.parametrize(
    "field,value,code",
    [
        ("media_type", "application/json", "unsupported_media_type"),
        ("kind", "PDF", "unsupported_media_type"),
        ("parser_profile", "markdown-v2", "pipeline_version_mismatch"),
        ("normalization_version", "trim-v1", "pipeline_version_mismatch"),
    ],
)
def test_unsupported_input_contract(field, value, code):
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for("# 문서", **{field: value}))
    assert exc.value.code == code


@pytest.mark.parametrize(
    "content",
    ["x" * 100_001, "> " * 40 + "정책", "x\n\n" * 5_001],
    ids=["characters", "depth", "nodes"],
)
def test_resource_limits_fail_without_partial_result(content):
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for(content))
    assert exc.value.code == "document_limit_exceeded"


@pytest.mark.parametrize("row", ["| 1 | 2 | 버려지면 안 됨 |", "| 1 |"])
def test_ragged_table_is_not_silently_truncated_or_padded(row):
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for("| A | B |\n| --- | --- |\n" + row))
    assert exc.value.code == "invalid_document_syntax"
    assert exc.value.locator.start_line == 3


def test_unclosed_fence_is_rejected_but_code_is_never_executed_or_html_parsed():
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for('```json\n{"key": 1}'))
    assert exc.value.code == "invalid_document_syntax"
    document = parse_source(source_for("```html\n<table>example</table>\n```"))
    assert len(document.blocks) == 1
    assert document.blocks[0].block_type == "code_block"


def test_invalid_unicode_and_nul_are_not_replaced_silently():
    source = source_for("ok")
    for invalid in ("bad\ud800", "nul\x00value"):
        with pytest.raises(SourceParsingError) as exc:
            parse_source(source.model_copy(update={"content": invalid}))
        assert exc.value.code == "invalid_document_syntax"


def test_locator_rejects_invalid_and_out_of_range_lines():
    document = parse_source(source_for("one\ntwo\n"))
    for start, end in ((0, 1), (2, 1)):
        with pytest.raises(ValidationError):
            SourceLocator(start_line=start, end_line=end)
    with pytest.raises(SourceParsingError) as exc:
        resolve_source(document, SourceLocator(start_line=2, end_line=3))
    assert exc.value.code == "invalid_source_locator"


def test_unicode_line_separator_is_content_not_an_extra_source_line():
    document = parse_source(source_for("# 제목\n\n앞\u2028뒤\n"))
    assert document.blocks[-1].locator.start_line == 3
    assert document.blocks[-1].locator.end_line == 3
    assert resolve_source(document, document.blocks[-1].locator) == "앞\u2028뒤\n"
    assert_complete_coverage(document)


@pytest.mark.parametrize(
    "content",
    [
        "```\n    ```",
        "~~~\ntext\n~~",
        "> ```\n> example",
        "- ```\n  example",
    ],
    ids=["indented-closer", "short-closer", "quote-eof", "list-eof"],
)
def test_fence_must_have_a_real_closing_marker(content):
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for(content))
    assert exc.value.code == "invalid_document_syntax"


@pytest.mark.parametrize(
    "content",
    [
        "> ```json\n> {}\n> ```\n",
        "- ```json\n  {}\n  ```\n",
        "````\n```\n````\n",
        "    indented example\n    <table>literal</table>\n",
    ],
    ids=["quote", "list", "long-fence", "indented-code"],
)
def test_nested_and_indented_code_preserve_raw_prefixes(content):
    document = parse_source(source_for(content))
    assert len(document.blocks) == 1
    assert resolve_source(document, document.blocks[0].locator) == content
    assert_complete_coverage(document)


@pytest.mark.parametrize("prefix", ["> ", "  "], ids=["quote-table", "list-table"])
def test_table_in_container_preserves_rows_and_cells(prefix):
    content = "| A | B |\n| --- | --- |\n| 1 | 2 |\n"
    content = "".join(prefix + line for line in content.splitlines(keepends=True))
    if prefix == "  ":
        content = "- table\n\n" + content
    document = parse_source(source_for(content))
    row = next(b for b in document.blocks if b.block_type == "table_row")
    assert row.cells == ("1", "2")
    assert row.container_path
    assert_complete_coverage(document)


def test_malformed_table_header_is_not_reported_as_an_ordinary_paragraph():
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for("| A | B |\n| --- |\n| 1 | 2 |"))
    assert exc.value.code == "invalid_document_syntax"


def test_local_heading_in_quote_does_not_change_following_document_section():
    document = parse_source(source_for("# Document\n\n> # Quoted\n> example\n\nbody\n"))
    assert document.blocks[-1].heading_path == (document.blocks[0].block_id,)


def test_duplicate_reference_definitions_remain_distinct_with_diagnostic():
    content = '[link][ref]\n\n[ref]: /first "one"\n[ref]: /second "two"\n'
    document = parse_source(source_for(content))
    definitions = [b for b in document.blocks if b.block_type == "reference_definition"]
    assert len(definitions) == 2
    assert document.diagnostics[0].code == "duplicate_reference"
    assert document.diagnostics[0].locator.start_line == 4
    assert_complete_coverage(document)


def test_empty_markers_are_explicit_exclusions_and_do_not_hide_body():
    document = parse_source(source_for("# Policy\n\n>\n\n-\n\nbody\n"))
    assert [b.block_type for b in document.blocks] == ["heading", "paragraph"]
    assert sum(e.reason == "container_marker" for e in document.excluded_ranges) == 2
    assert_complete_coverage(document)


def test_setext_heading_and_unterminated_final_line_have_exact_locations():
    document = parse_source(source_for("Title\n=====\n\nbody"))
    assert document.blocks[0].heading_text == "Title"
    assert resolve_source(document, document.blocks[0].locator) == "Title\n=====\n"
    assert resolve_source(document, document.blocks[-1].locator) == "body"


def test_maximum_character_boundary_is_accepted_and_not_truncated():
    content = "가" * 100_000
    document = parse_source(source_for(content))
    assert resolve_source(document, document.blocks[0].locator) == content


def test_byte_limit_is_checked_before_normalized_character_limit():
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for("😀" * 262_145))
    assert exc.value.code == "document_limit_exceeded"
    assert "MiB" in str(exc.value)


def test_source_input_does_not_coerce_binary_uploads_or_unknown_fields():
    fields = source_for("ok").model_dump()
    with pytest.raises(ValidationError):
        SourceInput(**{**fields, "content": b"\xff"})
    with pytest.raises(ValidationError):
        SourceInput(**{**fields, "unexpected": "field"})


def test_different_project_and_content_cannot_reuse_block_ids():
    original = parse_source(source_for("body"))
    for source in (source_for("body", project_id="another"), source_for("changed")):
        assert parse_source(source).blocks[0].block_id != original.blocks[0].block_id


def test_large_blank_run_is_one_explicit_range_not_thousands_of_records():
    document = parse_source(source_for("# policy\n" + "\n" * 90_000))
    assert len(document.excluded_ranges) == 1
    assert document.excluded_ranges[0].locator.end_line == 90_001
    assert_complete_coverage(document)


def test_metadata_expansion_has_an_output_budget():
    # Small text with deeply nested containers can otherwise produce a large DTO.
    prefix = "> " * 29
    content = (prefix + "x\n" + prefix + "\n") * 800
    with pytest.raises(SourceParsingError) as exc:
        parse_source(source_for(content))
    assert exc.value.code == "document_limit_exceeded"


def test_original_bytes_hash_is_carried_but_not_recomputed_from_normalized_text():
    document = parse_source(source_for("# normalized\n", content_hash="a" * 64))
    assert document.content_hash == "a" * 64
