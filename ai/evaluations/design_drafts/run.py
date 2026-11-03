"""Review answer-informed drafts and their change list over the plan document (#134).

Without --live this uses the scripted proposal in `cases.py` and makes no
model call. The Markdown report is review material for a person; every row is
an unapproved draft and the before/after columns show what the answers changed.
"""

import argparse
import asyncio
import json
import os
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from app.schemas.design_drafts import DesignDraftRequest
from app.schemas.documents import SourceDocument
from app.services.design_drafts import (
    DraftProposal,
    LLMDraftProposer,
    ModelItem,
    generate_design_drafts,
)
from app.services.document_parsing.pipeline import parse_source, resolve_source
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMUsage,
    OpenAICompatibleProvider,
)
from evaluations.design_drafts.cases import (
    DOCUMENT,
    DRAFT_PROPOSAL,
    build_request,
    resolve_ref,
    scripted_draft,
)
from evaluations.planning_questions.cases import PROJECT_ID

SCRIPTED_MODEL = "scripted-fake"
DRY_TITLE = "#134 답변 반영 설계 초안 검토 (가짜 공급자, 모델 호출 없음)"


class ScriptedDraftProposer:
    """Returns the scripted draft for the plan document; never calls a model."""

    async def propose(
        self, request: DesignDraftRequest, document: SourceDocument
    ) -> DraftProposal:
        def block_for_line(line: int) -> str:
            for block in document.blocks:
                if block.locator.start_line <= line <= block.locator.end_line:
                    return block.block_id
            raise KeyError(line)

        questions = [
            {"question_key": q.question.question_key, "text": q.question.text}
            for q in request.questions
        ]
        raw = scripted_draft(block_for_line, lambda ref: resolve_ref(ref, questions))
        usage = LLMUsage(
            request_key=f"scripted-{DOCUMENT}",
            feature="BEE_DRAFT",
            project_id=PROJECT_ID,
            model=SCRIPTED_MODEL,
            latency_ms=0,
            failed=False,
            tokens_known=False,
        )
        return DraftProposal(
            tuple(ModelItem.model_validate(item) for item in raw["items"]),
            tuple(raw["unresolved"]),
            SCRIPTED_MODEL,
            (usage,),
        )


def _checks(request: DesignDraftRequest, document: SourceDocument, result) -> dict:
    blocks = {block.block_id: block for block in document.blocks}
    approved = {
        item.design_item_version_id: item.content
        for item in request.existing_items
        if item.status == "APPROVED"
    }
    return {
        "items_proposed": len(DRAFT_PROPOSAL["items"]),
        "items": len(result.items),
        "all_quotes_resolvable": all(
            source.quote in resolve_source(document, blocks[source.block_id].locator)
            for item in result.items
            for source in item.sources
        ),
        "all_drafts_unapproved": all(item.status == "DRAFT" for item in result.items),
        "approved_bases_kept_as_before": all(
            change.before == approved[change.base_design_item_version_id]
            for change in result.changes
            if change.base_design_item_version_id in approved
        ),
        "answer_refs_all_answered": all(
            ref
            in {
                q.question.question_key
                for q in request.questions
                if q.status == "ANSWERED"
            }
            for item in result.items
            for ref in item.answer_refs
        ),
        "unsupported_items": sum(1 for item in result.items if item.support == "NONE"),
        "within_cap": len(result.items) <= result.max_items,
    }


async def evaluate(proposer_factory, *, max_items=50) -> dict:
    # JSON validation: the strict request model takes tuples, the body has lists.
    request = DesignDraftRequest.model_validate_json(
        json.dumps(await build_request(max_items=max_items), ensure_ascii=False)
    )
    try:
        outcome = await generate_design_drafts(request, proposer_factory())
    except LLMError as error:
        return {
            "document": DOCUMENT,
            "status": "failed",
            "error_code": error.code,
            "usage": [usage.to_api_dict() for usage in error.usage],
        }
    document = parse_source(request.to_source_input())
    result = outcome.result
    return {
        "document": DOCUMENT,
        "status": "completed",
        "model": outcome.model,
        "checks": _checks(request, document, result),
        "existing_items": [
            item.model_dump(mode="json") for item in request.existing_items
        ],
        "answered_questions": [
            {
                "question_key": q.question.question_key,
                "question": q.question.text,
                "answer": q.answer.answer.model_dump(mode="json"),
            }
            for q in request.questions
            if q.status == "ANSWERED"
        ],
        "items": [item.model_dump(mode="json") for item in result.items],
        "changes": [change.model_dump(mode="json") for change in result.changes],
        "untouched_existing_ids": list(result.untouched_existing_ids),
        "unanswered_question_keys": list(result.unanswered_question_keys),
        "unresolved": list(result.unresolved),
        "omitted_items": result.omitted_items,
        "usage": [usage.to_api_dict() for usage in outcome.usage],
    }


