import json
import math
import subprocess
import sys
from pathlib import Path

from app.schemas.documents import SourceDocument
from app.schemas.embedding_api import EmbeddingRequest, EmbeddingResponse
from app.schemas.retrieval import ChunkBatch, PreparedIndex
from app.services.retrieval.chunking import resolve_span

AI_ROOT = Path(__file__).resolve().parents[1]


def generate_packet(path):
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "scripts.generate_retrieval_handoff",
            "--output",
            str(path),
        ],
        cwd=AI_ROOT,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(path.read_text(encoding="utf-8"))


def test_generator_emits_a_reproducible_synthetic_packet(tmp_path):
    first = generate_packet(tmp_path / "first.json")
    second = generate_packet(tmp_path / "second.json")
    assert first == second
    assert first["metadata"]["synthetic"] is True
    assert first["embedding_profile"]["provider"] == "fixture"
    assert first["embedding_profile"]["dimension"] == 1024
    assert {item["id"] for item in first["documents"]} == {"201", "202", "203", "401"}


def test_packet_artifacts_preserve_source_key_dimension_and_wire_contracts(tmp_path):
    packet = generate_packet(tmp_path / "packet.json")
    for item in packet["documents"]:
        source = SourceDocument.model_validate_json(json.dumps(item["source_document"]))
        prepared = PreparedIndex.model_validate_json(json.dumps(item["prepared_index"]))
        batch = ChunkBatch.model_validate_json(
            json.dumps(item["chunk_response"]["result"])
        )
        EmbeddingRequest.model_validate_json(json.dumps(item["embedding_request"]))
        EmbeddingResponse.model_validate_json(json.dumps(item["embedding_response"]))
        assert prepared.manifest.project_id == item["project_id"] == source.project_id
        assert (
            prepared.manifest.document_version_id
            == item["id"]
            == source.document_version_id
        )
        assert prepared.manifest.expected_chunk_keys == tuple(
            c.chunk_key for c in batch.chunks
        )
        assert prepared.manifest.expected_chunk_keys == tuple(
            v.key for v in prepared.vectors
        )
        sent = {t["key"]: t["text"] for t in item["embedding_request"]["texts"]}
        assert sent == {
            chunk.chunk_key: chunk.embedding_text for chunk in prepared.chunks
        }
        assert item["chunk_response"]["origin"] == "PARSER"
        assert item["chunk_response"]["usage"] == []
        for chunk in prepared.chunks:
            assert chunk.text == "".join(
                resolve_span(source, span) for span in chunk.source_spans
            )
        for vector in prepared.vectors:
            assert len(vector.embedding) == 1024
            assert all(math.isfinite(value) for value in vector.embedding)
            assert math.hypot(*vector.embedding) > 0


def test_fixture_separates_current_baseline_review_scope_and_membership(tmp_path):
    packet = generate_packet(tmp_path / "packet.json")
    assert packet["current_baselines"] == {"101": "10002", "102": "20001"}
    versions = {item["id"]: item for item in packet["documents"]}
    assert versions["201"]["status"] == "SUPERSEDED"
    assert (
        versions["201"]["source_document"]["content_hash"]
        != versions["201"]["source_document"]["text_hash"]
    )
    assert versions["202"]["status"] == "APPROVED"
    assert versions["203"]["status"] == "DRAFT"
    assert versions["401"]["project_id"] == "102"
    assert any(
        member["project_id"] == "101" and not member["active"]
        for member in packet["memberships"]
    )
    cases = {case["id"]: case for case in packet["scenarios"]}
    assert cases["search-current"]["expected"]["document_version_ids"] == ["202"]
    assert cases["search-review-draft"]["expected"]["document_version_ids"] == ["203"]
    assert cases["search-not-ready"]["expected"]["outcome"] == "index_not_ready"


def test_forbidden_candidates_outscore_the_allowed_current_version(tmp_path):
    packet = generate_packet(tmp_path / "packet.json")
    # Makes a global top-k followed by filtering fail to return the allowed item.
    query = packet["query_vector"]
    scores = {}
    for item in packet["documents"]:
        vector = item["published_seed"]["prepared_index"]["vectors"][0]["embedding"]
        scores[item["id"]] = sum(a * b for a, b in zip(query, vector, strict=True))
    assert scores["201"] > scores["202"]
    assert scores["203"] > scores["202"]
    assert scores["401"] > scores["202"]


def test_publication_scenarios_preserve_ready_on_failure_and_stale_results(tmp_path):
    packet = generate_packet(tmp_path / "packet.json")
    cases = {case["id"]: case for case in packet["scenarios"]}
    assert len(cases) == len(packet["scenarios"])
    for case_id in (
        "publish-old-attempt",
        "publish-old-lease",
        "publish-missing-vector",
        "publish-wrong-dimension",
        "publish-different-model",
        "publish-storage-rollback",
        "publish-source-hash",
        "publish-partial-ai-failure",
        "publish-nonfinite-vector",
    ):
        assert cases[case_id]["expected"]["ready_unchanged"] is True
    current = next(item for item in packet["documents"] if item["id"] == "202")
    assert len(current["prepared_index"]["vectors"]) > 1


def test_reindex_fixture_distinguishes_previous_ready_from_candidate(tmp_path):
    packet = generate_packet(tmp_path / "packet.json")
    current = next(item for item in packet["documents"] if item["id"] == "202")
    previous = current["published_seed"]
    candidate = current["prepared_index"]
    assert previous["index_key"] != candidate["manifest"]["index_key"]
    previous_artifact = PreparedIndex.model_validate_json(
        json.dumps(previous["prepared_index"])
    )
    assert previous_artifact.vectors[0].embedding != tuple(
        candidate["vectors"][0]["embedding"]
    )
    assert (
        previous_artifact.manifest.embedding_profile.model_dump(mode="json")
        == packet["embedding_profile"]
    )
    cases = {case["id"]: case for case in packet["scenarios"]}
    for case_id in ("publish-complete", "publish-replay"):
        assert (
            cases[case_id]["expected"]["ready_index_key"]
            == candidate["manifest"]["index_key"]
        )
    for case_id in (
        "publish-old-attempt",
        "publish-old-lease",
        "publish-storage-rollback",
    ):
        assert cases[case_id]["expected"]["ready_index_key"] == previous["index_key"]
