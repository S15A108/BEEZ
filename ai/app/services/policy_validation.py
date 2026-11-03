"""Validate policy-analysis provenance against the existing AI-A02 snapshot.

These checks establish identity and coverage, not the semantic correctness of
the model's interpretation. The backend repeats them before applying a result.
"""

import json
import re

from app.schemas.validation import PolicyPlanEvidence, ValidateRequest, ValidateResult

POLICY_CHECK_KEY = "policy-gap-v1"
POLICY_CHECK_KIND = "POLICY_ALIGNMENT"


def _unique(values):
    return len(values) == len(set(values))


def _plan_quote(reference: PolicyPlanEvidence, body: ValidateRequest) -> bool:
    value = getattr(body.plan_revision.plan, reference.field)
    if isinstance(value, list):
        if (
            reference.index is None
            or reference.index < 0
            or reference.index >= len(value)
        ):
            return False
        value = value[reference.index]
    elif reference.index is not None:
        return False
    return bool(reference.quote.strip()) and reference.quote in value


def _content_quote(item, reference) -> bool:
    value = item.content
    for part in reference.path:
        if not part or len(part) > 256:
            return False
        if isinstance(value, dict) and part in value:
            value = value[part]
        elif isinstance(value, list) and re.fullmatch(r"0|[1-9][0-9]*", part):
            index = int(part)
            if index >= len(value):
                return False
            value = value[index]
        else:
            return False
    if isinstance(value, str):
        return bool(reference.quote.strip()) and reference.quote in value
    if value is None or isinstance(value, (dict, list, float)):
        return False
    return reference.quote == json.dumps(value, ensure_ascii=False)


