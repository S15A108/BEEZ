"""Review candidates and questions over the synthetic documents (#133).

Without --live this uses the scripted proposals in `cases.py` and makes no
model call. The Markdown report is review material for a person; it is not a
score, and a listed candidate is never a confirmed defect of the document.
"""

import argparse
import asyncio
import json
import os
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from app.schemas.documents import SourceDocument
from app.schemas.planning_questions import PlanningQuestionRequest
from app.services.document_parsing.pipeline import parse_source, resolve_source
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMUsage,
    OpenAICompatibleProvider,
)
from app.services.planning_questions import (
    CandidateProposal,
    LLMCandidateProposer,
    ModelCandidate,
    generate_planning_questions,
)
from evaluations.planning_questions.cases import (
    DOCUMENTS,
    PROJECT_ID,
    PROPOSALS,
    request_body,
    scripted_proposal,
)

SCRIPTED_MODEL = "scripted-fake"
DRY_TITLE = "#133 후보·질문 검토 (가짜 공급자, 모델 호출 없음)"


class ScriptedProposer:
    """Returns the scripted proposal for one document; never calls a model."""

    def __init__(self, name: str) -> None:
        self._name = name

    async def propose(self, document: SourceDocument) -> CandidateProposal:
        def block_for_line(line: int) -> str:
            for block in document.blocks:
                if block.locator.start_line <= line <= block.locator.end_line:
                    return block.block_id
            raise KeyError(line)

        raw = scripted_proposal(self._name, block_for_line)
        candidates = tuple(
            ModelCandidate.model_validate(item) for item in raw["candidates"]
        )
        usage = LLMUsage(
            request_key=f"scripted-{self._name}",
            feature="DOCUMENT_PARSE",
            project_id=PROJECT_ID,
            model=SCRIPTED_MODEL,
            latency_ms=0,
            failed=False,
            tokens_known=False,
        )
        return CandidateProposal(candidates, SCRIPTED_MODEL, (usage,))


def _checks(document: SourceDocument, result, proposed: int) -> dict:
    blocks = {block.block_id: block for block in document.blocks}
    resolvable = all(
        item.quote in resolve_source(document, blocks[item.block_id].locator)
        for candidate in result.candidates
        for item in candidate.evidence
    )
    return {
        "review_candidates_without_evidence": sum(
            1
            for candidate in result.candidates
            if candidate.status == "REVIEW_CANDIDATE" and not candidate.evidence
        ),
        "unverified": sum(
            1 for candidate in result.candidates if candidate.status == "UNVERIFIED"
        ),
        "all_quotes_resolvable": resolvable,
        "model_candidates_proposed": proposed,
        "questions": len(result.questions),
        "within_cap": len(result.questions) <= result.max_questions,
    }


async def evaluate(proposer_factory, *, mode="MODEL", max_questions=20) -> list[dict]:
    rows = []
    for entry in DOCUMENTS:
        name = entry["name"]
        request = PlanningQuestionRequest.model_validate(
            request_body(name, mode=mode, max_questions=max_questions)
        )
        proposer = proposer_factory(name) if mode == "MODEL" else None
        try:
            outcome = await generate_planning_questions(request, proposer)
        except LLMError as error:
            rows.append(
                {
                    "document": name,
                    "status": "failed",
                    "error_code": error.code,
                    "usage": [usage.to_api_dict() for usage in error.usage],
                }
            )
            continue
        document = parse_source(request.to_source_input())
        result = outcome.result
        rows.append(
            {
                "document": name,
                "kind": entry["kind"],
                "status": "completed",
                "origin": outcome.origin,
                "model": outcome.model,
                "checks": _checks(
                    document, result, len(PROPOSALS[name]) if mode == "MODEL" else 0
                ),
                "candidates": [
                    candidate.model_dump(mode="json") for candidate in result.candidates
                ],
                "questions": [
                    question.model_dump(mode="json") for question in result.questions
                ],
                "truncated_candidate_keys": list(result.truncated_candidate_keys),
                "usage": [usage.to_api_dict() for usage in outcome.usage],
            }
        )
    return rows


