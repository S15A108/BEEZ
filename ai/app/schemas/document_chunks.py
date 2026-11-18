from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field

ExternalSha256 = Annotated[str, Field(pattern=r"^(?:sha256:)?[0-9a-f]{64}$")]


class DocumentChunksRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    project_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    document_version_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    request_key: str = Field(min_length=1, max_length=128)
    kind: str
    media_type: str
    content: str
    content_hash: ExternalSha256
    text_hash: ExternalSha256
    normalization_profile: str
    # Omitted: the profile that belongs to media_type (markdown-v1 or plain-v1).
    parser_profile: str | None = None
    chunking_profile_id: str