def validate_policy_result(
    body: ValidateRequest, result: ValidateResult, undecided
) -> None:
    checks = [check for check in body.checks if check.kind == POLICY_CHECK_KIND]
    if not checks:
        if (
            result.policy_assessments is not None
            or result.policy_assessments_truncated is not None
            or result.no_policy_requirements_reason is not None
            or result.no_policy_requirements_evidence is not None
            or any(
                f.policy_need_id is not None or f.conflicting_design_refs
                for f in result.findings
            )
        ):
            raise ValueError("policy output without policy check")
        return
    if len(checks) != 1 or checks[0].key != POLICY_CHECK_KEY:
        raise ValueError("invalid policy check")
    check = checks[0]
    if result.policy_assessments is None or result.policy_assessments_truncated is None:
        raise ValueError("missing policy assessments")
    assessments = result.policy_assessments
    if not _unique([assessment.need_id for assessment in assessments]):
        raise ValueError("duplicate policy need")
    policy_unassessed = [
        entry for entry in result.unassessed if entry.check_key == POLICY_CHECK_KEY
    ]
    if (
        not assessments
        and not result.policy_assessments_truncated
        and not policy_unassessed
    ):
        if (
            not result.no_policy_requirements_reason
            or not result.no_policy_requirements_reason.strip()
            or result.no_policy_requirements_evidence is None
            or not _plan_quote(result.no_policy_requirements_evidence, body)
        ):
            raise ValueError("empty policy assessment without plan evidence")
    elif (
        result.no_policy_requirements_reason is not None
        or result.no_policy_requirements_evidence is not None
    ):
        raise ValueError("contradictory empty policy explanation")

    items = {item.id: item for item in body.approved_items}
    evidence = {entry.evidence_id: entry for entry in body.evidence}
    by_need = {assessment.need_id: assessment for assessment in assessments}
    allowed_versions = set(check.design_item_version_ids)
    for assessment in assessments:
        if (
            not assessment.need_id.strip()
            or not assessment.topic.strip()
            or not assessment.explanation.strip()
        ):
            raise ValueError("empty policy assessment field")
        if not _plan_quote(assessment.plan_evidence, body):
            raise ValueError("policy plan quote not in input")
        versions = assessment.design_item_version_ids
        if not _unique(versions) or not set(versions) <= allowed_versions:
            raise ValueError("policy version outside check")
        if not _unique(assessment.evidence_ids):
            raise ValueError("duplicate policy evidence")
        for reference in assessment.approved_content_refs:
            item = items.get(reference.design_item_version_id)
            if (
                item is None
                or reference.design_item_version_id not in versions
                or not _content_quote(item, reference)
            ):
                raise ValueError("policy content reference outside input")
        for eid in assessment.evidence_ids:
            entry = evidence.get(eid)
            if entry is None or entry.design_item_version_id not in versions:
                raise ValueError("policy evidence outside input")
        if assessment.state in {"SUPPORTED", "CONTRADICTORY"} and not (
            assessment.approved_content_refs or assessment.evidence_ids
        ):
            raise ValueError("policy assessment has no approved evidence")
        if assessment.state == "EXPLICITLY_UNDECIDED" and not any(
            undecided.search(evidence[eid].text) for eid in assessment.evidence_ids
        ):
            raise ValueError("undecided policy lacks explicit original evidence")

    if (
        result.policy_assessments_truncated
        or any(a.state == "INSUFFICIENT" for a in assessments)
    ) and (
        len(policy_unassessed) != 1
        or policy_unassessed[0].reason != "EVIDENCE_INSUFFICIENT"
    ):
        raise ValueError("incomplete policy analysis marked assessed")

    matched = set()
    fingerprints = set()
    for finding in result.findings:
        if finding.check_key != POLICY_CHECK_KEY:
            if finding.policy_need_id is not None or finding.conflicting_design_refs:
                raise ValueError("policy finding outside policy check")
            continue
        assessment = by_need.get(finding.policy_need_id)
        if assessment is None:
            raise ValueError("policy finding has unknown need")
        allowed_kinds = {
            "EXPLICITLY_UNDECIDED": {"MISSING_POLICY"},
            "CONTRADICTORY": {"MISMATCH", "CONFLICT"},
        }.get(assessment.state, set())
        if (
            finding.kind not in allowed_kinds
            or finding.design_item_version_id not in assessment.design_item_version_ids
        ):
            raise ValueError("policy finding disagrees with assessment")
        if not set(finding.evidence_ids) <= set(assessment.evidence_ids):
            raise ValueError("policy finding evidence outside assessment")
        conflicts = finding.conflicting_design_refs or []
        if (finding.kind == "CONFLICT") != bool(conflicts):
            raise ValueError("policy conflict needs both approved items")
        seen_versions = {finding.design_item_version_id}
        for reference in conflicts:
            item = items.get(reference.design_item_version_id)
            if (
                item is None
                or item.design_item_id != reference.design_item_id
                or reference.design_item_version_id
                not in assessment.design_item_version_ids
                or reference.design_item_version_id in seen_versions
            ):
                raise ValueError("invalid conflicting policy reference")
            seen_versions.add(reference.design_item_version_id)
            if not _unique(reference.evidence_ids) or not set(
                reference.evidence_ids
            ) <= set(finding.evidence_ids):
                raise ValueError("conflicting policy evidence not cited")
            if any(
                evidence[eid].design_item_version_id != reference.design_item_version_id
                for eid in reference.evidence_ids
            ):
                raise ValueError("conflicting policy evidence has wrong version")
            valid_paths = {
                tuple(ref.path)
                for ref in assessment.approved_content_refs
                if ref.design_item_version_id == reference.design_item_version_id
            }
            if any(tuple(path) not in valid_paths for path in reference.content_paths):
                raise ValueError("conflicting policy content path not assessed")
            if not reference.evidence_ids and not reference.content_paths:
                raise ValueError("conflicting policy has no evidence")
        primary = finding.design_item_version_id
        if not any(
            evidence[eid].design_item_version_id == primary
            for eid in finding.evidence_ids
        ) and not any(
            ref.design_item_version_id == primary
            for ref in assessment.approved_content_refs
        ):
            raise ValueError("primary policy has no evidence")
        fingerprint = (
            finding.policy_need_id,
            finding.kind,
            primary,
            tuple(sorted(seen_versions)),
        )
        if fingerprint in fingerprints:
            raise ValueError("duplicate policy finding")
        fingerprints.add(fingerprint)
        matched.add(finding.policy_need_id)
    if POLICY_CHECK_KEY in result.checked_keys:
        required = {
            a.need_id
            for a in assessments
            if a.state in {"EXPLICITLY_UNDECIDED", "CONTRADICTORY"}
        }
        if not required <= matched:
            raise ValueError("assessed policy problem has no finding")
