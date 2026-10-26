from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.schemas.documents import Identifier
from app.schemas.retrieval import EmbeddingText, EmbeddingUsage, EmbeddingVector


class EmbeddingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    project_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    request_key: str = Field(min_length=1, max_length=128)
    texts: list[EmbeddingText] = Field(min_length=1, max_length=1024)
    model: Identifier | None = None
    dimension: int | None = Field(default=None, ge=1, le=1536)


class EmbeddingApiResult(BaseModel):
    model: str
    dimension: int
    vectors: tuple[EmbeddingVector, ...]


class EmbeddingResponse(BaseModel):
    result: EmbeddingApiResult
    origin: Literal["MODEL"] = "MODEL"
    model: str
    usage: tuple[EmbeddingUsage, ...]
