"""Internal source parsing types; these are not yet a public BE/AI HTTP contract."""

from typing import Annotated, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

Sha256 = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
Identifier = Annotated[str, Field(min_length=1, max_length=256)]
DocumentKind = Literal["PLAN", "REQUIREMENTS", "API_SPEC", "ERD"]


class FrozenModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, strict=True)


class SourceInput(FrozenModel):
    project_id: Identifier
    document_version_id: Identifier
    # The pipeline reports unsupported combinations as SourceParsingError.
    kind: str
    media_type: str
    content: str
    content_hash: Sha256
    text_hash: Sha256
    normalization_version: str
    parser_profile: str


class SourceLocator(FrozenModel):
    """Normalized LF text, 1-based inclusive lines (including each line's LF)."""

    type: Literal["text_lines"] = "text_lines"
    start_line: int = Field(ge=1)
    end_line: int = Field(ge=1)

    @model_validator(mode="after")
    def ordered_lines(self) -> Self:
        if self.end_line < self.start_line:
            raise ValueError("end_line must be >= start_line")
        return self


class SourceContainer(FrozenModel):
    kind: Literal["bullet_list", "ordered_list", "list_item", "blockquote"]
    locator: SourceLocator


class SourceBlock(FrozenModel):
    block_id: str
    block_type: Literal[
        "heading",
        "paragraph",
        "list_item",
        "table_header",
        "table_row",
        "code_block",
        "reference_definition",
    ]
    locator: SourceLocator
    text_hash: Sha256
    # Ordered heading block IDs, including self for a heading. No copied context.
    heading_path: tuple[str, ...] = ()
    container_path: tuple[SourceContainer, ...] = ()
    heading_level: int | None = None
    heading_text: str | None = None
    # Parser-normalized inline Markdown, not source quotes or exact cell offsets.
    cells: tuple[str, ...] = ()
    table_header_id: str | None = None
    info: str | None = None


class ExcludedRange(FrozenModel):
    locator: SourceLocator
    reason: Literal["blank", "thematic_break", "container_marker"]


class SourceDiagnostic(FrozenModel):
    code: str
    message: str
    locator: SourceLocator


class SourceDocument(FrozenModel):
    project_id: Identifier
    document_version_id: Identifier
    kind: DocumentKind
    media_type: Literal["text/markdown", "text/plain"]
    content_hash: Sha256
    text_hash: Sha256
    normalization_version: str
    parser_profile: str
    parser_version: str
    normalized_text: str
    blocks: tuple[SourceBlock, ...]
    excluded_ranges: tuple[ExcludedRange, ...]
    diagnostics: tuple[SourceDiagnostic, ...] = ()
