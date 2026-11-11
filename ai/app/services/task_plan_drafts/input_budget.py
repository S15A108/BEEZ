"""Pack ranked context without cutting quotes or spending another model call.

Uses the repository's offline, pinned cl100k tokenizer as an explicit estimate;
it is not a promise of the gateway/model's exact Chat Completions token count.
"""

from dataclasses import dataclass
from typing import Protocol

from app.schemas.task_plan_drafts import TaskPlanDraftRequest
from app.services.retrieval.tokenization import TOKENIZER_ID, EmbeddingTokenCounter


class TokenCounter(Protocol):
    def count(self, text: str) -> int: ...


@dataclass(frozen=True)
class PackedInput:
    request: TaskPlanDraftRequest
    before_tokens: int
    after_tokens: int
    tokenizer: str = TOKENIZER_ID


def pack_input(
    request: TaskPlanDraftRequest,
    system: str,
    token_limit: int,
    reserve_tokens: int,
    counter: TokenCounter | None = None,
) -> PackedInput:
    from app.services.task_plan_drafts.proposer import plan_payload

    if (
        type(token_limit) is not int
        or type(reserve_tokens) is not int
        or reserve_tokens < 0
        or token_limit <= reserve_tokens
    ):
        raise ValueError("Invalid input token budget")
    counter = counter or EmbeddingTokenCounter()
    fixed_tokens = counter.count(system) + reserve_tokens

    def count(candidate: TaskPlanDraftRequest) -> int:
        return fixed_tokens + counter.count(plan_payload(candidate))

    before = count(request)
    if before <= token_limit:
        return PackedInput(request, before, before)
    # Inputs are ranked by BE. Interleave evidence and design so that one large
    # list cannot consume the entire budget before the other gets considered.
    current = request.model_copy(update={"excerpts": (), "design_items": ()})
    for index in range(max(len(request.excerpts), len(request.design_items))):
        for field in ("excerpts", "design_items"):
            values = getattr(request, field)
            if index >= len(values):
                continue
            trial = current.model_copy(
                update={field: (*getattr(current, field), values[index])}
            )
            if count(trial) <= token_limit:
                current = trial
    if not current.excerpts:
        raise ValueError("Input token budget cannot fit any complete document excerpt")
    return PackedInput(current, before, count(current))
