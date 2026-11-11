"""Drop what a model draft adds beyond the configured budgets (S15P31A108-385).

A draft is paid for before it is checked, so one entry over a soft budget should
not discard the rest. Overruns are fixed by removing whole entries, never by
cutting text: a cut sentence changes its meaning, and a cut quote could no
longer be checked against the document. Only moderate overruns (up to
TOLERANCE times the budget) are repaired; a model that ignores the budgets by
more than that is treated as broken and the draft is rejected as before.
The input is never modified, and nothing outside the configured budgets
changes, so structure, id and quote checks stay as strict as before.
"""

import copy
from typing import Any

from app.services.task_plan_drafts.limits import DraftOutputLimits

TOLERANCE = 2


class _Reject(Exception):
    """An overrun is beyond what is repaired; leave the output untouched."""


def _bound(limit: int) -> int:
    # Doubling means nothing for a budget of 0 or 1, so allow two more entries.
    return max(limit * TOLERANCE, limit + 2)


def _fit_entries(entries: Any, limit: int, dropped: dict[str, int], name: str) -> Any:
    """Keep the first `limit` entries of a list."""
    if not isinstance(entries, list):
        return entries
    if len(entries) > _bound(limit):
        raise _Reject
    if len(entries) > limit:
        dropped[name] = dropped.get(name, 0) + len(entries) - limit
        return entries[:limit]
    return entries


def _drop_long(
    entries: Any, limit: int, key: str | None, dropped: dict[str, int], name: str
) -> Any:
    """Remove entries whose text is longer than `limit` characters."""
    if not isinstance(entries, list):
        return entries
    kept = []
    for entry in entries:
        text = (
            entry
            if key is None
            else entry.get(key)
            if isinstance(entry, dict)
            else None
        )
        if isinstance(text, str) and len(text) > limit:
            if len(text) > _bound(limit):
                raise _Reject
            dropped[name] = dropped.get(name, 0) + 1
            continue
        kept.append(entry)
    return kept


def trim_output(raw: Any, limits: DraftOutputLimits) -> tuple[Any, dict[str, int]]:
    """Return (output within budgets, dropped counts by name).

    Output of an unexpected shape, or overrunning a budget by more than the
    tolerance, is returned unchanged with no counts, so validation rejects it.
    """
    if not isinstance(raw, dict) or not isinstance(raw.get("plan"), dict):
        return raw, {}
    out = copy.deepcopy(raw)
    dropped: dict[str, int] = {}
    try:
        plan = out["plan"]
        for name, limit in (
            ("goal", limits.goal_chars),
            ("approach", limits.approach_chars),
        ):
            value = plan.get(name)
            if isinstance(value, str) and len(value) > limit:
                if len(value) > _bound(limit):
                    raise _Reject
                plan[name] = ""
                dropped[name] = 1
        for name in ("included_scope", "excluded_scope", "completion_criteria"):
            if name in plan:
                entries = _drop_long(
                    plan[name], limits.list_entry_chars, None, dropped, name
                )
                plan[name] = _fit_entries(entries, limits.list_items, dropped, name)
        if "evidence" in out:
            entries = _drop_long(
                out["evidence"], limits.quote_chars, "quote", dropped, "evidence"
            )
            out["evidence"] = _fit_entries(
                entries, limits.evidence_items, dropped, "evidence"
            )
        if "design_ref_candidates" in out:
            entries = _drop_long(
                out["design_ref_candidates"],
                limits.reason_chars,
                "reason",
                dropped,
                "design_ref_candidates",
            )
            out["design_ref_candidates"] = _fit_entries(
                entries, limits.link_candidates, dropped, "design_ref_candidates"
            )
        if "new_design_items" in out:
            items = _fit_entries(
                out["new_design_items"], limits.new_items, dropped, "new_design_items"
            )
            if isinstance(items, list):
                for item in items:
                    if isinstance(item, dict) and "evidence" in item:
                        item["evidence"] = _drop_long(
                            item["evidence"],
                            limits.quote_chars,
                            "quote",
                            dropped,
                            "new_item_evidence",
                        )
            out["new_design_items"] = items
        if "unlinked" in out:
            out["unlinked"] = _fit_entries(
                out["unlinked"], limits.unlinked_items, dropped, "unlinked"
            )
    except _Reject:
        return raw, {}
    return out, dropped
