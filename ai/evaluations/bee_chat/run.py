"""Review the Bee's answers over the representative question set (#135).

Without --live this uses the scripted retrieval, the scripted answer model
and the scripted judge in `cases.py` and makes no network call. The Markdown
report is review material for a person: every row shows the status, what
grounded the answer, how far it was verified and which checks held. A
question the scripted model answers with a fabricated quote, an unsupported
fact or a flipped claim is expected to be rejected (`invalid_output`), and
that expectation is a check.
"""

import argparse
import asyncio
import json
import os
from collections.abc import Sequence
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from app.schemas.bee_chat import BeeChatRequest, RetrievedEvidence, citation_markers
from app.services.bee_chat import (
    VERIFY_CALL_KEY_PREFIX,
    AnswerProposal,
    Claim,
    ClaimVerdicts,
    LLMAnswerProposer,
    LLMClaimVerifier,
    ModelAnswer,
    ModelVerdict,
    generate_bee_chat,
)
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMUsage,
    OpenAICompatibleProvider,
)
from evaluations.bee_chat.cases import (
    QUESTIONS,
    build_request,
    question_by_text,
    scripted_answer,
    scripted_verdicts,
)
from evaluations.planning_questions.cases import PROJECT_ID

SCRIPTED_MODEL = "scripted-fake"
DRY_TITLE = "#135 비비 대표 질문 세트 검토 (가짜 공급자, 모델 호출 없음)"
INJECTION_MARKER = "INJECTED-777"


def _scripted_usage(key: str) -> LLMUsage:
    return LLMUsage(
        request_key=key,
        feature="BEE_CHAT",
        project_id=PROJECT_ID,
        model=SCRIPTED_MODEL,
        latency_ms=0,
        failed=False,
        tokens_known=False,
    )


class ScriptedAnswerProposer:
    """Returns the scripted answer for the question; never calls a model."""

    async def propose(
        self, request: BeeChatRequest, evidence: Sequence[RetrievedEvidence]
    ) -> AnswerProposal:
        question = question_by_text(request.question)
        raw = scripted_answer(
            question, [item.model_dump(mode="json") for item in evidence]
        )
        return AnswerProposal(
            ModelAnswer.model_validate(raw),
            SCRIPTED_MODEL,
            (_scripted_usage(f"scripted-{question['id']}"),),
        )


class ScriptedClaimVerifier:
    """Judges every claim SUPPORTED unless the case script flags its text."""

    async def verify(
        self,
        question: str,
        quotes: Sequence[str],
        shared: Sequence[str],
        claims: Sequence[Claim],
    ) -> ClaimVerdicts:
        payload = {
            "question": question,
            "claims": [{"index": claim.index, "text": claim.text} for claim in claims],
        }
        raw = scripted_verdicts(payload)
        entry = question_by_text(question)
        return ClaimVerdicts(
            tuple(ModelVerdict.model_validate(item) for item in raw["verdicts"]),
            SCRIPTED_MODEL,
            (_scripted_usage(f"scripted-verify-{entry['id']}"),),
        )


def _checks(question: dict, request: BeeChatRequest, result) -> dict:
    evidence = {item.evidence_id: item for item in request.retrieved_evidence}
    answered = result.answer_status == "ANSWERED"
    return {
        "status_expected": result.answer_status == question["expected"],
        "citations_resolvable": all(
            cited.quote in evidence[cited.evidence_id].text
            for cited in result.citations
        ),
        "required_terms": not answered
        or not question["required"]
        or all(
            any(term.casefold() in result.text.casefold() for term in group)
            for group in question["required"]
        ),
        "grounded_iff_answered": result.grounded == answered,
        "markers_valid": all(
            n <= len(result.citations) for n in citation_markers(result.text)
        ),
        "injection_absent": INJECTION_MARKER not in result.text,
        "verified_iff_answered": (result.verification.level != "NONE") == answered,
    }


