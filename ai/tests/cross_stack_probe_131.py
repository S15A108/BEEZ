"""Test-only bridge: one synthetic JSON input through the real AI ASGI route.

This process never calls GMS. It returns only its local response on stdout.
"""

import json
import sys

from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.main import app
from app.routers import context_selection as route
from app.schemas.retrieval import (
    EmbeddingProfile,
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)


class Counter:
    tokenizer_id = "cross-fixture"
    max_input_tokens = 2048

    def count(self, text):
        return len(text)


class NoNetworkClient:
    """Match the context-manager interface while forbidding HTTP requests."""

    def __init__(self, **_kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        pass

    def stream(self, *_args, **_kwargs):
        raise AssertionError("external network access is forbidden in this test")


class Provider:
    def __init__(self, _client, _key):
        pass

    async def embed(self, texts, profile, purpose, request_key):
        def vector(text):
            if purpose == "query" or "users" in text or "사용자" in text:
                return (1.0, 0.0)
            return (0.0, 1.0)

        return ProviderEmbeddingBatch(
            profile=profile,
            vectors=tuple(
                EmbeddingVector(key=item.key, embedding=vector(item.text))
                for item in texts
            ),
            usage=EmbeddingUsage(
                request_key=request_key,
                model=profile.model,
                latency_ms=1,
                failed=False,
                tokens_known=True,
                input_tokens=sum(len(item.text) for item in texts),
                output_tokens=0,
            ),
        )


def main():
    payload = json.load(sys.stdin)
    app.dependency_overrides[get_settings] = lambda: Settings(
        _env_file=None,
        ai_internal_token="cross-stack-fixture-token",
        gms_key="cross-stack-not-a-real-key",
        context_selection_enabled=True,
        context_selection_min_similarity="0.8",
        context_selection_max_items="1",
        context_selection_max_evidence="1",
        context_selection_total_timeout_seconds="5",
    )
    route.get_counter = Counter
    route.GmsEmbeddingProvider = Provider
    route.embedding_profile = lambda _settings: EmbeddingProfile(
        profile_id="cross-fixture",
        provider="fake",
        model="cross-fixture",
        dimension=2,
        tokenizer_id="cross-fixture",
        max_input_tokens=2048,
    )
    route.httpx.AsyncClient = NoNetworkClient
    try:
        # No context manager: app lifespan is not needed for this isolated probe.
        client = TestClient(app)
        response = client.post(
            "/v1/development-context-selections",
            json=payload,
            headers={"Authorization": "Bearer cross-stack-fixture-token"},
        )
        print(json.dumps({"status": response.status_code, "body": response.json()}))
    finally:
        app.dependency_overrides.clear()


if __name__ == "__main__":
    main()
