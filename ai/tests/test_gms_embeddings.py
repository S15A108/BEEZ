import json
from hashlib import sha256
from pathlib import Path

import httpx
import pytest
from pydantic import SecretStr

from app.core.config import Settings
from app.schemas.documents import SourceInput
from app.schemas.retrieval import ChunkingProfile, EmbeddingPolicy, EmbeddingText
from app.services.document_parsing.normalization import normalize_text, text_digest
from app.services.document_parsing.pipeline import parse_source
from app.services.retrieval.embeddings import EmbeddingError, embed_texts
from app.services.retrieval.gms import GMS_EMBEDDINGS_URL, GmsEmbeddingProvider
from app.services.retrieval.pipeline import prepare_document_index
from app.services.retrieval.tokenization import EmbeddingTokenCounter, embedding_profile


def profile():
    return embedding_profile(Settings(_env_file=None, gms_embedding_dimension=3))


def payload():
    return {
        "object": "list",
        "model": "text-embedding-3-small",
        "data": [
            {"object": "embedding", "index": 1, "embedding": [0.0, 1.0, 0.0]},
            {"object": "embedding", "index": 0, "embedding": [1.0, 0.0, 0.0]},
        ],
        "usage": {"prompt_tokens": 7, "total_tokens": 7},
    }


async def call_provider(handler, *, policy=None):
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        return await embed_texts(
            (EmbeddingText(key="a", text="정책"), EmbeddingText(key="b", text="조회")),
            profile(),
            "run-1",
            GmsEmbeddingProvider(client, SecretStr("test-gms-key")),
            EmbeddingTokenCounter(),
            policy=policy,
        )


@pytest.mark.asyncio
async def test_gms_wire_contract_maps_index_to_key_and_preserves_usage():
    requests = []

    def handler(request):
        requests.append(request)
        assert str(request.url) == GMS_EMBEDDINGS_URL
        assert request.headers["authorization"] == "Bearer test-gms-key"
        assert json.loads(request.content) == {
            "model": "text-embedding-3-small",
            "input": ["정책", "조회"],
            "dimensions": 3,
            "encoding_format": "float",
        }
        return httpx.Response(200, json=payload())

    result = await call_provider(handler)
    assert len(requests) == 1
    assert result.vectors[0].key == "a"
    assert result.vectors[0].embedding == (1.0, 0.0, 0.0)
    assert result.usage[0].input_tokens == 7
    assert result.usage[0].output_tokens == 0
    assert result.usage[0].tokens_known


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "case",
    [
        "duplicate",
        "negative",
        "boolean",
        "missing",
        "model",
        "base64",
        "numeric-string",
        "nan",
        "usage",
    ],
)
async def test_invalid_response_fails_without_retry_or_partial_vectors(case):
    requests = []

    def handler(request):
        requests.append(request)
        data = payload()
        if case == "duplicate":
            data["data"][1]["index"] = 1
        elif case == "negative":
            data["data"][1]["index"] = -1
        elif case == "boolean":
            data["data"][1]["index"] = True
        elif case == "missing":
            data["data"].pop()
        elif case == "model":
            data["model"] = "text-embedding-3-large"
        elif case == "base64":
            data["data"][0]["embedding"] = "AAAA"
        elif case == "numeric-string":
            data["data"][0]["embedding"][0] = "0.5"
        elif case == "nan":
            data["data"][0]["embedding"][0] = float("nan")
        elif case == "usage":
            data["usage"]["prompt_tokens"] = True
        return httpx.Response(200, content=json.dumps(data))

    with pytest.raises(EmbeddingError) as caught:
        await call_provider(handler)
    assert len(requests) == 1
    assert len(caught.value.usage) == 1
    if case != "usage":
        assert caught.value.usage[0].input_tokens == 7
    else:
        assert not caught.value.usage[0].tokens_known


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "status, attempts", [(401, 1), (403, 1), (400, 1), (429, 2), (503, 2), (302, 1)]
)
async def test_http_failures_are_bounded_and_do_not_expose_provider_body(
    status, attempts
):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(
            status,
            json={"error": {"message": "test-gms-key private text"}},
            headers={"location": "https://example.com"},
        )

    with pytest.raises(EmbeddingError) as caught:
        await call_provider(handler, policy=EmbeddingPolicy(retry_delay_seconds=0))
    assert len(calls) == attempts
    assert len(caught.value.usage) == attempts
    assert all(not usage.tokens_known for usage in caught.value.usage)
    assert "test-gms-key" not in str(caught.value)
    assert "private text" not in str(caught.value)


