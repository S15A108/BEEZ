"""Explicit, bounded live check: two synthetic inputs, one paid request, no retry."""

import asyncio
import json
from decimal import Decimal

import httpx

from app.core.config import Settings
from app.schemas.retrieval import EmbeddingPolicy, EmbeddingText
from app.services.retrieval.embeddings import EmbeddingError, embed_texts
from app.services.retrieval.gms import GmsEmbeddingProvider
from app.services.retrieval.tokenization import EmbeddingTokenCounter, embedding_profile


async def main() -> int:
    settings = Settings()
    if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
        print("GMS_KEY is missing. Set it in ai/.env; do not paste it into chat.")
        return 1
    counter = EmbeddingTokenCounter()
    inputs = (
        EmbeddingText(key="document", text="프로젝트 문서는 팀장이 승인합니다."),
        EmbeddingText(key="question", text="프로젝트 문서를 누가 승인하나요?"),
    )
    if sum(counter.count(item.text) for item in inputs) > 1000:
        raise ValueError("Smoke input exceeds its token budget")
    async with httpx.AsyncClient(trust_env=False, follow_redirects=False) as client:
        try:
            result = await embed_texts(
                inputs,
                embedding_profile(settings),
                "gms-smoke",
                GmsEmbeddingProvider(client, settings.gms_key),
                counter,
                policy=EmbeddingPolicy(
                    max_attempts=1, max_batches=1, max_batch_tokens=1000
                ),
            )
        except EmbeddingError as exc:
            print(
                json.dumps(
                    {
                        "ok": False,
                        "code": exc.code,
                        "usage": [u.model_dump() for u in exc.usage],
                    }
                )
            )
            return 1
    tokens = sum(u.input_tokens or 0 for u in result.usage)
    print(
        json.dumps(
            {
                "ok": True,
                "model": result.profile.model,
                "dimension": result.profile.dimension,
                "keys": [v.key for v in result.vectors],
                "calls": len(result.usage),
                "input_tokens": tokens,
                "output_tokens": sum(u.output_tokens or 0 for u in result.usage),
                "estimated_credits": str(Decimal(tokens) * Decimal("0.0002")),
                "credit_basis": "GMS public input rate checked 2026-10-26; not a balance audit",
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
