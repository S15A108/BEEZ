"""Validated BE v2 snapshot for document review; no DB access is needed."""

import hashlib
import json
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

KINDS = ("API_SPEC", "ERD", "REQ_SPEC")
ID = r"^[1-9][0-9]{0,18}$"
HASH = r"^sha256:[0-9a-f]{64}$"


def text_hash(content: str) -> str:
    return "sha256:" + hashlib.sha256(content.encode("utf-8")).hexdigest()


class Reference(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    reference_id: str = Field(min_length=1, max_length=128)
    document_id: str = Field(pattern=ID)
    document_version_id: str = Field(pattern=ID)
    kind: Literal["PLAN", "API_SPEC", "ERD", "REQ_SPEC"]
    version_no: int = Field(ge=1, le=2147483647)
    status: Literal["APPROVED"]
    content: str
    content_hash: str = Field(pattern=HASH)

    @model_validator(mode="after")
    def validate_snapshot(self):
        if (
            self.reference_id != f"document-version-{self.document_version_id}"
            or self.content_hash != text_hash(self.content)
        ):
            raise ValueError("invalid reference snapshot")
        return self


def reference_hash(references: list[Reference]) -> str:
    fields = (
        "reference_id",
        "document_id",
        "document_version_id",
        "kind",
        "version_no",
        "status",
        "content_hash",
    )
    snapshots = [
        {key: getattr(ref, key) for key in fields}
        for ref in sorted(references, key=lambda ref: ref.reference_id)
    ]
    canonical = json.dumps(
        snapshots,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    )
    return text_hash(canonical)


class DocumentReviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    contract_version: Literal[2]
    project_id: str = Field(pattern=ID)
    document_id: str = Field(pattern=ID)
    revision: int = Field(ge=1, le=9223372036854775807)
    kind: Literal["PLAN", "API_SPEC", "ERD", "REQ_SPEC"]
    content: str = Field(min_length=1)
    content_hash: str = Field(pattern=HASH)
    reference_hash: str = Field(pattern=HASH)
    request_key: str = Field(min_length=1, max_length=128)
    unavailable_references: list[Literal["API_SPEC", "ERD", "REQ_SPEC"]] = Field(
        max_length=3
    )
    references: list[Reference] = Field(max_length=100)

    @model_validator(mode="after")
    def validate_snapshot(self):
        if not self.request_key.strip() or any(
            ord(c) < 32 or 127 <= ord(c) <= 159 for c in self.request_key
        ):
            raise ValueError("invalid request key")
        ids = [ref.reference_id for ref in self.references]
        if len(set(ids)) != len(ids):
            raise ValueError("duplicate references")
        expected_missing = {
            kind
            for kind in KINDS
            if kind != self.kind
            and not any(ref.kind == kind for ref in self.references)
        }
        if (
            len(self.unavailable_references) != len(set(self.unavailable_references))
            or set(self.unavailable_references) != expected_missing
            or self.content_hash != text_hash(self.content)
            or self.reference_hash != reference_hash(self.references)
        ):
            raise ValueError("invalid snapshot")
        return self