@pytest.mark.asyncio
async def test_timeout_is_not_retried_and_usage_remains_unknown():
    calls = []

    def handler(request):
        calls.append(request)
        raise httpx.ReadTimeout("private request", request=request)

    with pytest.raises(EmbeddingError, match="no partial") as caught:
        await call_provider(handler)
    assert caught.value.code == "embedding_timeout"
    assert len(calls) == 1
    assert caught.value.usage[0].input_tokens is None


def test_real_tokenizer_handles_literal_special_token_and_invalid_unicode():
    counter = EmbeddingTokenCounter()
    assert counter.count("hello world") == 2
    assert counter.count("<|endoftext|>") > 1
    assert counter.count("한글 API 👩‍💻") > 0
    with pytest.raises(ValueError):
        counter.count("\ud800")
    assert profile().tokenizer_id == counter.tokenizer_id
    assert profile().max_input_tokens == counter.max_input_tokens


def test_selected_models_and_secret_handling():
    settings = Settings(_env_file=None, gms_key="never-display-this")
    assert settings.bee_default_model == "gpt-6-luna"
    assert settings.bee_complex_model == "gpt-6.1-sol"
    assert settings.gms_embedding_model == "text-embedding-3-small"
    assert settings.gms_embedding_dimension == 1024
    assert "never-display-this" not in repr(settings)


@pytest.mark.asyncio
async def test_real_tokenizer_and_http_adapter_prepare_a_complete_source_index():
    raw = (Path(__file__).parent / "fixtures/parsing/api-spec.md").read_bytes()
    text = raw.decode("utf-8")
    document = parse_source(
        SourceInput(
            project_id="101",
            document_version_id="211",
            kind="API_SPEC",
            media_type="text/markdown",
            content=text,
            content_hash=sha256(raw).hexdigest(),
            text_hash=text_digest(normalize_text(text)),
            normalization_version="utf8-lf-v1",
            parser_profile="markdown-v1",
        )
    )
    settings = Settings(_env_file=None)
    selected = embedding_profile(settings)
    counter = EmbeddingTokenCounter()
    calls = []

    def handler(request):
        data = json.loads(request.content)
        calls.append(data)
        total = sum(counter.count(value) for value in data["input"])
        return httpx.Response(
            200,
            json={
                "object": "list",
                "model": selected.model,
                "data": [
                    {
                        "object": "embedding",
                        "index": i,
                        "embedding": [1.0] + [0.0] * 1023,
                    }
                    for i in reversed(range(len(data["input"])))
                ],
                "usage": {"prompt_tokens": total, "total_tokens": total},
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        result = await prepare_document_index(
            document,
            ChunkingProfile(tokenizer_id=counter.tokenizer_id, max_tokens=80),
            selected,
            "source-run",
            GmsEmbeddingProvider(client, SecretStr("test")),
            counter,
            policy=EmbeddingPolicy(batch_size=2),
        )
    assert len(calls) > 1
    assert all(counter.count(c.embedding_text) <= 80 for c in result.chunks)
    assert [v.key for v in result.vectors] == list(result.manifest.expected_chunk_keys)
    assert all(len(v.embedding) == 1024 for v in result.vectors)
    assert result.manifest.embedding_profile == selected
    assert result.manifest.document_version_id == "211"
