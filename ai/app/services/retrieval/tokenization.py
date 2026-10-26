from functools import lru_cache
from importlib.metadata import version

import tiktoken

from app.core.config import Settings
from app.schemas.retrieval import EmbeddingProfile

TOKENIZER_VERSION = "0.14.0"
TOKENIZER_ID = f"tiktoken:{TOKENIZER_VERSION}:cl100k_base:ordinary-v1"
MAX_INPUT_TOKENS = 8192


@lru_cache
def _encoding() -> tiktoken.Encoding:
    if version("tiktoken") != TOKENIZER_VERSION:
        raise ValueError("Tokenizer version differs from the pinned profile")
    return tiktoken.get_encoding("cl100k_base")


class EmbeddingTokenCounter:
    tokenizer_id = TOKENIZER_ID
    max_input_tokens = MAX_INPUT_TOKENS

    def __init__(self) -> None:
        self.encoding = _encoding()

    def count(self, text: str) -> int:
        # Reject malformed Unicode instead of tiktoken's replacement fallback.
        text.encode("utf-8", errors="strict")
        return len(self.encoding.encode_ordinary(text))


def embedding_profile(settings: Settings) -> EmbeddingProfile:
    return EmbeddingProfile(
        profile_id=f"gms-small-{settings.gms_embedding_dimension}-v1",
        provider="gms-openai",
        model=settings.gms_embedding_model,
        dimension=settings.gms_embedding_dimension,
        tokenizer_id=TOKENIZER_ID,
        max_input_tokens=MAX_INPUT_TOKENS,
    )
