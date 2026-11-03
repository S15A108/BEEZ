"""The dry-run review over the synthetic documents must stay honest and current."""

import asyncio
from pathlib import Path

from app.services.llm_client import LLMError, LLMUsage
from evaluations.planning_questions.cases import DOCUMENTS, PROPOSALS
from evaluations.planning_questions.run import (
    DRY_TITLE,
    ScriptedProposer,
    evaluate,
    render_markdown,
)

REPORT = (
    Path(__file__).resolve().parents[1]
    / "evaluations"
    / "planning_questions"
    / "results-2026-11-02-dry.md"
)


def rows():
    return asyncio.run(evaluate(ScriptedProposer))


def test_every_document_is_reviewed_without_unsupported_review_candidates():
    report = rows()
    assert [row["document"] for row in report] == [d["name"] for d in DOCUMENTS]
    reasons = set()
    for row in report:
        assert row["status"] == "completed" and row["model"] == "scripted-fake"
        checks = row["checks"]
        assert checks["review_candidates_without_evidence"] == 0
        assert checks["all_quotes_resolvable"] and checks["within_cap"]
        assert checks["model_candidates_proposed"] == len(PROPOSALS[row["document"]])
        questioned = {q["candidate_key"] for q in row["questions"]}
        assert len(questioned) == len(row["questions"])
        for candidate in row["candidates"]:
            if candidate["status"] == "UNVERIFIED":
                reasons.add(candidate["unverified_reason"])
            else:
                assert candidate["evidence"] and candidate["unverified_reason"] is None
    assert reasons == {
        "QUOTE_NOT_IN_BLOCK",
        "NO_EVIDENCE",
        "BLOCK_NOT_FOUND",
        "INSUFFICIENT_EVIDENCE",
    }


def test_explicit_undecided_statements_and_placeholders_are_found():
    quotes = {
        row["document"]: {
            item["quote"] for c in row["candidates"] for item in c["evidence"]
        }
        for row in rows()
    }
    assert "| 정식 운영 | 미정 | 시범 운영 평가 후 협의 |" in quotes["plan.md"]
    assert "이 문서는 로그 보관 기간을 확정하지 않는다." in quotes["requirements.md"]
    assert (
        "문서 삭제 API의 경로와 메서드별 상세 응답은 아직 합의하지 않았다."
        in quotes["api-spec.md"]
    )
    assert (
        "탈퇴 기록과 사용자 표시는 별도 운영 정책에 따른다." in quotes["data-design.md"]
    )


def test_committed_report_matches_a_fresh_dry_run():
    assert render_markdown(rows(), title=DRY_TITLE) == REPORT.read_text(
        encoding="utf-8"
    )


def test_failures_are_reported_separately_from_empty_results():
    class Failing:
        def __init__(self, name):
            self.name = name

        async def propose(self, document):
            raise LLMError(
                "model_unavailable",
                (
                    LLMUsage(
                        request_key="k",
                        feature="DOCUMENT_PARSE",
                        project_id="101",
                        model="fake",
                        latency_ms=1,
                        failed=True,
                        tokens_known=False,
                    ),
                ),
            )

    report = asyncio.run(evaluate(Failing))
    assert {row["status"] for row in report} == {"failed"}
    assert report[0]["error_code"] == "model_unavailable"
    assert report[0]["usage"][0]["failed"]
    assert "실패: `model_unavailable`" in render_markdown(report, title="t")
