"""Internal retrieval artifacts; publishing an index is a separate BE operation."""

from typing import Literal, Self

from pydantic import Field, model_validator

from app.schemas.documents import (
    ExcludedRange,
    FrozenModel,
    Identifier,
    Sha256,
    SourceLocator,
)


class ChunkingProfile(FrozenModel):
    profile_id: Identifier = "structure-v1"
    tokenizer_id: Identifier
    max_tokens: int = Field(default=600, ge=1, le=65_536)
    include_headings: bool = False
    include_table_headers: bool = False
    max_chunks: int = Field(default=1024, ge=1, le=4096)


class SourceSpan(FrozenModel):
    block_id: str
    # Absolute Unicode code point offsets in normalized_text; end is exclusive.
    start_offset: int = Field(ge=0)
    end_offset: int = Field(gt=0)
    locator: SourceLocator

    @model_validator(mode="after")
    def ordered_offsets(self) -> Self:
        if self.end_offset <= self.start_offset:
            raise ValueError("end_offset must exceed start_offset")
        return self


class ContextReference(FrozenModel):
    block_id: str
    role: Literal["heading", "table_header"]
    locator: SourceLocator
    text_hash: Sha256
    applied: bool


class RetrievalChunk(FrozenModel):
    chunk_key: str
    chunk_no: int = Field(ge=0)
    document_version_id: Identifier
    text: str
    source_spans: tuple[SourceSpan, ...]
    context_refs: tuple[ContextReference, ...]
    embedding_text: str
    token_count: int = Field(gt=0)


class ChunkManifest(FrozenModel):
    project_id: Identifier
    document_version_id: Identifier
    content_hash: Sha256
    text_hash: Sha256
    normalization_version: str
    parser_profile: str
    parser_version: str
    chunker_version: str
    chunking_profile: ChunkingProfile
    expected_chunk_keys: tuple[str, ...]


class ChunkBatch(FrozenModel):
    manifest: ChunkManifest
    chunks: tuple[RetrievalChunk, ...]
    excluded_ranges: tuple[ExcludedRange, ...]


class EmbeddingProfile(FrozenModel):
    profile_id: Identifier
    provider: Identifier
    model: Identifier
    dimension: int = Field(ge=1, le=16_384)
    tokenizer_id: Identifier
    max_input_tokens: int = Field(ge=1, le=65_536)
    preprocessing_version: Literal["identity-v1"] = "identity-v1"
    distance: Literal["cosine"] = "cosine"
    scalar_type: Literal["float32"] = "float32"


class EmbeddingPolicy(FrozenModel):
    batch_size: int = Field(default=64, ge=1, le=2048)
    max_batch_tokens: int = Field(default=60_000, ge=1, le=300_000)
    max_batches: int = Field(default=128, ge=1, le=1024)
    max_texts: int = Field(default=1024, ge=1, le=4096)
    max_attempts: int = Field(default=2, ge=1, le=3)
    call_timeout_seconds: float = Field(default=20.0, gt=0, le=120)
    total_timeout_seconds: float = Field(default=90.0, gt=0, le=300)
    retry_delay_seconds: float = Field(default=0.5, ge=0, le=10)
    retry_timeouts: bool = False


class EmbeddingText(FrozenModel):
    key: Identifier
    text: str = Field(min_length=1)


class EmbeddingVector(FrozenModel):
    key: Identifier
    # Validated in the service so usage survives NaN/dimension/zero-norm failures.
    embedding: tuple[float, ...]


class EmbeddingUsage(FrozenModel):
    request_key: Identifier
    feature: Literal["EMBEDDING"] = "EMBEDDING"
    model: Identifier
    input_tokens: int | None = Field(default=None, ge=0)
    output_tokens: int | None = Field(default=None, ge=0)
    latency_ms: int = Field(ge=0)
    failed: bool
    tokens_known: bool

    @model_validator(mode="after")
    def known_tokens(self) -> Self:
        if self.tokens_known and (
            self.input_tokens is None or self.output_tokens is None
        ):
            raise ValueError("Known usage requires input and output token counts")
        if not self.tokens_known and (
            self.input_tokens is not None or self.output_tokens is not None
        ):
            raise ValueError("Unknown usage must not invent token counts")
        return self


class ProviderEmbeddingBatch(FrozenModel):
    profile: EmbeddingProfile
    vectors: tuple[EmbeddingVector, ...]
    usage: EmbeddingUsage


class EmbeddingResult(FrozenModel):
    profile: EmbeddingProfile
    purpose: Literal["document", "query"]
    vectors: tuple[EmbeddingVector, ...]
    usage: tuple[EmbeddingUsage, ...]


class IndexManifest(ChunkManifest):
    embedding_profile: EmbeddingProfile
    index_key: Sha256


class PreparedIndex(FrozenModel):
    """Prepared only: BE must validate run/scope and atomically publish this set."""

    manifest: IndexManifest
    chunks: tuple[RetrievalChunk, ...]
    vectors: tuple[EmbeddingVector, ...]
    excluded_ranges: tuple[ExcludedRange, ...]
    usage: tuple[EmbeddingUsage, ...]
