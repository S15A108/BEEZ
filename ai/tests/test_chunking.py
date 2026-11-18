"""Source coverage and evidence/context separation for #129."""

from hashlib import sha256
from pathlib import Path

import pytest

from app.schemas.documents import SourceInput
from app.schemas.retrieval import ChunkingProfile
from app.services.document_parsing.pipeline import parse_source
from app.services.retrieval.chunking import build_chunks, resolve_span
from app.services.retrieval.errors import RetrievalError


class ByteCounter:
    """Test-only budget. This is not a production model tokenizer."""

    tokenizer_id = "test:utf8-bytes-v1"
    max_input_tokens = 4096

    def count(self, text: str) -> int:
        return len(text.encode("utf-8"))


def document_for(content, kind="PLAN", version="v1"):
    normalized = (
        content.removeprefix("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    )
    return parse_source(
        SourceInput(
            project_id="p1",
            document_version_id=version,
            kind=kind,
            media_type="text/markdown",
            content=content,
            content_hash=sha256(content.encode()).hexdigest(),
            text_hash=sha256(normalized.encode()).hexdigest(),
            normalization_version="utf8-lf-v1",
            parser_profile="markdown-v1",
        )
    )


def profile(**changes):
    return ChunkingProfile(tokenizer_id=ByteCounter.tokenizer_id, **changes)


def assert_coverage(document, batch):
    expected = []
    lines = document.normalized_text.split("\n")
    starts = [0]
    for line in lines[:-1]:
        starts.append(starts[-1] + len(line) + 1)
    starts.append(len(document.normalized_text))
    for block in document.blocks:
        expected.extend(
            range(starts[block.locator.start_line - 1], starts[block.locator.end_line])
        )
    actual = []
    for chunk in batch.chunks:
        assert chunk.text == "".join(
            resolve_span(document, span) for span in chunk.source_spans
        )
        assert chunk.token_count == ByteCounter().count(chunk.embedding_text)
        assert chunk.token_count <= batch.manifest.chunking_profile.max_tokens
        for span in chunk.source_spans:
            actual.extend(range(span.start_offset, span.end_offset))
    assert actual == expected
    assert len(actual) == len(set(actual))


@pytest.mark.parametrize(
    "filename,kind",
    [
        ("plan.md", "PLAN"),
        ("requirements.md", "REQUIREMENTS"),
        ("api-spec.md", "API_SPEC"),
        ("data-design.md", "ERD"),
    ],
)
def test_chunk_coverage_and_deterministic_manifest(filename, kind):
    content = (Path(__file__).parent / "fixtures/parsing" / filename).read_text(
        encoding="utf-8"
    )
    document = document_for(content, kind)
    batch = build_chunks(document, profile(max_tokens=180), ByteCounter())
    assert batch == build_chunks(document, profile(max_tokens=180), ByteCounter())
    assert batch.manifest.expected_chunk_keys == tuple(
        c.chunk_key for c in batch.chunks
    )
    assert batch.manifest.document_version_id == "v1"
    assert batch.manifest.text_hash == document.text_hash
    assert [c.chunk_no for c in batch.chunks] == list(range(len(batch.chunks)))
    assert batch.excluded_ranges == document.excluded_ranges
    assert_coverage(document, batch)


def test_large_single_line_table_row_has_exact_subspans_and_header_reference():
    document = document_for(
        "# 요구사항\n\n| ID | 내용 |\n| --- | --- |\n| R-1 | " + "조건😀 " * 80 + "|\n",
        "REQUIREMENTS",
    )
    row = next(b for b in document.blocks if b.block_type == "table_row")
    batch = build_chunks(document, profile(max_tokens=110), ByteCounter())
    pieces = [
        c
        for c in batch.chunks
        if any(s.block_id == row.block_id for s in c.source_spans)
    ]
    assert len(pieces) > 1
    assert all(
        any(ref.block_id == row.table_header_id for ref in c.context_refs)
        for c in pieces
    )
    assert all(
        s.locator.start_line == 5 and s.locator.end_line == 5
        for c in pieces
        for s in c.source_spans
    )
    assert_coverage(document, batch)


def test_context_is_budgeted_and_never_added_to_evidence():
    document = document_for(
        "# 정책\n\n관리자는 승인한다.\n\n" + "다른 조건도 확인한다. " * 8
    )
    baseline = build_chunks(document, profile(max_tokens=90), ByteCounter())
    augmented = build_chunks(
        document, profile(max_tokens=90, include_headings=True), ByteCounter()
    )
    assert all(c.embedding_text == c.text for c in baseline.chunks)
    assert any(c.embedding_text != c.text for c in augmented.chunks)
    assert any(ref.applied for c in augmented.chunks for ref in c.context_refs)
    assert_coverage(document, augmented)


def test_table_header_context_is_independently_configurable():
    document = document_for(
        "# Policy\n\n| ID | Detail |\n| --- | --- |\n" + "| A | " + "x " * 90 + "|\n"
    )
    batch = build_chunks(
        document, profile(max_tokens=100, include_table_headers=True), ByteCounter()
    )
    rows = [
        c
        for c in batch.chunks
        if any(ref.role == "table_header" and ref.applied for ref in c.context_refs)
    ]
    assert rows
    assert all("| ID | Detail |" in c.embedding_text for c in rows)
    assert all(
        not ref.applied
        for c in batch.chunks
        for ref in c.context_refs
        if ref.role == "heading"
    )
    assert_coverage(document, batch)


def test_api_endpoint_boundary_even_when_everything_fits_the_budget():
    document = document_for(
        "# API\n\n## GET /items\n\n읽기\n\n## POST /items\n\n쓰기\n", "API_SPEC"
    )
    batch = build_chunks(document, profile(max_tokens=1000), ByteCounter())
    assert all(not ("읽기" in c.text and "쓰기" in c.text) for c in batch.chunks)
    headings = {
        b.heading_text: b.block_id for b in document.blocks if b.block_type == "heading"
    }
    for chunk in batch.chunks:
        if "쓰기" in chunk.text:
            assert headings["POST /items"] in [
                ref.block_id for ref in chunk.context_refs
            ]
            assert headings["GET /items"] not in [
                ref.block_id for ref in chunk.context_refs
            ]


def test_data_table_boundary_and_duplicate_source_occurrences():
    document = document_for("# users\n\n같은 값\n\n# documents\n\n같은 값\n", "ERD")
    batch = build_chunks(document, profile(max_tokens=1000), ByteCounter())
    assert len(batch.chunks) == 2
    assert batch.chunks[0].chunk_key != batch.chunks[1].chunk_key
    assert_coverage(document, batch)


def test_unicode_offsets_are_code_points_and_preserve_crlf_normalized_source():
    document = document_for("\ufeff# 제목\r\n\r\n" + "😀가Ａe\u0301" * 10)
    batch = build_chunks(document, profile(max_tokens=13), ByteCounter())
    assert_coverage(document, batch)


def test_profile_and_document_version_change_chunk_keys():
    document = document_for("# Policy\n\nbody")
    original = build_chunks(document, profile(), ByteCounter())
    changed_profile = build_chunks(
        document, profile(include_headings=True), ByteCounter()
    )
    changed_version = build_chunks(
        document_for("# Policy\n\nbody", version="v2"), profile(), ByteCounter()
    )
    assert (
        original.manifest.expected_chunk_keys
        != changed_profile.manifest.expected_chunk_keys
    )
    assert (
        original.manifest.expected_chunk_keys
        != changed_version.manifest.expected_chunk_keys
    )


@pytest.mark.parametrize("bad", ["text", "block_hash", "missing_block", "bad_ref"])
def test_invalid_source_snapshot_is_rejected_before_chunking(bad):
    document = document_for("# Policy\n\nbody")
    if bad == "text":
        document = document.model_copy(update={"normalized_text": "changed"})
    elif bad == "block_hash":
        blocks = (
            document.blocks[0].model_copy(update={"text_hash": "0" * 64}),
            document.blocks[1],
        )
        document = document.model_copy(update={"blocks": blocks})
    elif bad == "missing_block":
        document = document.model_copy(update={"blocks": document.blocks[:1]})
    else:
        blocks = (
            document.blocks[0],
            document.blocks[1].model_copy(update={"heading_path": ("absent",)}),
        )
        document = document.model_copy(update={"blocks": blocks})
    with pytest.raises(RetrievalError) as exc:
        build_chunks(document, profile(), ByteCounter())
    assert exc.value.code == "invalid_source_document"


def test_tokenizer_identity_and_model_limit_are_required():
    document = document_for("body")
    for config in (ChunkingProfile(tokenizer_id="other"), profile(max_tokens=4097)):
        with pytest.raises(RetrievalError) as exc:
            build_chunks(document, config, ByteCounter())
        assert exc.value.code == "chunking_profile_mismatch"


def test_context_or_single_character_that_cannot_fit_fails_whole_batch():
    document = document_for("# Very long heading\n\nbody")
    with pytest.raises(RetrievalError):
        build_chunks(
            document, profile(max_tokens=10, include_headings=True), ByteCounter()
        )
    with pytest.raises(RetrievalError):
        build_chunks(document_for("😀"), profile(max_tokens=3), ByteCounter())


def test_chunk_count_limit_does_not_return_partial_batch():
    with pytest.raises(RetrievalError) as exc:
        build_chunks(
            document_for("x" * 100), profile(max_tokens=10, max_chunks=2), ByteCounter()
        )
    assert exc.value.code == "chunk_limit_exceeded"


def test_sibling_list_items_share_chunks_and_keep_item_spans():
    document = document_for("# Policy\n\n" + "".join(f"- Rule {i}\n" for i in range(5)))
    batch = build_chunks(document, profile(), ByteCounter())
    list_chunks = [chunk for chunk in batch.chunks if "- Rule" in chunk.text]
    assert len(list_chunks) == 1
    assert len(list_chunks[0].source_spans) == 5
    assert_coverage(document, batch)


def test_many_short_list_items_fit_the_default_chunk_limit():
    document = document_for(
        "# Requirements\n\n" + "".join(f"- Requirement {i:04d}\n" for i in range(1500))
    )
    batch = build_chunks(document, profile(), ByteCounter())
    assert len(batch.chunks) < 100
    assert all(chunk.token_count <= 600 for chunk in batch.chunks)
    assert [span.block_id for chunk in batch.chunks for span in chunk.source_spans] == [
        block.block_id for block in document.blocks
    ]


def test_nested_siblings_merge_within_their_own_parent_item():
    document = document_for(
        "- Parent A\n  - child A1\n  - child A2\n"
        "- Parent B\n  - child B1\n  - child B2\n"
    )
    batch = build_chunks(document, profile(), ByteCounter())
    assert any("child A1" in c.text and "child A2" in c.text for c in batch.chunks)
    assert any("child B1" in c.text and "child B2" in c.text for c in batch.chunks)
    assert all(not ("child A" in c.text and "child B" in c.text) for c in batch.chunks)
    assert_coverage(document, batch)


@pytest.mark.parametrize("separator", ["\n---\n\n", "\nparagraph\n\n"])
def test_separate_lists_keep_separate_boundaries(separator):
    document = document_for(
        "- first A\n- first B\n" + separator + "- second A\n- second B\n"
    )
    batch = build_chunks(document, profile(), ByteCounter())
    assert any("first A" in c.text and "first B" in c.text for c in batch.chunks)
    assert any("second A" in c.text and "second B" in c.text for c in batch.chunks)
    assert all(not ("first A" in c.text and "second A" in c.text) for c in batch.chunks)
    assert_coverage(document, batch)


def test_list_types_and_quote_ranges_remain_boundaries():
    document = document_for(
        "- bullet A\n- bullet B\n\n1. ordered A\n2. ordered B\n\n"
        "> - quoted A\n> - quoted B\n\nplain\n\n> - other quote\n"
    )
    batch = build_chunks(document, profile(), ByteCounter())
    assert any("bullet A" in c.text and "bullet B" in c.text for c in batch.chunks)
    assert any("ordered A" in c.text and "ordered B" in c.text for c in batch.chunks)
    assert any("quoted A" in c.text and "quoted B" in c.text for c in batch.chunks)
    assert all(
        not ("bullet A" in c.text and "ordered A" in c.text) for c in batch.chunks
    )
    assert all(
        not ("quoted A" in c.text and "other quote" in c.text) for c in batch.chunks
    )
    assert_coverage(document, batch)
