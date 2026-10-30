"""Bounded development evaluation of the #131 selector using synthetic data.

The fixture labels are agent-authored and need human review. This measures the
selector over a supplied snapshot, not the BE index, MCP, or production quality.
"""

import argparse
import asyncio
import hashlib
import json
import os
import tempfile
from pathlib import Path

import httpx

from app.core.config import Settings
from app.schemas.retrieval import EmbeddingPolicy
from app.services.retrieval.context_selection import (
    ContextSelectionError,
    SelectionInput,
    select_context,
)
from app.services.retrieval.context_semantic_selector import EmbeddingContextSelector
from app.services.retrieval.gms import GmsEmbeddingProvider
from app.services.retrieval.tokenization import EmbeddingTokenCounter, embedding_profile

FIXTURE = (
    Path(__file__).resolve().parents[1]
    / "tests/fixtures/retrieval/context-selection-131.json"
)
SCENARIOS = (
    "upload-create-one-hop",
    "task-plan-adds-current-reference",
    "owner-reference",
    "source-only-index-retry",
    "unknown-retention",
)


def prepare_cases(data):
    """Use independent expected IDs, never the fixture's fake proposal."""
    by_id = {case["id"]: case for case in data["scenarios"]}
    for case_id in SCENARIOS:
        case = by_id[case_id]
        values = dict(data["snapshot"])
        values["request"] = case["request"]
        for key in ("task_id", "task_design_refs", "search_status"):
            if key in case:
                values[key] = case[key]
        if "evidence_override" in case:
            values["evidence"] = case["evidence_override"]
        yield (
            case,
            SelectionInput.model_validate_json(json.dumps(values, ensure_ascii=False)),
        )


def assess(case, result):
    selected_items = {item.design_item_version_id for item in result.design_items}
    expected_items = {
        item["design_item_version_id"] for item in case["expected_design_items"]
    }
    selected_evidence = set(result.evidence_ids)
    expected_evidence = set(case["expected_evidence_ids"])
    return {
        "id": case["id"],
        "items": sorted(selected_items),
        "evidence": sorted(selected_evidence),
        "missing_items": sorted(expected_items - selected_items),
        "extra_items": sorted(selected_items - expected_items),
        "missing_evidence": sorted(expected_evidence - selected_evidence),
        "extra_evidence": sorted(selected_evidence - expected_evidence),
        "unresolved": list(result.unresolved),
        "usage": [
            usage.model_dump(mode="json", exclude_none=True) for usage in result.usage
        ],
    }


def checkpoint(path, output):
    """Keep the previous checkpoint if writing the next one fails."""
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, delete=False
        ) as stream:
            temporary = Path(stream.name)
            json.dump(output, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


async def run(args):
    raw = FIXTURE.read_bytes()
    data = json.loads(raw)
    settings = Settings(_env_file=args.env_file)
    if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
        raise ValueError("GMS_KEY is not configured")
    profile = embedding_profile(settings)
    policy = EmbeddingPolicy(max_attempts=1, batch_size=64)
    counter = EmbeddingTokenCounter()
    cases = list(prepare_cases(data))
    for _, snapshot in cases:
        texts = [item.text for item in (*snapshot.items, *snapshot.evidence)]
        counts = [counter.count(text) for text in texts]
        if (
            not texts
            or len(texts) > min(policy.batch_size, policy.max_texts)
            or any(count <= 0 or count > profile.max_input_tokens for count in counts)
            or sum(counts) > policy.max_batch_tokens
        ):
            raise ValueError("Fixture exceeds the one-document-batch call budget")
    output = {
        "fixture_sha256": hashlib.sha256(raw).hexdigest(),
        "label_author": data["label_author"],
        "model": profile.model,
        "dimension": profile.dimension,
        "threshold": args.threshold,
        "max_items": args.max_items,
        "max_evidence": args.max_evidence,
        "max_provider_calls": len(SCENARIOS) * 2,
        "notes": "Synthetic development labels; BE, MCP and production data are not evaluated.",
        "cases": [],
        "in_flight": None,
    }
    # Claim the path before any paid call. The file cannot be resumed or
    # overwritten implicitly; an interrupted call remains visible as unknown.
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(output, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    # The fixture has at most ten candidate texts per case: one query and one
    # document batch per case. Disable retries and stop immediately on failure.
    async with httpx.AsyncClient(trust_env=False, follow_redirects=False) as http:
        selector = EmbeddingContextSelector(
            GmsEmbeddingProvider(http, settings.gms_key),
            profile,
            counter,
            min_similarity=args.threshold,
            max_items=args.max_items,
            max_evidence=args.max_evidence,
            policy=policy,
        )
        for case, snapshot in cases:
            output["in_flight"] = case["id"]
            checkpoint(args.output, output)
            try:
                result = await select_context(snapshot, selector)
                output["cases"].append(assess(case, result))
            except ContextSelectionError as exc:
                output["error"] = {
                    "case": case["id"],
                    "code": exc.code,
                    "usage": [
                        u.model_dump(mode="json", exclude_none=True) for u in exc.usage
                    ],
                }
                output["in_flight"] = None
                checkpoint(args.output, output)
                break
            output["in_flight"] = None
            checkpoint(args.output, output)
    print(
        f"Recorded {len(output['cases'])}/{len(SCENARIOS)} cases; error={output.get('error', {}).get('code', 'none')}"
    )
    return 1 if "error" in output else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--live",
        action="store_true",
        required=True,
        help="Explicitly enable paid GMS calls",
    )
    parser.add_argument(
        "--threshold", required=True, type=float, help="Experimental cosine threshold"
    )
    parser.add_argument("--max-items", type=int, default=2)
    parser.add_argument("--max-evidence", type=int, default=2)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists")
    if not args.output.parent.is_dir():
        parser.error("output parent does not exist")
    return asyncio.run(run(args))


if __name__ == "__main__":
    raise SystemExit(main())