async def evaluate(proposer_factory, verifier_factory=None) -> dict:
    rows = []
    for question in QUESTIONS:
        # JSON validation: the strict request model takes tuples, the body lists.
        request = BeeChatRequest.model_validate_json(
            json.dumps(build_request(question), ensure_ascii=False)
        )
        verifier = verifier_factory() if verifier_factory is not None else None
        try:
            outcome = await generate_bee_chat(request, proposer_factory(), verifier)
        except LLMError as error:
            # A rejected output is the verifier doing its job; any other
            # error is a failure of the run.
            rejected = error.code == "invalid_output"
            rows.append(
                {
                    "id": question["id"],
                    "question": question["question"],
                    "expected": question["expected"],
                    "status": "rejected" if rejected else "failed",
                    "error_code": error.code,
                    "checks": {
                        "status_expected": rejected
                        and question["expected"] == "REJECTED"
                    },
                    "usage": [usage.to_api_dict() for usage in error.usage],
                }
            )
            continue
        result = outcome.result
        rows.append(
            {
                "id": question["id"],
                "question": question["question"],
                "expected": question["expected"],
                "status": "completed",
                "model": outcome.model,
                "answer_status": result.answer_status,
                "grounded": result.grounded,
                "verification": result.verification.level,
                "text": result.text,
                "citations": [
                    {
                        "n": cited.n,
                        "evidence_id": cited.evidence_id,
                        "document_version_id": cited.document_version_id,
                        "lines": f"L{cited.locator.start_line}-L{cited.locator.end_line}",
                        "quote": cited.quote,
                    }
                    for cited in result.citations
                ],
                "design_refs": [ref.key for ref in result.design_refs],
                "task_refs": [ref.key for ref in result.task_refs],
                "unresolved": list(result.unresolved),
                "next_actions": list(result.next_actions),
                "notices": list(result.notices),
                "evidence_considered": result.evidence_considered,
                "evidence_omitted": result.evidence_omitted,
                "checks": _checks(question, request, result),
                "usage": [usage.to_api_dict() for usage in outcome.usage],
            }
        )
    completed = [row for row in rows if row["status"] == "completed"]
    return {
        "questions": rows,
        "summary": {
            "total": len(rows),
            "answered": sum(1 for r in completed if r["answer_status"] == "ANSWERED"),
            "insufficient": sum(
                1 for r in completed if r["answer_status"] == "INSUFFICIENT_EVIDENCE"
            ),
            "out_of_scope": sum(
                1 for r in completed if r["answer_status"] == "OUT_OF_SCOPE"
            ),
            "rejected": sum(1 for r in rows if r["status"] == "rejected"),
            "failed": sum(1 for r in rows if r["status"] == "failed"),
            "all_checks_passed": all(
                row["status"] != "failed" and all(row["checks"].values())
                for row in rows
            ),
        },
    }


def _cell(value) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def render_markdown(report: dict, *, title: str) -> str:
    summary = report["summary"]
    lines = [f"# {title}", ""]
    lines.append(
        "대표 질문 세트(`evaluations/data/questions.json`의 12문항 + 후속 질문·작업 상태·"
        "모순 답변·뒤집힌 답변·근거 없는 주제·범위 밖·주입 문장 7문항)를 대본 검색 결과, "
        "대본 모델 출력, 대본 판정으로 돌린 결과다. 모든 답변은 입력 근거와 대조하고 "
        "문장마다 판정 모델이 인용문과 맞는지 본 뒤의 값이며, 원문에 없는 인용·인용이 "
        "뒷받침하지 않는 사실·인용과 모순되는 문장이 하나라도 있으면 답변 전체를 "
        "거부한다(`invalid_output`, HTTP 502). 승인·결정은 팀장의 몫이다."
    )
    lines.append("")
    lines.append(
        f"질문 {summary['total']}건 · 답변 {summary['answered']} · 정보 부족 "
        f"{summary['insufficient']} · 범위 밖 {summary['out_of_scope']} · 거부 "
        f"{summary['rejected']} · 실패 {summary['failed']} · 검사 "
        f"{'모두 통과' if summary['all_checks_passed'] else '실패 있음'}"
    )
    lines.append("")
    lines.append("## 질문별 결과")
    lines.append("")
    lines.append(
        "| 순서 | 질문 ID | 질문 | 기대 | 결과 | 근거 | 검증 | 인용 | 설계 항목 | 작업 "
        "| 답변 | 미확정 | 검사 |"
    )
    lines.append(
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"
    )
    for index, row in enumerate(report["questions"], start=1):
        if row["status"] != "completed":
            label = "거부" if row["status"] == "rejected" else "실패"
            verdict = "통과" if all(row["checks"].values()) else "실패: status_expected"
            lines.append(
                f"| {index} | {row['id']} | {_cell(row['question'])} | {row['expected']} "
                f"| {label}: `{row['error_code']}` | - | - | - | - | - | - | - | {verdict} |"
            )
            continue
        citations = "; ".join(
            f"[{c['n']}] {c['document_version_id']} {c['lines']} 「{_cell(c['quote'])}」"
            for c in row["citations"]
        )
        failed_checks = [name for name, ok in row["checks"].items() if not ok]
        lines.append(
            "| "
            + " | ".join(
                [
                    str(index),
                    row["id"],
                    _cell(row["question"]),
                    row["expected"],
                    row["answer_status"],
                    "예" if row["grounded"] else "아니오",
                    row["verification"],
                    citations or "-",
                    ", ".join(row["design_refs"]) or "-",
                    ", ".join(row["task_refs"]) or "-",
                    _cell(row["text"]),
                    " / ".join(_cell(item) for item in row["unresolved"]) or "-",
                    "통과"
                    if not failed_checks
                    else "실패: " + ", ".join(failed_checks),
                ]
            )
            + " |"
        )
    lines.append("")
    lines.append(
        "검사 항목: `status_expected`(기대 상태; REJECTED는 `invalid_output` 거부), "
        "`citations_resolvable`(모든 인용이 근거 원문의 부분 문자열), "
        "`required_terms`(답변에 핵심 용어 포함), `grounded_iff_answered`, "
        "`markers_valid`(본문 [n]이 인용 수 이내), `injection_absent`(주입 문장의 "
        "표식이 답변에 없음), `verified_iff_answered`(답변에만 검증 수준이 붙음). "
        "검증 열은 `verification.level`이다: `ENTAILMENT`는 판정 모델이 문장마다 "
        "인용문과 맞는지 확인한 것, `TOKENS`는 결정론 검사만 거친 것."
    )
    return "\n".join(lines) + "\n"


