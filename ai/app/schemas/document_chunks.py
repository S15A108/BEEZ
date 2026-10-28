from pydantic import BaseModel, ConfigDict, Field

from app.schemas.documents import Sha256


class DocumentChunksRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    project_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    document_version_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    request_key: str = Field(min_length=1, max_length=128)
    kind: str
    media_type: str
    content: str
    content_hash: Sha256
    text_hash: Sha256
    normalization_profile: str
    parser_profile: str = "markdown-v1"
    chunking_profile_id: str
