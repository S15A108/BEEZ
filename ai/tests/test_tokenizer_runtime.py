import hashlib
import os
import tempfile
from pathlib import Path

import pytest
import tiktoken
import tiktoken.load
import tiktoken.registry
from fastapi.testclient import TestClient

from app.main import app
from app.routers.embeddings import get_counter
from app.services.retrieval import tokenization

VOCABULARY_URL = (
    "https://openaipublic.blob.core.windows.net/encodings/cl100k_base.tiktoken"
)
CACHE_KEY = hashlib.sha1(VOCABULARY_URL.encode()).hexdigest()


@pytest.fixture
def runtime_cache(monkeypatch, tmp_path):
    reference = tiktoken.get_encoding("cl100k_base")
    cache_directory = os.environ.get(
        "TIKTOKEN_CACHE_DIR",
        os.environ.get(
            "DATA_GYM_CACHE_DIR", str(Path(tempfile.gettempdir()) / "data-gym-cache")
        ),
    )
    vocabulary = (Path(cache_directory) / CACHE_KEY).read_bytes()
    downloads = []

    def offline_read(path):
        if path.startswith("http"):
            downloads.append(path)
            raise OSError("Network is unavailable")
        return Path(path).read_bytes()

    tokenization._encoding.cache_clear()
    get_counter.cache_clear()
    monkeypatch.setenv("TIKTOKEN_CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(tiktoken.registry, "ENCODINGS", {})
    monkeypatch.setattr(tiktoken.load, "read_file", offline_read)
    yield tmp_path / CACHE_KEY, vocabulary, reference, downloads
    tokenization._encoding.cache_clear()
    get_counter.cache_clear()


def test_cold_runtime_missing_vocabulary_fails_without_downloading(runtime_cache):
    _, _, _, downloads = runtime_cache
    with pytest.raises(ValueError, match="vocabulary"):
        tokenization.EmbeddingTokenCounter()
    assert downloads == []


def test_corrupt_vocabulary_is_rejected_without_network_repair(runtime_cache):
    path, _, _, downloads = runtime_cache
    path.write_bytes(b"corrupt vocabulary")
    with pytest.raises(ValueError, match="vocabulary"):
        tokenization.EmbeddingTokenCounter()
    assert downloads == []


def test_offline_runtime_matches_the_pinned_official_tokenizer(runtime_cache):
    path, vocabulary, reference, downloads = runtime_cache
    path.write_bytes(vocabulary)
    counter = tokenization.EmbeddingTokenCounter()
    for text in (
        "hello world",
        "사용자는 승인된 문서를 조회한다.",
        "<|endoftext|>",
        "😀 e\u0301 Ａ\n\t",
        "| key | value |\n",
        "",
    ):
        assert counter.encoding.encode_ordinary(text) == reference.encode_ordinary(text)
    assert downloads == []


def test_server_rejects_missing_vocabulary_before_becoming_ready(runtime_cache):
    _, _, _, downloads = runtime_cache
    with pytest.raises(ValueError, match="vocabulary"), TestClient(app):
        pass
    assert downloads == []


def test_server_warms_tokenizer_before_requests(runtime_cache):
    path, vocabulary, _, downloads = runtime_cache
    path.write_bytes(vocabulary)
    with TestClient(app) as client:
        path.unlink()
        assert client.get("/health").status_code == 200
        assert tokenization.EmbeddingTokenCounter().count("hello world") == 2
    assert downloads == []