def _live_factories(endpoint: str, api_key: str, model: str):
    provider = OpenAICompatibleProvider(endpoint=endpoint, api_key=api_key)
    client = LLMClient(provider)

    def proposer_factory():
        return LLMAnswerProposer(
            client,
            context=LLMContext(
                project_id=PROJECT_ID,
                feature="BEE_CHAT",
                request_key=f"bee-eval-{uuid4()}",
            ),
            model=model,
            call_key=f"bee-eval-{uuid4()}",
            timeout_seconds=60,
        )

    def verifier_factory():
        return LLMClaimVerifier(
            client,
            context=LLMContext(
                project_id=PROJECT_ID,
                feature="BEE_CHAT",
                request_key=f"bee-eval-{uuid4()}",
            ),
            model=model,
            call_key=f"{VERIFY_CALL_KEY_PREFIX}eval-{uuid4()}",
            timeout_seconds=60,
        )

    return provider, proposer_factory, verifier_factory


async def _run(args) -> int:
    if args.live:
        endpoint = os.environ.get("AI_BEE_EVAL_ENDPOINT", "")
        api_key = os.environ.get("AI_BEE_EVAL_API_KEY", "")
        model = os.environ.get("AI_BEE_EVAL_MODEL", "")
        if not endpoint or not api_key or not model:
            print("Set AI_BEE_EVAL_ENDPOINT, _API_KEY and _MODEL for --live.")
            return 2
        provider, proposer_factory, verifier_factory = _live_factories(
            endpoint, api_key, model
        )
        async with provider:
            report = await evaluate(proposer_factory, verifier_factory)
        title = f"#135 실제 모델 평가 ({model}, {datetime.now(UTC):%Y-%m-%d})"
    else:
        report = await evaluate(ScriptedAnswerProposer, ScriptedClaimVerifier)
        title = DRY_TITLE
    rendered = render_markdown(report, title=title)
    if args.output:
        output = Path(args.output)
        if output.exists() and not args.overwrite:
            print(f"{output} exists; pass --overwrite to replace it.")
            return 2
        output.write_text(rendered, encoding="utf-8")
        if args.json:
            Path(args.json).write_text(
                json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
            )
    else:
        print(rendered)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="call a real model")
    parser.add_argument("--output", help="Markdown report path")
    parser.add_argument("--json", help="optional raw JSON path")
    parser.add_argument("--overwrite", action="store_true")
    return asyncio.run(_run(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
