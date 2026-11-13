"""Rule detection cites exact, resolvable source quotes and needs no model."""

from hashlib import sha256
from pathlib import Path

from app.schemas.documents import SourceInput
from app.schemas.planning_questions import DocumentRef
from app.services.document_parsing.pipeline import parse_source, resolve_source
from app.services.planning_questions import detect_rule_candidates

REF = DocumentRef(document_id="201", document_version_id="211", version_no=1)
FIXTURES = Path(__file__).parent / "fixtures" / "parsing"


def parse(content, *, kind="PLAN", media_type="text/markdown", profile="markdown-v1"):
    digest = sha256(content.encode("utf-8")).hexdigest()
    return parse_source(
        SourceInput(
            project_id="101",
            document_version_id="211",
            kind=kind,
            media_type=media_type,
            content=content,
            content_hash=digest,
            text_hash=digest,
            normalization_version="utf8-lf-v1",
            parser_profile=profile,
        )
    )


def assert_resolvable(document, hits):
    blocks = {block.block_id: block for block in document.blocks}
    for candidate, draft in hits:
        assert candidate.status == "REVIEW_CANDIDATE"
        assert draft.origin == "TEMPLATE"
        for item in candidate.evidence:
            assert item.quote in resolve_source(document, blocks[item.block_id].locator)
            assert item.locator == blocks[item.block_id].locator


def test_undecided_sentences_cite_exact_sentences_under_their_heading():
    document = parse(
        "# 기획\n\n## 운영\n\n정식 운영 날짜는 아직 결정하지 않았다. 서비스는 일정을 "
        "추측해 확정하지 않는다. 요금은 별도 정책에 따른다.\n"
    )
    hits = detect_rule_candidates(document, REF)
    assert [c.evidence[0].quote for c, _ in hits] == [
        "정식 운영 날짜는 아직 결정하지 않았다.",
        "요금은 별도 정책에 따른다.",
    ]
    assert [c.candidate_key for c, _ in hits] == [
        "rule-undecided-L5-1",
        "rule-undecided-L5-2",
    ]
    assert {c.kind for c, _ in hits} == {"MISSING_CANDIDATE"}
    assert {c.signal for c, _ in hits} == {"UNDECIDED_STATEMENT"}
    assert hits[0][0].evidence[0].location == "기획 / 운영 / L5-L5"
    assert hits[0][1].question_type == "ESSAY"
    assert "정식 운영 날짜는 아직 결정하지 않았다." in hits[0][1].text
    assert_resolvable(document, hits)


def test_present_tense_exclusion_needs_a_document_scope_marker():
    document = parse(
        "이 문서는 로그 보관 기간을 확정하지 않는다. 검증기는 결과를 임의로 확정하지 "
        "않는다. 합계 한도는 정의하지 않는다.\n"
    )
    hits = detect_rule_candidates(document, REF)
    assert [c.evidence[0].quote for c, _ in hits] == [
        "이 문서는 로그 보관 기간을 확정하지 않는다."
    ]


def test_blank_cells_are_not_undecided():
    # An ERD leaves descriptions blank and puts table constraints in a row of
    # blank cells; neither says anything is undecided (S15P31A108-413).
    document = parse(
        "## users\n\n| 컬럼 | 타입 | 설명 |\n| --- | --- | --- |\n"
        "| id | BIGSERIAL PK | |\n| email | TEXT | 로그인 이메일 |\n"
        "| | | **UK (provider, provider_user_id)** |\n| status | TEXT | - |\n"
    )
    assert detect_rule_candidates(document, REF) == []


