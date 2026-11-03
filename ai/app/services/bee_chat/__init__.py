"""Bee: instance initialization and grounded design Q&A (#135)."""

from .instance import (
    GREETING,
    PERSONA,
    SCOPE_STATEMENT,
    build_instance,
    render_system_prompt,
)
from .proposer import (
    MAX_PAYLOAD_BYTES,
    AnswerProposal,
    AnswerProposer,
    LLMAnswerProposer,
    ModelAnswer,
    ModelCitation,
    chat_payload,
)
from .service import (
    INSUFFICIENT_TEXT,
    OUT_OF_SCOPE_TEXT,
    REVIEW_NOTICE,
    UNGROUNDED_NOTE,
    AnswerRejected,
    BeeChatOutcome,
    evidence_location,
    generate_bee_chat,
    renumber_markers,
    select_evidence,
    verify_answer,
)

__all__ = [
    "GREETING",
    "INSUFFICIENT_TEXT",
    "MAX_PAYLOAD_BYTES",
    "OUT_OF_SCOPE_TEXT",
    "PERSONA",
    "REVIEW_NOTICE",
    "SCOPE_STATEMENT",
    "UNGROUNDED_NOTE",
    "AnswerProposal",
    "AnswerProposer",
    "AnswerRejected",
    "BeeChatOutcome",
    "LLMAnswerProposer",
    "ModelAnswer",
    "ModelCitation",
    "build_instance",
    "chat_payload",
    "evidence_location",
    "generate_bee_chat",
    "render_system_prompt",
    "renumber_markers",
    "select_evidence",
    "verify_answer",
]
