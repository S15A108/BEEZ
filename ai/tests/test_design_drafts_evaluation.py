"""The dry-run draft review must show answers' effect without touching approvals."""

import asyncio
from pathlib import Path

from app.services.llm_client import LLMError, LLMUsage
from evaluations.design_drafts.cases import EXISTING_ITEMS
from evaluations.design_drafts.run import (
    DRY_TITLE,
    ScriptedDraftProposer,
    evaluate,
    render_markdown,
)

REPORT = (
    Path(__file__).resolve().parents[1]
    / "evaluations"
    / "design_drafts"
    / "results-2026-11-03-dry.md"
)


def row():
    return asyncio.run(evaluate(ScriptedDraftProposer))


def test_dry_run_keeps_approvals_and_shows_before_after():
    report = row()
    assert report["status"] == "completed" and report["model"] == "scripted-fake"
    checks = report["checks"]
    assert checks["all_drafts_unapproved"] and checks["approved_bases_kept_as_before"]
    assert checks["all_quotes_resolvable"] and checks["answer_refs_all_answered"]
    assert checks["within_cap"] and checks["unsupported_items"] == 1
    assert checks["items_proposed"] == 7 and checks["items"] == 6
    approved = next(
        change
        for change in report["changes"]
        if change["base_design_item_version_id"] == "1101"
    )
    assert approved["change"] == "UPDATE"
    assert approved["before"] == EXISTING_ITEMS[0]["content"]
    assert approved["changed_fields"][0]["field"] == "description"
    assert report["untouched_existing_ids"] == ["1102"]
    assert len(report["answered_questions"]) == 4
    assert len(report["unanswered_question_keys"]) == 3
    quotes = {s["quote"] for item in report["items"] for s in item["sources"]}
    assert "정식 운영 날짜는 2027년 1월이다." not in quotes
    assert [item["support"] for item in report["items"]][-1] == "NONE"


def test_committed_report_matches_a_fresh_dry_run():
    assert render_markdown(row(), title=DRY_TITLE) == REPORT.read_text(encoding="utf-8")


def test_failures_are_reported_separately():
    class Failing:
        async def propose(self, request, document):
            raise LLMError(
                "model_unavailable",
                (
                    LLMUsage(
                        request_key="k",
                        feature="BEE_DRAFT",
                        project_id="101",
                        model="fake",
                        latency_ms=1,
                        failed=True,
                        tokens_known=False,
                    ),
                ),
            )

    report = asyncio.run(evaluate(Failing))
    assert report["status"] == "failed"
    assert report["error_code"] == "model_unavailable"
    assert report["usage"][0]["failed"]
    assert "실패: `model_unavailable`" in render_markdown(report, title="t")