def test_undecided_cells_of_one_column_are_one_question():
    rows = ["기획", "설계", "구현", "시험", "배포", "운영"]
    document = parse(
        "## 일정\n\n| 단계 | 담당 | 기한 |\n| --- | --- | --- |\n"
        + "".join(f"| {name} | 미정 | TBD |\n" for name in rows[:1])
        + "".join(f"| {name} | TBD | 11-20 |\n" for name in rows[1:])
    )
    hits = detect_rule_candidates(document, REF)
    # Keys name the table by its header line, so another row joining the
    # column keeps the key stable.
    assert [c.candidate_key for c, _ in hits] == [
        "rule-placeholder-L3-c2",
        "rule-placeholder-L3-c3",
    ]
    (owner, owner_draft), (due, due_draft) = hits
    assert {owner.signal, due.signal} == {"PLACEHOLDER_CELL"}
    # Evidence stays within the schema bound; every row is still named.
    assert [item.quote for item in owner.evidence] == [
        f"| {name} | {'미정' if name == '기획' else 'TBD'} | "
        f"{'TBD' if name == '기획' else '11-20'} |"
        for name in rows[:5]
    ]
    assert all(f"'{name}'" in owner_draft.text for name in rows)
    assert "'담당'" in owner.summary and "6개 행" in owner.summary
    assert owner_draft.question_type == "ESSAY"
    # A column undecided in one row keeps the short single-value question.
    assert [item.quote for item in due.evidence] == ["| 기획 | 미정 | TBD |"]
    assert "'기획'" in due.summary and "'기한'" in due.summary
    assert due_draft.question_type == "SHORT_ANSWER"
    assert_resolvable(document, hits)


def test_duplicate_identifiers_with_different_text_are_one_conflict():
    content = (FIXTURES / "requirements.md").read_bytes().decode("utf-8")
    document = parse(content, kind="REQUIREMENTS")
    hits = [
        item
        for item in detect_rule_candidates(document, REF)
        if item[0].kind == "CONFLICT"
    ]
    assert len(hits) == 1
    candidate, draft = hits[0]
    assert candidate.candidate_key == "rule-duplicate-REQ-003"
    assert candidate.signal == "DUPLICATE_KEY"
    assert [item.quote for item in candidate.evidence] == ["REQ-003", "REQ-003"]
    assert len({item.block_id for item in candidate.evidence}) == 2
    assert draft.question_type == "CHOICE"
    assert len(draft.answer_format.options) == 2 and draft.answer_format.allow_other
    assert_resolvable(document, hits)


def test_identical_repeats_and_single_keys_are_not_conflicts():
    document = parse(
        "- REQ-001: 같은 문장이다.\n- REQ-001: 같은 문장이다.\n- REQ-002: 하나.\n"
    )
    assert detect_rule_candidates(document, REF) == []


def test_list_markers_are_stripped_from_quotes():
    document = parse("- 재시도 횟수는 추후 결정한다.\n")
    (candidate, _), *_ = detect_rule_candidates(document, REF)
    assert candidate.evidence[0].quote == "재시도 횟수는 추후 결정한다."


def test_plain_text_profile_has_line_only_locations():
    document = parse(
        "<user> 태그가 있는 문단.\n\n요금은 미정이다.\n",
        media_type="text/plain",
        profile="plain-v1",
    )
    hits = detect_rule_candidates(document, REF)
    assert [c.evidence[0].location for c, _ in hits] == ["L3-L3"]
    assert_resolvable(document, hits)


def test_overlong_sentence_quote_is_capped_but_still_contains_the_signal():
    document = parse("가" * 1500 + " 일정은 미정이다.\n")
    hits = detect_rule_candidates(document, REF)
    quote = hits[0][0].evidence[0].quote
    assert len(quote) <= 1000 and "미정" in quote
    assert_resolvable(document, hits)


def test_long_table_cells_and_headings_keep_the_candidate_within_limits():
    long_cell = "가" * 2100
    long_heading = "나" * 1200
    document = parse(
        f"# {long_heading}\n\n| {long_cell} | 값 |\n| --- | --- |\n| {long_cell} | TBD |\n"
    )
    hits = detect_rule_candidates(document, REF)
    assert len(hits) == 1
    candidate, draft = hits[0]
    assert len(candidate.summary) <= 2000 and len(draft.text) <= 2000
    assert "가" * 80 + "…" in candidate.summary and "가" * 81 not in candidate.summary
    assert "'값'" in candidate.summary
    assert len(candidate.evidence[0].quote) <= 1000
    assert len(candidate.evidence[0].location) <= 1000
    assert_resolvable(document, hits)