def _cell(value) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def render_markdown(row: dict, *, title: str) -> str:
    lines = [f"# {title}", ""]
    lines.append(
        "모든 항목은 **미승인 초안(DRAFT)** 이며 승인·발행은 BE가 맡는다. `before`는 기존 "
        "항목 본문, `after`는 초안 본문이고 답변 참조는 어떤 답변이 변경을 이끌었는지 보여 준다."
    )
    lines.append("")
    if row["status"] != "completed":
        lines.append(f"실패: `{row['error_code']}`")
        return "\n".join(lines) + "\n"
    checks = row["checks"]
    lines.append(
        f"origin `MODEL` · 모델 제안 {checks['items_proposed']}건 · 초안 {checks['items']}건"
        f"(근거 없음 {checks['unsupported_items']}) · 인용 복원 "
        f"{'성공' if checks['all_quotes_resolvable'] else '실패'} · 승인본 보존 "
        f"{'확인' if checks['approved_bases_kept_as_before'] else '실패'} · 답변 참조 "
        f"{'모두 답변됨' if checks['answer_refs_all_answered'] else '오류'}"
    )
    lines.append("")
    lines.append("## 답변")
    lines.append("")
    lines.append("| 질문 키 | 질문 | 답변 |")
    lines.append("| --- | --- | --- |")
    for entry in row["answered_questions"]:
        answer = entry["answer"]
        text = answer.get("text") or ", ".join(answer.get("selected", []))
        lines.append(
            f"| {entry['question_key']} | {_cell(entry['question'])} | {_cell(text)} |"
        )
    lines.append("")
    lines.append("## 초안과 변경 목록")
    lines.append("")
    lines.append(
        "| 순서 | 초안 | 종류 | 변경 | 기준 항목 | 근거 | before | after | 바뀐 필드 | 답변 참조 |"
    )
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for index, (item, change) in enumerate(
        zip(row["items"], row["changes"], strict=True), start=1
    ):
        base = item["base_design_item_version_id"] or "-"
        if item["base_status"]:
            base += f" ({item['base_status']})"
        before = change["before"]["description"] if change["before"] else "-"
        fields = ", ".join(f["field"] for f in change["changed_fields"]) or "-"
        lines.append(
            "| "
            + " | ".join(
                [
                    str(index),
                    item["candidate_key"],
                    item["kind"],
                    change["change"],
                    base,
                    item["support"],
                    _cell(before),
                    _cell(item["content"]["description"]),
                    fields,
                    ", ".join(item["answer_refs"]) or "-",
                ]
            )
            + " |"
        )
    lines.append("")
    lines.append(
        f"그대로 둔 기존 항목: {', '.join(row['untouched_existing_ids']) or '없음'} · "
        f"미답변 질문: {len(row['unanswered_question_keys'])}건 · 미확정: "
        + (" / ".join(row["unresolved"]) or "없음")
    )
    return "\n".join(lines) + "\n"


def _live_factory(endpoint: str, api_key: str, model: str):
    provider = OpenAICompatibleProvider(endpoint=endpoint, api_key=api_key)
    client = LLMClient(provider)

    def factory():
        return LLMDraftProposer(
            client,
            context=LLMContext(
                project_id=PROJECT_ID,
                feature="BEE_DRAFT",
                request_key=f"draft-eval-{uuid4()}",
            ),
            model=model,
            call_key=f"draft-eval-{uuid4()}",
            timeout_seconds=90,
        )

    return provider, factory


async def _run(args) -> int:
    if args.live:
        endpoint = os.environ.get("AI_DRAFT_EVAL_ENDPOINT", "")
        api_key = os.environ.get("AI_DRAFT_EVAL_API_KEY", "")
        model = os.environ.get("AI_DRAFT_EVAL_MODEL", "")
        if not endpoint or not api_key or not model:
            print("Set AI_DRAFT_EVAL_ENDPOINT, _API_KEY and _MODEL for --live.")
            return 2
        provider, factory = _live_factory(endpoint, api_key, model)
        async with provider:
            row = await evaluate(factory, max_items=args.max_items)
        title = f"#134 실제 모델 평가 ({model}, {datetime.now(UTC):%Y-%m-%d})"
    else:
        row = await evaluate(ScriptedDraftProposer, max_items=args.max_items)
        title = DRY_TITLE
    report = render_markdown(row, title=title)
    if args.output:
        output = Path(args.output)
        if output.exists() and not args.overwrite:
            print(f"{output} exists; pass --overwrite to replace it.")
            return 2
        output.write_text(report, encoding="utf-8")
        if args.json:
            Path(args.json).write_text(
                json.dumps(row, ensure_ascii=False, indent=2), encoding="utf-8"
            )
    else:
        print(report)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="call a real model")
    parser.add_argument("--max-items", type=int, default=50)
    parser.add_argument("--output", help="Markdown report path")
    parser.add_argument("--json", help="optional raw JSON path")
    parser.add_argument("--overwrite", action="store_true")
    return asyncio.run(_run(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
