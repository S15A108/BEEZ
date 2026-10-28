from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.schemas.documents import Identifier
from app.schemas.retrieval import EmbeddingText, EmbeddingUsage, EmbeddingVector


class EmbeddingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    project_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    request_key: str = Field(min_length=1, max_length=128)
    texts: list[EmbeddingText] = Field(min_length=1, max_length=1024)
    model: Identifier | None = None
    dimension: int | None = Field(default=None, ge=1, le=1536)
    embedding_profile_id: Identifier | None = None
    purpose: Literal["DOCUMENT", "QUERY"] | None = None

    @model_validator(mode="after")
    def paired_profile_and_purpose(self) -> Self:
        fields = {"embedding_profile_id", "purpose"} & self.model_fields_set
        if fields and (
            fields != {"embedding_profile_id", "purpose"}
            or self.embedding_profile_id is None
            or self.purpose is None
        ):
            raise ValueError(
                "embedding_profile_id and purpose must be supplied together"
            )
        return self


class EmbeddingApiResult(BaseModel):
    model: str
    dimension: int
    vectors: tuple[EmbeddingVector, ...]
    embedding_profile_id: str | None = None
    purpose: Literal["DOCUMENT", "QUERY"] | None = None


class EmbeddingResponse(BaseModel):
    result: EmbeddingApiResult
    origin: Literal["MODEL"] = "MODEL"
    model: str
    usage: tuple[EmbeddingUsage, ...]
