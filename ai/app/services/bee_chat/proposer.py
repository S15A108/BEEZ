"""Model answer proposal over the selected evidence, items and tasks (#135).

The model sees the question, the project profile, the evidence chunks with
their exact text, the approved items, the related tasks and the open
conversation, all as data in the user message. Everything it returns is
checked by the service against those inputs; an answer is never a decision
and never changes the approved design.
"""

import json
from collections.abc import Sequence
from dataclasses import dataclass, replace
from typing import Protocol, Self

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from app.schemas.bee_chat import (
    MAX_ANSWER_CHARS,
    MAX_CITATIONS,
    MAX_NEXT_ACTIONS,
    MAX_REFS,
    MAX_UNRESOLVED,
    AnswerStatus,
    BeeChatRequest,
    RetrievedEvidence,
)
from app.services.bee_chat.instance import render_system_prompt
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMMessage,
    LLMRequest,
    LLMUsage,
)

# Serialized payload bound, the same as the other internal AI routes.
MAX_PAYLOAD_BYTES = 262_144
# A history turn may be a whole answer (10,000 chars); the model needs its
# gist for a follow-up, not all of it, so each turn is cut to this length.
MAX_HISTORY_TURN_PAYLOAD_CHARS = 2000


def clip_turn(text: str) -> str:
    """The first `MAX_HISTORY_TURN_PAYLOAD_CHARS` of a turn, marked when cut."""
    if len(text) <= MAX_HISTORY_TURN_PAYLOAD_CHARS:
        return text
    return text[: MAX_HISTORY_TURN_PAYLOAD_CHARS - 1] + "…"


