"""Provisional BE-to-AI recommendation input. BE owns authorization and search."""

import json
from typing import Annotated, Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

TextId = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=128)
]
Description = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)
]


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Source(ContractModel):
    document_id: TextId
    document_version_id: TextId
    version_no: int = Field(ge=1)
    # Preserve BE's saved location verbatim, including an unverified location.
    location: Annotated[str, StringConstraints(max_length=10000)] | None = None


class DesignItemVersion(ContractModel):
    id: TextId
    design_item_id: TextId
    key: TextId
    kind: Literal[
        "REQUIREMENT", "API_CONTRACT", "DATA_CONTRACT", "POLICY", "CONSTRAINT"
    ]
    version_no: int = Field(ge=1)
    status: Literal["APPROVED"]
    schema_version: int = Field(ge=1)
    content: dict[str, Any]
    source: Source | None = None
    content_hash: str | None = None

    @model_validator(mode="after")
    def bounded_content(self) -> Self:
        try:
            encoded = json.dumps(self.content, ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError, RecursionError) as exc:
            raise ValueError("invalid design content") from exc
        if len(encoded.encode("utf-8")) > 1_048_576:
            raise ValueError("design content exceeds transport limit")
        return self


class SearchEvidence(ContractModel):
    source: Source
    verification: Literal["VERIFIED", "UNVERIFIED"] = "UNVERIFIED"
    # Sharing a document version is a candidate link, not direct proof.
    association: Literal["SAME_DOCUMENT_VERSION", "EXPLICIT_LINK"]


class RecommendationCandidate(ContractModel):
    design_item_version: DesignItemVersion
    design_source_verification: Literal["VERIFIED", "UNVERIFIED"] = "UNVERIFIED"
    search_evidence: list[SearchEvidence] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def valid_verification(self) -> Self:
        source = self.design_item_version.source
        if self.design_source_verification == "VERIFIED" and (
            source is None or not source.location or not source.location.strip()
        ):
            raise ValueError("verified design source requires a location")
        for evidence in self.search_evidence:
            if evidence.verification == "VERIFIED" and (
                not evidence.source.location or not evidence.source.location.strip()
            ):
                raise ValueError("verified search evidence requires a location")
            if evidence.association == "SAME_DOCUMENT_VERSION" and (
                source is None
                or source.document_version_id != evidence.source.document_version_id
                or source.document_id != evidence.source.document_id
                or source.version_no != evidence.source.version_no
            ):
                raise ValueError(
                    "same document version association must match saved source"
                )
        return self


class OmittedCandidate(ContractModel):
    design_item_version_id: TextId
    # BE may know only the version ID; never infer a key for unseen candidates.
    key: TextId | None = None
    reason: Literal["CONTENT_TOO_LARGE", "CANDIDATE_LIMIT", "OTHER"]


class OmissionInfo(ContractModel):
    identified_candidates: list[OmittedCandidate] = Field(
        default_factory=list, max_length=100
    )
    unexamined_scope: bool = False
    # Total unique unexamined candidates across all unseen scopes; None if unknown.
    unexamined_count: int | None = Field(default=None, ge=1)


class RecommendationRequest(ContractModel):
    project_id: TextId
    request_key: TextId
    call_key: TextId
    request: Description
    task_id: TextId | None = None
    search_status: Literal["READY", "NOT_READY", "FAILED"]
    search_truncated: bool
    mapping_complete: bool
    search_observation: Literal["UNKNOWN", "NO_DOCUMENTS", "DOCUMENTS_FOUND"] = (
        "UNKNOWN"
    )
    unlinked_documents: bool = False
    omissions: OmissionInfo = Field(default_factory=OmissionInfo)
    execution_budget_ms: int = Field(ge=1, le=60_000)
    # A separate hard transport bound protects the service; 20 candidates may
    # be analyzed and the remaining known IDs are reported as omitted.
    candidates: list[RecommendationCandidate] = Field(max_length=100)

    @model_validator(mode="after")
    def distinct_versions(self) -> Self:
        versions = [item.design_item_version.id for item in self.candidates]
        items = [item.design_item_version.design_item_id for item in self.candidates]
        if len(set(versions)) != len(versions) or len(set(items)) != len(items):
            raise ValueError("duplicate candidate")
        if self.search_status != "READY" and self.candidates:
            raise ValueError("failed or unavailable search cannot provide candidates")
        omitted_ids = [
            item.design_item_version_id for item in self.omissions.identified_candidates
        ]
        if len(set(omitted_ids)) != len(omitted_ids) or set(omitted_ids) & set(
            versions
        ):
            raise ValueError("duplicate or conflicting omitted candidate")
        if self.search_observation == "NO_DOCUMENTS" and self.unlinked_documents:
            raise ValueError("no search documents cannot include unlinked documents")
        if self.search_observation == "NO_DOCUMENTS" and any(
            item.search_evidence for item in self.candidates
        ):
            raise ValueError("no search documents cannot include search evidence")
        if (
            self.omissions.unexamined_count is not None
            and not self.omissions.unexamined_scope
        ):
            raise ValueError("unexamined count requires unexamined scope")
        if (
            self.search_status == "READY"
            and self.search_observation == "DOCUMENTS_FOUND"
            and self.mapping_complete
            and not self.unlinked_documents
            and not self.candidates
            and not self.omissions.identified_candidates
            and not self.omissions.unexamined_scope
            and not self.search_truncated
        ):
            raise ValueError(
                "documents found without candidates or accounted omissions"
            )
        return self


class ModelRecommendation(ContractModel):
    design_item_version_id: TextId
    reason: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)
    ]


class ModelOutput(ContractModel):
    recommendations: list[ModelRecommendation] = Field(max_length=20)
