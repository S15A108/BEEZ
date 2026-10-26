from hashlib import sha256

import pytest

from app.schemas.documents import SourceInput
from app.schemas.retrieval import (
    ChunkingProfile,
    EmbeddingPolicy,
    EmbeddingProfile,
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)
from app.services.document_parsing.pipeline import parse_source
from app.services.retrieval.embeddings import EmbeddingError, EmbeddingProviderError
from app.services.retrieval.pipeline import prepare_document_index


class Counter:
    tokenizer_id = "test:characters-v1"
    max_input_tokens = 200

    def count(self, text):
        return len(text)


class Provider:
    def __init__(self, fail_at=None):
        self.calls = []
        self.fail_at = fail_at

    async def embed(self, texts, profile, purpose, call_key):
        self.calls.append((texts, purpose, call_key))
        usage = EmbeddingUsage(
            request_key=call_key,
            model=profile.model,
            input_tokens=sum(len(t.text) for t in texts),
            output_tokens=0,
            latency_ms=1,
            failed=False,
            tokens_known=True,
        )
        if len(self.calls) == self.fail_at:
            raise EmbeddingProviderError(
                "provider_unavailable",
                "test failure",
                usage=usage.model_copy(update={"failed": True}),
            )
        return ProviderEmbeddingBatch(
            profile=profile,
            usage=usage,
            vectors=tuple(
                EmbeddingVector(key=t.key, embedding=(0.5, 0.5, 0.25))
                for t in reversed(texts)
            ),
        )


def source_document():
    content = (
        "# 정책\n\n## 승인\n\n관리자가 승인한다.\n\n## 실패\n\n기존 원문을 유지한다.\n"
    )
    digest = sha256(content.encode()).hexdigest()
    return parse_source(
        SourceInput(
            project_id="p1",
            document_version_id="v1",
            kind="PLAN",
            media_type="text/markdown",
            content=content,
            content_hash=digest,
            text_hash=digest,
            normalization_version="utf8-lf-v1",
            parser_profile="markdown-v1",
        )
    )


def profile(**overrides):
    values = {
        "profile_id": "test-v1",
        "provider": "fake",
        "model": "model-a",
        "dimension": 3,
        "tokenizer_id": Counter.tokenizer_id,
        "max_input_tokens": 200,
    }
    values.update(overrides)
    return EmbeddingProfile(**values)


@pytest.mark.asyncio
async def test_parsing_to_index_artifact_has_exact_keys_profile_and_source_version():
    provider = Provider()
    result = await prepare_document_index(
        source_document(),
        ChunkingProfile(
            tokenizer_id=Counter.tokenizer_id, max_tokens=80, include_headings=True
        ),
        profile(),
        "run-1",
        provider,
        Counter(),
    )
    assert result.manifest.project_id == "p1"
    assert result.manifest.document_version_id == "v1"
    assert result.manifest.embedding_profile == profile()
    assert result.manifest.expected_chunk_keys == tuple(
        c.chunk_key for c in result.chunks
    )
    assert result.manifest.expected_chunk_keys == tuple(v.key for v in result.vectors)
    sent_text = {t.key: t.text for call in provider.calls for t in call[0]}
    assert sent_text == {c.chunk_key: c.embedding_text for c in result.chunks}
    assert all(call[1] == "document" for call in provider.calls)
    assert result.usage and not hasattr(result, "ready")


@pytest.mark.asyncio
async def test_partial_embedding_failure_does_not_create_an_index_manifest():
    provider = Provider(fail_at=2)
    with pytest.raises(EmbeddingError) as exc:
        await prepare_document_index(
            source_document(),
            ChunkingProfile(tokenizer_id=Counter.tokenizer_id, max_tokens=80),
            profile(),
            "run-1",
            provider,
            Counter(),
            policy=EmbeddingPolicy(batch_size=1),
        )
    assert len(exc.value.usage) == 2
    assert not hasattr(exc.value, "manifest")


@pytest.mark.asyncio
async def test_same_dimension_different_model_has_a_different_index_identity():
    args = (
        source_document(),
        ChunkingProfile(tokenizer_id=Counter.tokenizer_id, max_tokens=80),
    )
    first = await prepare_document_index(
        *args, profile(), "run-1", Provider(), Counter()
    )
    second = await prepare_document_index(
        *args, profile(model="model-b"), "run-2", Provider(), Counter()
    )
    assert first.manifest.index_key != second.manifest.index_key


@pytest.mark.asyncio
async def test_larger_chunk_budget_than_embedding_profile_is_rejected_before_calls():
    provider = Provider()
    with pytest.raises(EmbeddingError):
        await prepare_document_index(
            source_document(),
            ChunkingProfile(tokenizer_id=Counter.tokenizer_id, max_tokens=80),
            profile(max_input_tokens=40),
            "run-1",
            provider,
            Counter(),
        )
    assert not provider.calls
