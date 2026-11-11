"""Configurable generation budgets within the public response contract."""

from dataclasses import dataclass, fields

from app.core.config import Settings
from app.schemas.task_plan_drafts import (
    MAX_APPROACH_CHARS,
    MAX_EVIDENCE,
    MAX_GOAL_CHARS,
    MAX_LINK_CANDIDATES,
    MAX_LIST_ENTRY_CHARS,
    MAX_LIST_ITEMS,
    MAX_NEW_ITEMS,
    MAX_REASON_CHARS,
    MAX_UNLINKED,
)


@dataclass(frozen=True)
class DraftOutputLimits:
    goal_chars: int
    approach_chars: int
    list_items: int
    list_entry_chars: int
    evidence_items: int
    quote_chars: int
    link_candidates: int
    reason_chars: int
    new_items: int
    unlinked_items: int

    @classmethod
    def from_settings(cls, settings: Settings) -> "DraftOutputLimits":
        limits = cls(
            **{
                field.name: int(getattr(settings, f"task_plan_drafts_{field.name}"))
                for field in fields(cls)
            }
        )
        ceilings = (
            MAX_GOAL_CHARS,
            MAX_APPROACH_CHARS,
            MAX_LIST_ITEMS,
            MAX_LIST_ENTRY_CHARS,
            MAX_EVIDENCE,
            1000,
            MAX_LINK_CANDIDATES,
            MAX_REASON_CHARS,
            MAX_NEW_ITEMS,
            MAX_UNLINKED,
        )
        for field, ceiling in zip(fields(cls), ceilings, strict=True):
            value = getattr(limits, field.name)
            minimum = (
                0
                if field.name
                in {"evidence_items", "link_candidates", "new_items", "unlinked_items"}
                else 1
            )
            if not minimum <= value <= ceiling:
                raise ValueError(f"Invalid task plan draft budget: {field.name}")
        return limits
