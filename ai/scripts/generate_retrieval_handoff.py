"""Generate synthetic BE handoff data with real AI artifact code, no model calls."""

import argparse
import asyncio
import json
from pathlib import Path

from app.schemas.documents import SourceInput
from app.schemas.retrieval import (
    ChunkBatch,
    ChunkingProfile,
    ChunkManifest,
    EmbeddingProfile,
    EmbeddingUsage,
    EmbeddingVector,
    ProviderEmbeddingBatch,
)
from app.services.document_parsing.normalization import normalize_text, text_digest
from app.services.document_parsing.pipeline import parse_source
from app.services.retrieval.pipeline import prepare_document_index
from app.services.retrieval.tokenization import EmbeddingTokenCounter

SPEC = (
    Path(__file__).resolve().parents[1]
    / "tests"
    / "fixtures"
    / "retrieval"
    / "handoff.json"
)


class _FixtureProvider:
    def __init__(self, seed, counter):
        self.seed, self.counter = seed, counter

    async def embed(self, texts, profile, purpose, request_key):
        vector = tuple(self.seed) + (0.0,) * (profile.dimension - len(self.seed))
        return ProviderEmbeddingBatch(
            profile=profile,
            vectors=tuple(
                EmbeddingVector(key=text.key, embedding=vector) for text in texts
            ),
            usage=EmbeddingUsage(
                request_key=request_key,
                model=profile.model,
                input_tokens=sum(self.counter.count(text.text) for text in texts),
                output_tokens=0,
                latency_ms=0,
                failed=False,
                tokens_known=True,
            ),
        )


async def _prepared_fixture(document, chunking, profile, counter, seed, label):
    prepared = await prepare_document_index(
        document,
        chunking,
        profile,
        label,
        _FixtureProvider(seed, counter),
        counter,
    )
    # Actual dispatches get unique keys; synthetic exports use stable fixture
    # keys so that regenerated packets can be compared byte-for-byte.
    return prepared.model_copy(
        update={
            "usage": tuple(
                usage.model_copy(update={"request_key": f"{label}-batch-{i}"})
                for i, usage in enumerate(prepared.usage)
            )
        }
    )


async def build_packet():
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    counter = EmbeddingTokenCounter()
    profile = EmbeddingProfile(
        profile_id="fixture-small-1024-v1",
        provider="fixture",
        model="text-embedding-3-small",
        dimension=1024,
        tokenizer_id=counter.tokenizer_id,
        max_input_tokens=counter.max_input_tokens,
    )
    chunking = ChunkingProfile(tokenizer_id=counter.tokenizer_id)
    documents = []
    for definition in spec["documents"]:
        content = definition["content"]
        source = SourceInput(
            project_id=definition["project_id"],
            document_version_id=definition["id"],
            kind=definition["kind"],
            media_type="text/markdown",
            content=content,
            content_hash=text_digest(content),
            text_hash=text_digest(normalize_text(content)),
            normalization_version="utf8-lf-v1",
            parser_profile="markdown-v1",
        )
        document = parse_source(source)
        prepared = await _prepared_fixture(
            document,
            chunking,
            profile,
            counter,
            definition["vector_seed"],
            f"fixture-{definition['id']}",
        )
        previous = prepared
        if definition["id"] == spec["run"]["document_version_id"]:
            previous = await _prepared_fixture(
                document,
                ChunkingProfile(
                    profile_id="fixture-previous-v1",
                    tokenizer_id=counter.tokenizer_id,
                    max_tokens=300,
                    include_headings=True,
                ),
                profile,
                counter,
                [0.6, 0.8],
                f"fixture-previous-{definition['id']}",
            )
        batch = ChunkBatch(
            manifest=ChunkManifest(
                **prepared.manifest.model_dump(include=set(ChunkManifest.model_fields))
            ),
            chunks=prepared.chunks,
            excluded_ranges=prepared.excluded_ranges,
        )
        entry = {
            key: value
            for key, value in definition.items()
            if key not in {"content", "vector_seed"}
        }
        entry.update(
            {
                "source_document": document.model_dump(mode="json"),
                "chunk_request": {
                    **source.model_dump(mode="json"),
                    "request_key": f"fixture-{definition['id']}-chunks",
                    "chunking_profile_id": chunking.profile_id,
                },
                "chunk_response": {
                    "result": batch.model_dump(mode="json"),
                    "origin": "PARSER",
                    "usage": [],
                },
                "embedding_request": {
                    "project_id": source.project_id,
                    "request_key": f"fixture-{definition['id']}-embedding",
                    "texts": [
                        {"key": chunk.chunk_key, "text": chunk.embedding_text}
                        for chunk in batch.chunks
                    ],
                    "model": profile.model,
                    "dimension": profile.dimension,
                },
                "embedding_response": {
                    "result": {
                        "model": profile.model,
                        "dimension": profile.dimension,
                        "vectors": [
                            vector.model_dump(mode="json")
                            for vector in prepared.vectors
                        ],
                    },
                    "origin": "MODEL",
                    "model": profile.model,
                    "usage": [
                        usage.model_dump(mode="json") for usage in prepared.usage
                    ],
                },
                "prepared_index": prepared.model_dump(mode="json"),
                "published_seed": {
                    "state": "READY",
                    "index_key": previous.manifest.index_key,
                    "chunk_keys": list(previous.manifest.expected_chunk_keys),
                    "prepared_index": previous.model_dump(mode="json"),
                },
            }
        )
        documents.append(entry)
    run_source = next(
        item["source_document"]
        for item in documents
        if item["id"] == spec["run"]["document_version_id"]
    )
    target = next(
        item for item in documents if item["id"] == spec["run"]["document_version_id"]
    )
    for case in spec["scenarios"]:
        if case["kind"] == "publish":
            # Replay's mutation first completes publication, then repeats it.
            changed = case["id"] in {"publish-complete", "publish-replay"}
            case["expected"]["ready_index_key"] = (
                target["prepared_index"]["manifest"]["index_key"]
                if changed
                else target["published_seed"]["index_key"]
            )
    return {
        "metadata": {
            "fixture_version": spec["fixture_version"],
            "synthetic": True,
            "contract_status": "proposal",
            "verification_level": "ai-artifacts-only",
            "warning": "Synthetic vectors and usage: never publish to production or billing records.",
        },
        "embedding_profile": profile.model_dump(mode="json"),
        "query_vector": [1.0] + [0.0] * (profile.dimension - 1),
        "projects": spec["projects"],
        "memberships": spec["memberships"],
        "baselines": spec["baselines"],
        "current_baselines": {
            project["id"]: project["current_baseline_id"]
            for project in spec["projects"]
        },
        "run": {
            **spec["run"],
            "expected_source": {
                key: run_source[key]
                for key in (
                    "project_id",
                    "document_version_id",
                    "content_hash",
                    "text_hash",
                )
            },
            "expected_embedding_profile": profile.model_dump(mode="json"),
            "expected_chunking_profile": chunking.model_dump(mode="json"),
        },
        "documents": documents,
        "scenarios": spec["scenarios"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    packet = asyncio.run(build_packet())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(packet, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"Wrote {len(packet['documents'])} synthetic documents and {len(packet['scenarios'])} BE scenarios."
    )


if __name__ == "__main__":
    main()
