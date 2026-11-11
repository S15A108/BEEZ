"""Drop entries a model draft adds beyond the configured count budgets (S15P31A108-385).

A draft is paid for before it is checked, so one extra entry should not discard the rest.
Only COUNTS are enforced here: extra entries are removed from the end, whole, never cut.
Character budgets (goal, approach, list entry, quote, reason) are guidance in the prompt
only. A model that writes a little longer has already spent those output tokens, so
discarding the text saves nothing and loses a usable answer; the public contract
ceilings (ModelDraft, the response schema) still reject anything beyond what the page
accepts. A model that ignores a count budget by more than TOLERANCE times is treated as
broken and the draft is rejected as before. The input is never modified, and structure,
id and quote checks stay as strict as before.
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


def trim_output(raw: Any, limits: DraftOutputLimits) -> tuple[Any, dict[str, int]]:
    """Return (output within count budgets, dropped counts by name).

    Output of an unexpected shape, or overrunning a count budget by more than the
    tolerance, is returned unchanged with no counts, so validation rejects it.
    """
    if not isinstance(raw, dict) or not isinstance(raw.get("plan"), dict):
        return raw, {}
    out = copy.deepcopy(raw)
    dropped: dict[str, int] = {}
    try:
        plan = out["plan"]
        for name in ("included_scope", "excluded_scope", "completion_criteria"):
            if name in plan:
                plan[name] = _fit_entries(plan[name], limits.list_items, dropped, name)
        for name, limit in (
            ("evidence", limits.evidence_items),
            ("design_ref_candidates", limits.link_candidates),
            ("new_design_items", limits.new_items),
            ("unlinked", limits.unlinked_items),
        ):
            if name in out:
                out[name] = _fit_entries(out[name], limit, dropped, name)
    except _Reject:
        return raw, {}
    return out, dropped
