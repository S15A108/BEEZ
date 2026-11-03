"""The representative question set must separate grounded, withheld and off-topic."""

import asyncio
from pathlib import Path

from app.services.bee_chat import INSUFFICIENT_TEXT, OUT_OF_SCOPE_TEXT, UNVERIFIED_NOTE
from app.services.llm_client import LLMError, LLMUsage
from evaluations.bee_chat.cases import QUESTIONS
from evaluations.bee_chat.run import (
    DRY_TITLE,
    ScriptedAnswerProposer,
    evaluate,
    render_markdown,
)

REPORT = (
    Path(__file__).resolve().parents[1]
    / "evaluations"
    / "bee_chat"
    / "results-2026-11-03-dry.md"
)


def report():
    return asyncio.run(evaluate(ScriptedAnswerProposer))


def row(result, question_id):
    return next(item for item in result["questions"] if item["id"] == question_id)


def test_dry_run_grounds_answers_and_withholds_guesses():
    result = report()
    summary = result["summary"]
    assert summary == {
        "total": len(QUESTIONS),
        "failed": 0,
        "answered": 12,
        "insufficient": 3,
        "out_of_scope": 1,
        "all_checks_passed": True,
    }
    draft = row(result, "plan-draft")
    assert len(draft["citations"]) == 1 and "[2]" not in draft["text"]
    assert "모든 멤버가 조회한다" not in str(draft["citations"])
    withheld = row(result, "req-absent")
    assert withheld["answer_status"] == "INSUFFICIENT_EVIDENCE"
    assert (
        withheld["text"] == INSUFFICIENT_TEXT
        and UNVERIFIED_NOTE in withheld["unresolved"]
    )
    assert "90일" not in withheld["text"]
    judged = row(result, "erd-absent")
    assert (
        judged["answer_status"] == "INSUFFICIENT_EVIDENCE" and not judged["citations"]
    )
    publish = row(result, "plan-publish")
    assert publish["evidence_considered"] == 12 and publish["evidence_omitted"] == 8
    assert publish["design_refs"] == ["PLN-001"] and publish["notices"]
    create = row(result, "api-create")
    assert create["design_refs"] == ["API-001"] and create["task_refs"] == ["TASK-001"]
    assert row(result, "out-of-scope")["text"] == OUT_OF_SCOPE_TEXT
    assert "INJECTED-777" not in row(result, "injection")["text"]
    assert row(result, "plan-publish-followup")["grounded"]


def test_committed_report_matches_a_fresh_dry_run():
    assert render_markdown(report(), title=DRY_TITLE) == REPORT.read_text(
        encoding="utf-8"
    )


def test_failures_are_reported_separately():
    class Failing:
        async def propose(self, request, evidence):
            raise LLMError(
                "model_unavailable",
                (
                    LLMUsage(
                        request_key="k",
                        feature="BEE_CHAT",
                        project_id="101",
                        model="fake",
                        latency_ms=1,
                        failed=True,
                        tokens_known=False,
                    ),
                ),
            )

    result = asyncio.run(evaluate(Failing))
    assert result["summary"]["failed"] == len(QUESTIONS)
    assert not result["summary"]["all_checks_passed"]
    assert result["questions"][0]["error_code"] == "model_unavailable"
    assert "실패: `model_unavailable`" in render_markdown(result, title="t")