def _cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def render_markdown(rows: list[dict], *, title: str) -> str:
    lines = [f"# {title}", ""]
    lines.append(
        "후보는 모두 **검토 후보**이며 문서 결함의 확정이 아니다. `UNVERIFIED`는 인용을 원문 "
        "블록에서 찾지 못했거나 근거가 없는 후보로, 질문에도 `basis=UNVERIFIED`가 붙는다."
    )
    lines.append("")
    for row in rows:
        lines.append(f"## {row['document']}")
        lines.append("")
        if row["status"] != "completed":
            lines.append(f"실패: `{row['error_code']}`")
            lines.append("")
            continue
        checks = row["checks"]
        lines.append(
            f"origin `{row['origin']}` · 모델 제안 {checks['model_candidates_proposed']}건 · "
            f"후보 {len(row['candidates'])}건(미확인 {checks['unverified']}) · "
            f"질문 {checks['questions']}건 · 근거 없는 검토 후보 "
            f"{checks['review_candidates_without_evidence']}건 · 인용 복원 "
            f"{'성공' if checks['all_quotes_resolvable'] else '실패'}"
        )
        lines.append("")
        lines.append(
            "| 순위 | 후보 | 종류 | 상태 | 신호 | 위치 | 인용 | 질문 유형 | 질문 |"
        )
        lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        questions = {q["candidate_key"]: q for q in row["questions"]}
        for candidate in row["candidates"]:
            question = questions.get(candidate["candidate_key"])
            evidence = candidate["evidence"]
            location = evidence[0]["location"] if evidence else "-"
            quote = evidence[0]["quote"] if evidence else "-"
            if len(quote) > 60:
                quote = quote[:60] + "…"
            status = candidate["status"]
            if candidate["unverified_reason"]:
                status += f" ({candidate['unverified_reason']})"
            lines.append(
                "| "
                + " | ".join(
                    [
                        str(question["rank"]) if question else "-",
                        candidate["candidate_key"],
                        candidate["kind"],
                        status,
                        candidate["signal"],
                        _cell(location),
                        _cell(quote),
                        question["question_type"] if question else "-",
                        _cell(question["text"]) if question else "(상한 초과)",
                    ]
                )
                + " |"
            )
        lines.append("")
    return "\n".join(lines) + "\n"


def _live_factory(endpoint: str, api_key: str, model: str):
    provider = OpenAICompatibleProvider(endpoint=endpoint, api_key=api_key)
    client = LLMClient(provider)

    def factory(name: str):
        return LLMCandidateProposer(
            client,
            context=LLMContext(
                project_id=PROJECT_ID,
                feature="DOCUMENT_PARSE",
                request_key=f"planning-eval-{name}-{uuid4()}",
            ),
            model=model,
            call_key=f"planning-eval-{name}-{uuid4()}",
            timeout_seconds=60,
        )

    return provider, factory


async def _run(args) -> int:
    if args.live:
        endpoint = os.environ.get("AI_PLANNING_EVAL_ENDPOINT", "")
        api_key = os.environ.get("AI_PLANNING_EVAL_API_KEY", "")
        model = os.environ.get("AI_PLANNING_EVAL_MODEL", "")
        if not endpoint or not api_key or not model:
            print("Set AI_PLANNING_EVAL_ENDPOINT, _API_KEY and _MODEL for --live.")
            return 2
        provider, factory = _live_factory(endpoint, api_key, model)
        async with provider:
            rows = await evaluate(factory, max_questions=args.max_questions)
        title = f"#133 실제 모델 평가 ({model}, {datetime.now(UTC):%Y-%m-%d})"
    else:
        rows = await evaluate(ScriptedProposer, max_questions=args.max_questions)
        title = DRY_TITLE
    report = render_markdown(rows, title=title)
    if args.output:
        output = Path(args.output)
        if output.exists() and not args.overwrite:
            print(f"{output} exists; pass --overwrite to replace it.")
            return 2
        output.write_text(report, encoding="utf-8")
        if args.json:
            Path(args.json).write_text(
                json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8"
            )
    else:
        print(report)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="call a real model")
    parser.add_argument("--max-questions", type=int, default=20)
    parser.add_argument("--output", help="Markdown report path")
    parser.add_argument("--json", help="optional raw JSON rows path")
    parser.add_argument("--overwrite", action="store_true")
    return asyncio.run(_run(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
