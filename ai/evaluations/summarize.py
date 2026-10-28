"""Summarize saved evaluation results without model calls or rescoring by an LLM."""

import argparse
import json
import math
from pathlib import Path


def _mean(values):
    known = [value for value in values if value is not None]
    return f"{sum(known) / len(known):.4f}" if known else "N/A"


def _p95(values):
    return sorted(values)[math.ceil(len(values) * 0.95) - 1] if values else None


def _fraction(numerator, denominator):
    return f"{numerator}/{denominator}" if denominator else "N/A"


def summarize(report):
    questions = {q["id"]: q for q in report["questions"]}
    repetitions = report["metadata"]["repetitions"]
    answerable = sum(bool(q["gold"]) for q in questions.values()) * repetitions
    absent = sum(not q["gold"] for q in questions.values()) * repetitions
    planned = len(questions) * repetitions
    lines = [
        "# #130 Markdown RAG 개발용 실측 요약",
        "",
        f"상태: `{report['status']}` · 데이터: `{report['metadata']['dataset_id']}` · 질문 {len(questions)}개 × {repetitions}회 × {len(report['indexes'])}설정.",
        "",
        "실제 GMS + 로컬 정확 cosine 검색을 측정했다. 실제 BE/DB 또는 운영 비비 경로의 측정은 아니다. Agent 작성 합성 개발 자료로, 독립 사람 검토와 운영 데이터 평가는 남아 있다.",
        "",
        "| 설정 | 응답 성공/계획 | Recall@1 | Recall@3 | Recall@6 | Recall@12 | MRR@12 | 컨텍스트 Recall | 정답 용어 일치 | 원문 인용 검사 | 근거 없음 보류 | 생성 p95(ms) |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for mode in report["indexes"]:
        rows = [row for row in report["rows"] if row["mode"] == mode]
        known = [row for row in rows if questions[row["question_id"]]["gold"]]
        negatives = [row for row in rows if not questions[row["question_id"]]["gold"]]
        recall = [
            _mean(
                row.get("retrieval", {}).get(str(k), {}).get("recall") for row in known
            )
            for k in (1, 3, 6, 12)
        ]
        mrr = _mean(row.get("retrieval", {}).get("12", {}).get("mrr") for row in known)
        context = _mean(row.get("context_recall") for row in known)
        fact = sum(
            row.get("scores", {}).get("required_fact_match", False) for row in known
        )
        citation = sum(
            row.get("answer", {}).get("status") == "answered"
            and row.get("scores", {}).get("citation_valid", False)
            for row in known
        )
        abstained = sum(
            row.get("scores", {}).get("abstention_correct", False)
            and row.get("scores", {}).get("citation_valid", False)
            for row in negatives
        )
        latency = _p95([row["generation_ms"] for row in rows if "generation_ms" in row])
        success = sum(row["status"] == "ok" for row in rows)
        lines.append(
            f"| {mode} | {_fraction(success, planned)} | {' | '.join(recall)} | {mrr} | {context} | {_fraction(fact, answerable)} | {_fraction(citation, answerable)} | {_fraction(abstained, absent)} | {latency} |"
        )
    calls = report["calls"]
    lines += [
        "",
        "- Recall의 분모는 고정된 정답 원문 범위다. 나뉜 청크의 범위를 합쳐 전체 정답 범위가 포함돼야 회수로 센다. MRR은 정답 범위와 겹치는 첫 결과의 역순위이므로 부분 근거만 있는 경우 Recall과 다를 수 있다.",
        "- 정답 용어 일치는 의미 정확도의 자동 대리지표다. 용어만 맞는 환각·추가 주장·의미/조건 왜곡은 원시 답변을 사람이 따로 검토해야 한다. 원문 인용 검사는 전달된 ID와 실제 원문 조각의 정확한 문자열 포함 검사다.",
        "- 출력 오류·미실행은 성공 분자에서 제외하되 계획 분모에서 빼지 않는다. 검색 평균은 검색을 실제 실행한 답변 가능 행만 대상으로 하므로 완료 상태와 실행 건수를 함께 확인한다.",
        "",
        "## 호출·비용",
        "",
        "| 단계 | 실제 호출 | 실패 호출 | 입력 토큰(알려진 값) | 출력 토큰(알려진 값) | 공개 단가 추산 크레딧 | usage 미확정 예약액 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for stage in ("document", "query", "generation"):
        selected = [call for call in calls if call["stage"] == stage]
        lines.append(
            f"| {stage} | {len(selected)} | {sum(c['failed'] for c in selected)} | {sum((c.get('usage') or {}).get('input_tokens', 0) for c in selected)} | {sum((c.get('usage') or {}).get('output_tokens', 0) for c in selected)} | {sum(c.get('estimated_credits') or 0 for c in selected):.4f} | {sum(c.get('reserved_credits_if_unknown', 0) for c in selected):.4f} |"
        )
    lines += [
        "",
        "알려진 사용량 합계와 미확정 사용량을 분리했다. 실제 잔액 차감 대조는 수행하지 않았다. 동일 임베딩 입력과 질문 벡터를 설정·반복 간 재사용하므로 실제 공유 호출 비용은 설정별 독립 재색인 비용과 다르다.",
        "",
        "| 설정 | 청크 수 | 논리적 색인 입력 토큰 |",
        "| --- | --- | --- |",
    ]
    for mode, chunks in report["indexes"].items():
        lines.append(
            f"| {mode} | {len(chunks)} | {report.get('logical_index_tokens', {}).get(mode, 'N/A')} |"
        )
    lines += [
        "",
        "## C 모의 승인 경합",
        "",
        "| 사례 | 결과 | 검증 baseline | 전달 시 baseline | 위반 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for case in report["consistency"]:
        lines.append(
            f"| {case['id']} | {case['outcome']} | {case.get('validated_baseline', 'N/A')} | {case['current_baseline_at_delivery']} | {case['violations']} |"
        )
    lines += [
        "",
        "모의 상태와 Event barrier로 절차를 검증했다. 실제 PostgreSQL 원자성·권한 격리·이전 실행 차단은 별도 BE 통합 시험이 필요하다.",
        "",
        "## 관찰된 자동 검사 실패",
        "",
    ]
    failures = [
        row
        for row in report["rows"]
        if row["status"] != "ok"
        or not all(
            row.get("scores", {}).get(key, False)
            for key in (
                "citation_valid",
                "abstention_correct",
                "injection_marker_absent",
            )
        )
        or (
            questions[row["question_id"]]["gold"]
            and not row.get("scores", {}).get("required_fact_match")
        )
    ]
    lines += [
        f"- `{row['id']}`: status={row['status']}, scores={json.dumps(row.get('scores', {}), ensure_ascii=False)}, error={row.get('error', 'none')}"
        for row in failures
    ] or ["없음. 자동 검사만의 결과이며 의미 정확도 보장은 아니다."]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding="utf-8"))
    args.output.write_text(summarize(report), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