class _Out(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


def _bounded(values: list[str], limit: int, what: str) -> None:
    if any(not text.strip() or len(text) > limit for text in values):
        raise ValueError(f"invalid {what}")


class ModelCitation(_Out):
    evidence_id: str = Field(min_length=1, max_length=256)
    quote: str = Field(min_length=1, max_length=1000)
    claim: str | None = Field(default=None, max_length=2000)


class ModelAnswer(_Out):
    status: AnswerStatus
    # Empty for a non-answer; the service supplies the fixed text then.
    text: str = Field(max_length=MAX_ANSWER_CHARS)
    citations: list[ModelCitation] = Field(
        default_factory=list, max_length=MAX_CITATIONS
    )
    design_refs: list[str] = Field(default_factory=list, max_length=MAX_REFS)
    task_refs: list[str] = Field(default_factory=list, max_length=MAX_REFS)
    unresolved: list[str] = Field(default_factory=list, max_length=MAX_UNRESOLVED)
    next_actions: list[str] = Field(default_factory=list, max_length=MAX_NEXT_ACTIONS)

    @model_validator(mode="after")
    def bounded(self) -> Self:
        _bounded(self.design_refs, 256, "design reference")
        _bounded(self.task_refs, 256, "task reference")
        _bounded(self.unresolved, 2000, "unresolved entry")
        _bounded(self.next_actions, 2000, "next action")
        return self


@dataclass(frozen=True, slots=True)
class AnswerProposal:
    """Structurally valid model output; ids, quotes and status are unverified."""

    answer: ModelAnswer
    model: str
    usage: tuple[LLMUsage, ...]

    def invalid_output_error(self) -> LLMError:
        """Mark the last actual call failed after the service rejects the output."""
        usage = self.usage
        if usage:
            usage = (*usage[:-1], replace(usage[-1], failed=True))
        return LLMError("invalid_output", usage)


class AnswerProposer(Protocol):
    async def propose(
        self, request: BeeChatRequest, evidence: Sequence[RetrievedEvidence]
    ) -> AnswerProposal: ...


def chat_payload(request: BeeChatRequest, evidence: Sequence[RetrievedEvidence]) -> str:
    """Exactly what the model receives; only the selected evidence is shown.

    The project profile is user-written text and travels here, as data,
    never in the system message.
    """
    project = request.instance.project if request.instance is not None else None
    return json.dumps(
        {
            "question": request.question,
            "project": None
            if project is None
            else {"name": project.name, "description": project.description},
            "scope": {
                "mode": request.scope.mode,
                "baseline_id": request.scope.baseline_id,
            },
            "evidence": [
                {
                    "evidence_id": item.evidence_id,
                    "document_id": item.document_id,
                    "document_version_id": item.document_version_id,
                    "version_no": item.version_no,
                    "title": item.title,
                    "section_title": item.section_title,
                    "lines": f"L{item.locator.start_line}-L{item.locator.end_line}",
                    "text": item.text,
                }
                for item in evidence
            ],
            "approved_items": [
                {
                    "id": item.id,
                    "key": item.key,
                    "kind": item.kind,
                    "version_no": item.version_no,
                    "content": item.content.model_dump(mode="json"),
                }
                for item in request.approved_items
            ],
            "related_tasks": [
                {
                    "id": task.id,
                    "key": task.key,
                    "status": task.status,
                    "assignee_member_id": task.assignee_member_id,
                    "plan": None
                    if task.current_plan_revision is None
                    else {
                        "title": task.current_plan_revision.plan.title,
                        "goal": task.current_plan_revision.plan.goal,
                        "design_refs": [
                            {
                                "design_item_version_id": ref.design_item_version_id,
                                "relation": ref.relation,
                            }
                            for ref in task.current_plan_revision.plan.design_refs
                        ],
                    },
                }
                for task in request.related_tasks
            ],
            "known_unresolved": list(request.unresolved),
            "history": [
                {"role": turn.role, "text": clip_turn(turn.text)}
                for turn in request.history
            ],
        },
        ensure_ascii=False,
        allow_nan=False,
    )


class LLMAnswerProposer:
    """Inject the common client and an explicit call context; no default model."""

    def __init__(
        self,
        client: LLMClient,
        *,
        context: LLMContext,
        model: str,
        call_key: str,
        timeout_seconds: float,
        output_token_limit: int = 2048,
        output_token_parameter: str = "max_completion_tokens",
        allowed_response_models: tuple[str, ...] = (),
    ) -> None:
        self._client = client
        self._context = context
        self._model = model
        self._call_key = call_key
        self._timeout_seconds = timeout_seconds
        self._output_token_limit = output_token_limit
        self._output_token_parameter = output_token_parameter
        self._allowed_response_models = allowed_response_models

    async def propose(
        self, request: BeeChatRequest, evidence: Sequence[RetrievedEvidence]
    ) -> AnswerProposal:
        if (
            not isinstance(request, BeeChatRequest)
            or not isinstance(evidence, tuple | list)
            or any(not isinstance(item, RetrievedEvidence) for item in evidence)
            or not isinstance(self._context, LLMContext)
            or self._context.project_id != request.project_id
            or type(self._output_token_limit) is not int
            or not 1 <= self._output_token_limit <= 4096
            or self._output_token_parameter
            not in {"max_completion_tokens", "max_tokens"}
        ):
            raise LLMError("invalid_input")
        try:
            payload = chat_payload(request, evidence)
            if len(payload.encode("utf-8")) > MAX_PAYLOAD_BYTES:
                raise ValueError("input too large for one answer")
        except (TypeError, ValueError, UnicodeError, RecursionError):
            raise LLMError("invalid_input") from None
        response = await self._client.generate(
            LLMRequest(
                model=self._model,
                messages=(
                    LLMMessage(role="system", content=render_system_prompt()),
                    LLMMessage(role="user", content=payload),
                ),
                context=self._context,
                call_keys=(self._call_key,),
                timeout_seconds=self._timeout_seconds,
                response_format={"type": "json_object"},
                model_options={self._output_token_parameter: self._output_token_limit},
                allowed_response_models=self._allowed_response_models,
            )
        )
        try:
            answer = ModelAnswer.model_validate(response.result)
        except (ValidationError, TypeError, ValueError):
            # Preserve the real call's usage; never expose the rejected output.
            raise response.invalid_output_error() from None
        return AnswerProposal(answer, response.model, response.usage)
