"""Prepare a blind human review and compare it with saved #131 results.

This is an offline developer tool; it never calls a model, network or BE.
Reviewer-provided identity is a claim, not proof of independence.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path

from evaluations.context_selection_131 import SCENARIOS, prepare_cases


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _load_fixture(path, repository):
    raw = path.read_bytes()
    data = json.loads(raw)
    ids = [case["id"] for case in data["scenarios"]]
    if len(ids) != len(set(ids)) or any(ids.count(key) != 1 for key in SCENARIOS):
        raise ValueError("invalid fixture case IDs")
    sources = {}
    for doc_id, name in data["document_paths"].items():
        source = (repository / name).resolve()
        if not source.is_relative_to(repository.resolve()):
            raise ValueError("source outside repository")
        raw_source = source.read_bytes()
        sources[doc_id] = (name, _sha(raw_source), raw_source.decode("utf-8"))
    cases = list(prepare_cases(data))
    if [case["id"] for case, _ in cases] != list(SCENARIOS):
        raise ValueError("unexpected case order")
    return _sha(raw), sources, cases


def _case_id(index):
    return f"CASE-{index:02d}"


def _case_hashes(cases):
    """Bind anonymous case IDs to currently reconstructed snapshot inputs."""
    return {
        _case_id(index): _sha(
            json.dumps(
                snapshot.model_dump(mode="json"),
                sort_keys=True,
                ensure_ascii=True,
                separators=(",", ":"),
            ).encode("ascii")
        )
        for index, (_case, snapshot) in enumerate(cases, 1)
    }


def prepare(fixture, repository):
    """Return reviewer-facing Markdown and a blank, separate review form."""
    digest, sources, cases = _load_fixture(fixture, repository)
    lines = [
        "# 131 가상 개발 요청: 블라인드 검토",
        "",
        f"fixture SHA-256: `{digest}`",
        "",
        "이 자료는 사람이 별도로 판단하기 위한 미검토 자료다. 가상 사례이며 실제 BE 검색 결과가 아니다.",
        "기존 기대 라벨·모의 선택·모델 출력은 이 자료에 포함하지 않았다. 검토가 끝날 때까지 이들을 열지 않는다.",
        "요청에 직접 관련된 설계 버전 ID와 원문 ID를 별도 JSON 검토서에 적는다. 관계나 작업 참조만으로 추가되는 항목은 직접 관련으로 표시하지 않는다.",
        "도구는 직접 관련 항목과 현재 작업 참조를 시작점으로 EXPLICIT·CONFIRMED DEPENDS_ON 관계를 1단계만 확장해 최종 기대 설계를 계산한다.",
        "직접 관련 설계와 원문이 모두 없으면 no_match=true다. 작업 참조만 있어도 no_match=true일 수 있다.",
        "판단할 수 없으면 uncertain=true로 표시하고 해당 사례의 세 판단 필드는 null로 둔다. 빈 목록은 판단 불가가 아니다.",
        "입력의 설계와 관계는 인공 데이터다. 원문 근거는 표시된 파일에서 직접 확인한다.",
        "",
    ]
    form = {
        "fixture_sha256": digest,
        "source_sha256": {name: sha for name, sha, _ in sources.values()},
        "case_input_sha256": _case_hashes(cases),
        "reviewer": None,
        "cases": [],
    }
    for index, (case, snapshot) in enumerate(cases, 1):
        case_id = _case_id(index)
        lines += [f"## {case_id}", "", f"요청: {snapshot.request}", ""]
        if "evidence_override" in case:
            lines += [
                "이 사례의 원문 후보는 실측 당시 별도로 교체됐으며 다른 원문 후보는 포함되지 않았다. 일반적인 경쟁 검색으로 간주하지 않는다.",
                "",
            ]
        lines.append(
            "작업 참조: "
            + (
                ", ".join(
                    f"{ref.design_item_id}/{ref.design_item_version_id}"
                    for ref in snapshot.task_design_refs
                )
                if snapshot.task_id
                else "없음"
            )
        )
        lines += ["", "### 설계 후보 (인공)", ""]
        for item in snapshot.items:
            lines.append(
                f"- 항목 {item.design_item_id}, 버전 {item.design_item_version_id}: {item.text}"
            )
        lines += ["", "### 원문 후보", ""]
        for item in snapshot.evidence:
            name, sha, source = sources[item.document_version_id]
            offset = source.find(item.text)
            if offset < 0:
                raise ValueError("evidence is absent from its cited source")
            line = source.count("\n", 0, offset) + 1
            lines.append(
                f"- {item.evidence_id}: {item.text} "
                f"(출처: `{name}:{line}`, SHA-256 `{sha}`)"
            )
        lines += ["", "### 입력된 관계", ""]
        if not snapshot.relations:
            lines.append("- 없음")
        for relation in snapshot.relations:
            lines.append(
                f"- {relation.from_design_item_id}/{relation.from_design_item_version_id} → "
                f"{relation.to_design_item_id}/{relation.to_design_item_version_id}: "
                f"{relation.relation}, {relation.origin}, {relation.status}"
            )
        lines.append("")
        form["cases"].append(
            {
                "case": case_id,
                "uncertain": None,
                "direct_item_ids": None,
                "expected_evidence_ids": None,
                "no_match": None,
            }
        )
    return "\n".join(lines).rstrip() + "\n", form


def _id_list(value, allowed):
    if not isinstance(value, list) or any(not isinstance(x, str) for x in value):
        raise ValueError("a judged case requires ID arrays")
    if len(value) != len(set(value)) or not set(value).issubset(allowed):
        raise ValueError("duplicate or out-of-scope ID")
    return set(value)


def _expected_final_items(snapshot, direct):
    """Apply task seeds and exactly one eligible dependency hop."""
    by_item = {item.design_item_id: item for item in snapshot.items}
    by_version = {item.design_item_version_id: item for item in snapshot.items}
    seeds = {by_version[version].design_item_id for version in direct}
    seeds.update(ref.design_item_id for ref in snapshot.task_design_refs)
    result = set(direct) | {
        ref.design_item_version_id for ref in snapshot.task_design_refs
    }
    for relation in snapshot.relations:
        source = by_item.get(relation.from_design_item_id)
        target = by_item.get(relation.to_design_item_id)
        if (
            relation.relation == "DEPENDS_ON"
            and relation.origin == "EXPLICIT"
            and relation.status == "CONFIRMED"
            and relation.from_design_item_id in seeds
            and source is not None
            and target is not None
            and source.design_item_version_id == relation.from_design_item_version_id
            and target.design_item_version_id == relation.to_design_item_version_id
        ):
            result.add(target.design_item_version_id)
    return result


def _validated_review(fixture, repository, review):
    digest, sources, cases = _load_fixture(fixture, repository)
    if set(review) != {
        "fixture_sha256",
        "source_sha256",
        "case_input_sha256",
        "reviewer",
        "cases",
    }:
        raise ValueError("invalid review fields")
    if (
        review["fixture_sha256"] != digest
        or review["source_sha256"] != {name: sha for name, sha, _ in sources.values()}
        or review["case_input_sha256"] != _case_hashes(cases)
    ):
        raise ValueError("fixture or source hash mismatch")
    reviewer = review["reviewer"]
    if not isinstance(reviewer, str) or not reviewer.strip():
        raise ValueError("reviewer must be explicitly supplied")
    rows = review["cases"]
    if not isinstance(rows, list) or len(rows) != len(cases):
        raise ValueError("review must contain the exact case set")
    if any(not isinstance(row, dict) for row in rows):
        raise ValueError("invalid review row")
    by_id = {row.get("case"): row for row in rows}
    if len(by_id) != len(cases) or set(by_id) != {
        _case_id(i) for i in range(1, len(cases) + 1)
    }:
        raise ValueError("review must contain the exact case set")
    validated = []
    for index, (original, snapshot) in enumerate(cases, 1):
        row = by_id[_case_id(index)]
        if (
            set(row)
            != {
                "case",
                "uncertain",
                "direct_item_ids",
                "expected_evidence_ids",
                "no_match",
            }
            or type(row["uncertain"]) is not bool
        ):
            raise ValueError("uncertainty must be explicitly judged")
        if row["uncertain"]:
            if any(
                row[key] is not None
                for key in (
                    "direct_item_ids",
                    "expected_evidence_ids",
                    "no_match",
                )
            ):
                raise ValueError("uncertain cases must not contain judgments")
            validated.append((original, snapshot, row, None, None))
            continue
        direct = _id_list(
            row["direct_item_ids"],
            {x.design_item_version_id for x in snapshot.items},
        )
        evidence = _id_list(
            row["expected_evidence_ids"], {x.evidence_id for x in snapshot.evidence}
        )
        if type(row["no_match"]) is not bool:
            raise ValueError("no_match must be explicit")
        if row["no_match"] != (not direct and not evidence):
            raise ValueError("contradictory no_match judgment")
        items = _expected_final_items(snapshot, direct)
        validated.append((original, snapshot, row, items, evidence))
    return digest, reviewer, validated


def compare(fixture, repository, review, measurement, threshold=None):
    """Compare reviewed final IDs only; never reconstruct direct selection or scores."""
    digest, reviewer, cases = _validated_review(fixture, repository, review)
    if (
        measurement.get("fixture_sha256") != digest
        or measurement.get("error")
        or measurement.get("in_flight")
    ):
        raise ValueError("measurement does not match fixture or is incomplete")
    measured = measurement.get("cases")
    report_version = measurement.get("report_version")
    if report_version is not None and (
        type(report_version) is not int or report_version < 1
    ):
        raise ValueError("invalid measurement report version")
    if not isinstance(measured, list) or any(
        not isinstance(row, dict) for row in measured
    ):
        raise ValueError("invalid measurement cases")
    by_id = {row.get("id"): row for row in measured}
    if (
        len(measured) != len(cases)
        or len(by_id) != len(cases)
        or set(by_id) != set(SCENARIOS)
    ):
        raise ValueError("measurement must contain the exact case set")
    base_threshold = measurement.get("threshold")
    if (
        isinstance(base_threshold, bool)
        or not isinstance(base_threshold, (int, float))
        or not math.isfinite(base_threshold)
        or not -1 <= base_threshold <= 1
    ):
        raise ValueError("invalid measurement threshold")
    if threshold is not None and (
        isinstance(threshold, bool)
        or not math.isfinite(threshold)
        or not -1 <= threshold <= 1
    ):
        raise ValueError("invalid threshold")
    chosen_rows = {}
    for case, _snapshot in [(original, snapshot) for original, snapshot, *_ in cases]:
        measured_row = by_id[case["id"]]
        if threshold is not None and threshold != base_threshold:
            comparisons = measured_row.get("threshold_comparisons", [])
            if not isinstance(comparisons, list) or any(
                not isinstance(x, dict) for x in comparisons
            ):
                raise ValueError("invalid threshold comparisons")
            matches = [x for x in comparisons if x.get("threshold") == threshold]
            if len(matches) != 1 or matches[0].get("id") != case["id"]:
                raise ValueError("threshold comparison is absent or ambiguous")
            measured_row = matches[0]
        chosen_rows[case["id"]] = measured_row
    output = {
        "fixture_sha256": digest,
        "reviewer": reviewer,
        "reviewer_identity_verified": False,
        "threshold": measurement.get("threshold") if threshold is None else threshold,
        "scope": "final selected IDs only; synthetic labels; no direct-match inference",
        "measurement_input_note": (
            "Historical measurements do not contain per-case input hashes; "
            "verify their input-construction code and revision separately."
        ),
        "cases": [],
    }
    for index, (
        original,
        snapshot,
        row,
        expected_items,
        expected_evidence,
    ) in enumerate(cases, 1):
        if row["uncertain"]:
            output["cases"].append(
                {"case": _case_id(index), "status": "uncertain_skipped"}
            )
            continue
        measured_row = chosen_rows[original["id"]]
        items = _id_list(
            measured_row.get("items"),
            {x.design_item_version_id for x in snapshot.items},
        )
        evidence = _id_list(
            measured_row.get("evidence"), {x.evidence_id for x in snapshot.evidence}
        )
        unresolved = measured_row.get("unresolved")
        if (
            not isinstance(unresolved, list)
            or any(not isinstance(value, str) for value in unresolved)
            or len(unresolved) != len(set(unresolved))
            or not set(unresolved).issubset({"NO_MATCH", "DEPENDENCY_UNVERIFIED"})
        ):
            raise ValueError("invalid measurement unresolved state")
        dependency_status = (
            "unverified"
            if "DEPENDENCY_UNVERIFIED" in unresolved
            else "none_reported"
            if report_version == 4
            else "not_recorded"
        )
        output["cases"].append(
            {
                "case": _case_id(index),
                "status": "compared",
                "missing_items": sorted(expected_items - items),
                "extra_items": sorted(items - expected_items),
                "missing_evidence": sorted(expected_evidence - evidence),
                "extra_evidence": sorted(evidence - expected_evidence),
                "no_match_agrees": row["no_match"] == ("NO_MATCH" in unresolved),
                "dependency_status": dependency_status,
            }
        )
    return output


def _write_new(path, text):
    with path.open("x", encoding="utf-8", newline="\n") as file:
        file.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    sub = parser.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--sheet", type=Path, required=True)
    prep.add_argument("--form", type=Path, required=True)
    comp = sub.add_parser("compare")
    comp.add_argument("--review", type=Path, required=True)
    comp.add_argument("--measurement", type=Path, required=True)
    comp.add_argument("--threshold", type=float)
    comp.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        sheet, form = prepare(args.fixture, args.repository)
        if args.sheet == args.form or args.sheet.exists() or args.form.exists():
            raise ValueError("output must be new and distinct")
        _write_new(args.sheet, sheet)
        _write_new(args.form, json.dumps(form, ensure_ascii=False, indent=2) + "\n")
    else:
        review = json.loads(args.review.read_text(encoding="utf-8"))
        measurement = json.loads(args.measurement.read_text(encoding="utf-8"))
        report = compare(
            args.fixture, args.repository, review, measurement, args.threshold
        )
        _write_new(args.output, json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
