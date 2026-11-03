"""Review candidates and supplement questions for one fixed document version (#133)."""

from .proposer import (
    MAX_MODEL_CANDIDATES,
    MAX_PAYLOAD_BYTES,
    CandidateProposal,
    CandidateProposer,
    LLMCandidateProposer,
    ModelCandidate,
    ModelEvidence,
    ModelProposal,
    ModelQuestion,
    proposal_payload,
)
from .rules import QuestionDraft, block_location, detect_rule_candidates
from .service import (
    PlanningQuestionOutcome,
    compose_result,
    generate_planning_questions,
    merge_items,
    model_candidate_key,
    order_items,
    priority_score,
    verify_model_candidate,
)

__all__ = [
    "MAX_MODEL_CANDIDATES",
    "MAX_PAYLOAD_BYTES",
    "CandidateProposal",
    "CandidateProposer",
    "LLMCandidateProposer",
    "ModelCandidate",
    "ModelEvidence",
    "ModelProposal",
    "ModelQuestion",
    "PlanningQuestionOutcome",
    "QuestionDraft",
    "block_location",
    "compose_result",
    "detect_rule_candidates",
    "generate_planning_questions",
    "merge_items",
    "model_candidate_key",
    "order_items",
    "priority_score",
    "proposal_payload",
    "verify_model_candidate",
]
