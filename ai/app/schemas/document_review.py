"""Validated BE v2 snapshot for document review; no DB access is needed."""

import hashlib
import json
import unicodedata
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

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


def _bounded_text(value: str, limit: int, *, nonblank: bool = True) -> str:
    # Match Java String.length() and reject malformed lone surrogate codepoints.
    try:
        length = len(value.encode("utf-16-le")) // 2
    except UnicodeEncodeError as exc:
        raise ValueError("invalid Unicode text") from exc

    # Java String.isBlank() uses Character.isWhitespace, which intentionally
    # excludes non-breaking spaces that Python's str.strip() considers blank.
    def java_whitespace(char: str) -> bool:
        code = ord(char)
        return (
            0x09 <= code <= 0x0D
            or 0x1C <= code <= 0x1F
            or (
                unicodedata.category(char) in {"Zs", "Zl", "Zp"}
                and code not in {0x00A0, 0x2007, 0x202F}
            )
        )

    if length > limit or (nonblank and all(map(java_whitespace, value))):
        raise ValueError("invalid text length")
    return value


class ReviewSuggestionContext(BaseModel):
    """The already stored suggestion provided by the BE to DISCUSS."""

    model_config = ConfigDict(extra="forbid", strict=True)

    id: str = Field(pattern=ID)
    type: Literal["PATCH", "COMMENT"]
    step: Literal["BODY", "API_SPEC", "ERD", "REQ_SPEC"]
    explanation: str
    original_text: str | None = None
    replacement_text: str | None = None
    decision: Literal["PENDING", "APPLIED", "SKIPPED"] | None = None

    @model_validator(mode="after")
    def validate_suggestion(self):
        _bounded_text(self.explanation, 10000)
        if self.type == "PATCH":
            if self.original_text is None or self.replacement_text is None:
                raise ValueError("PATCH requires original and replacement text")
            # An empty replacement is a valid deletion. Original must be nonempty,
            # but whitespace may itself be the exact target span.
            if not self.original_text:
                raise ValueError("PATCH original must not be empty")
            _bounded_text(self.original_text, 100000, nonblank=False)
            _bounded_text(self.replacement_text, 100000, nonblank=False)
        elif (
            self.original_text is not None
            or self.replacement_text is not None
            or self.decision is not None
        ):
            raise ValueError("COMMENT has no patch or decision")
        return self


class ReviewChatMessage(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    author_kind: Literal["HUMAN", "BEE"]
    text: str
    proposed_text: str | None = None

    @model_validator(mode="after")
    def validate_message(self):
        _bounded_text(self.text, 5000 if self.author_kind == "HUMAN" else 10000)
        if self.proposed_text is not None:
            _bounded_text(self.proposed_text, 100000, nonblank=False)
        return self


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
    mode: Literal["REVIEW", "DISCUSS", "REQUEST"] | None = None
    suggestion: ReviewSuggestionContext | None = None
    messages: list[ReviewChatMessage] | None = Field(default=None, max_length=40)
    request_text: str | None = None

    @field_validator("request_text")
    @classmethod
    def validate_request_text(cls, value: str | None):
        if value is not None:
            _bounded_text(value, 5000)
        return value

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
        if self.mode == "DISCUSS":
            if (
                self.suggestion is None
                or not self.messages
                or self.messages[-1].author_kind != "HUMAN"
                or self.request_text is not None
            ):
                raise ValueError("invalid DISCUSS context")
        elif self.mode == "REQUEST":
            if (
                self.request_text is None
                or self.suggestion is not None
                or self.messages is not None
            ):
                raise ValueError("invalid REQUEST context")
        elif any(
            value is not None
            for value in (self.suggestion, self.messages, self.request_text)
        ):
            raise ValueError("review mode does not accept chat context")
        return self
