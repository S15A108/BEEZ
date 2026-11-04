"""The representative question set must separate grounded, withheld, off-topic and rejected."""

import asyncio
from pathlib import Path

from app.services.bee_chat import INSUFFICIENT_TEXT, OUT_OF_SCOPE_TEXT
from app.services.llm_client import LLMError, LLMUsage
from evaluations.bee_chat.cases import QUESTIONS
from evaluations.bee_chat.run import (
    DRY_TITLE,
    ScriptedAnswerProposer,
    ScriptedClaimVerifier,
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
    return asyncio.run(evaluate(ScriptedAnswerProposer, ScriptedClaimVerifier))


def row(result, question_id):
    return next(item for item in result["questions"] if item["id"] == question_id)


def test_dry_run_grounds_answers_and_withholds_guesses():
    result = report()
    summary = result["summary"]
    assert summary == {
        "total": len(QUESTIONS),
        "answered": 12,
        "insufficient": 2,
        "out_of_scope": 1,
        "rejected": 4,
        "failed": 0,
        "all_checks_passed": True,
    }
    # A fabricated quote beside a real one rejects the output (PR #96 P1).
    draft = row(result, "plan-draft")
    assert draft["status"] == "rejected" and draft["error_code"] == "invalid_output"
    assert draft["usage"][0]["failed"]
    assert row(result, "req-absent")["status"] == "rejected"
    assert row(result, "req-contradiction")["status"] == "rejected"
    # PR #96 P1 (7th round): the flipped subject is caught by the judge call,
    # whose usage follows the failed answer call.
    flipped = row(result, "plan-negation")
    assert flipped["status"] == "rejected"
    assert [(u["request_key"], u["failed"]) for u in flipped["usage"]] == [
        ("scripted-plan-negation", True),
        ("scripted-verify-plan-negation", False),
    ]
    completed = [r for r in result["questions"] if r["status"] == "completed"]
    assert {
        r["verification"] for r in completed if r["answer_status"] == "ANSWERED"
    } == {"ENTAILMENT"}
    assert {
        r["verification"] for r in completed if r["answer_status"] != "ANSWERED"
    } == {"NONE"}
    followup = row(result, "plan-publish-followup")
    assert len(followup["citations"]) == 2 and "[3]" not in followup["text"]
    assert followup["text"].count("[1]") == 2
    task = row(result, "task-status")
    assert task["grounded"] and task["task_refs"] == ["TASK-001"]
    assert not task["citations"] and task["design_refs"] == ["API-001"]
    withheld = row(result, "password-absent")
    assert withheld["answer_status"] == "INSUFFICIENT_EVIDENCE"
    assert withheld["text"] == INSUFFICIENT_TEXT
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


def test_without_a_judge_the_flipped_answer_is_only_token_checked():
    result = asyncio.run(evaluate(ScriptedAnswerProposer))
    flipped = row(result, "plan-negation")
    # The deterministic checks alone cannot see it, and the row says so.
    assert flipped["status"] == "completed" and flipped["verification"] == "TOKENS"
    assert not flipped["checks"]["status_expected"]
    assert result["summary"]["rejected"] == 3


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
    assert result["summary"]["rejected"] == 0
    assert not result["summary"]["all_checks_passed"]
    assert result["questions"][0]["error_code"] == "model_unavailable"
    assert "실패: `model_unavailable`" in render_markdown(result, title="t")
