import json

from app.schemas.documents import SourceDocument
from app.schemas.retrieval import (
    ChunkingProfile,
    EmbeddingPolicy,
    EmbeddingProfile,
    EmbeddingText,
    IndexManifest,
    PreparedIndex,
)
from app.services.document_parsing.normalization import text_digest
from app.services.retrieval.chunking import TokenCounter, build_chunks
from app.services.retrieval.embeddings import (
    EmbeddingError,
    EmbeddingProvider,
    embed_texts,
)


async def prepare_document_index(
    document: SourceDocument,
    chunking_profile: ChunkingProfile,
    embedding_profile: EmbeddingProfile,
    request_key: str,
    provider: EmbeddingProvider,
    token_counter: TokenCounter,
    *,
    policy: EmbeddingPolicy | None = None,
) -> PreparedIndex:
    if (
        chunking_profile.tokenizer_id != embedding_profile.tokenizer_id
        or chunking_profile.max_tokens > embedding_profile.max_input_tokens
    ):
        raise EmbeddingError(
            "embedding_profile_mismatch", "Chunk and embedding token budgets disagree."
        )
    batch = build_chunks(document, chunking_profile, token_counter)
    inputs = tuple(
        EmbeddingText(key=chunk.chunk_key, text=chunk.embedding_text)
        for chunk in batch.chunks
    )
    result = await embed_texts(
        inputs,
        embedding_profile,
        request_key,
        provider,
        token_counter,
        purpose="document",
        policy=policy,
    )
    index_key = text_digest(
        json.dumps(
            {
                "source": batch.manifest.model_dump(mode="json"),
                "embedding": embedding_profile.model_dump(mode="json"),
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    )
    manifest = IndexManifest(
        **batch.manifest.model_dump(),
        embedding_profile=embedding_profile,
        index_key=index_key,
    )
    return PreparedIndex(
        manifest=manifest,
        chunks=batch.chunks,
        vectors=result.vectors,
        excluded_ranges=batch.excluded_ranges,
        usage=result.usage,
    )
