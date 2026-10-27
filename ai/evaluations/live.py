"""Bounded, opt-in GMS experiment. No retries or production API registration."""

import json
import time
from datetime import UTC, datetime
from typing import Literal

import httpx
from pydantic import BaseModel, ConfigDict

from app.core.config import Settings
from app.schemas.retrieval import EmbeddingPolicy, EmbeddingText
from app.services.retrieval.embeddings import (
    EmbeddingError,
    EmbeddingProviderError,
    embed_texts,
)
from app.services.retrieval.gms import GmsEmbeddingProvider
from app.services.retrieval.tokenization import embedding_profile
from evaluations.document_rag import (
    ANSWER_SCHEMA,
    INSTRUCTIONS,
    CallBudget,
    answer_scores,
    cosine,
    retrieval_scores,
    select_context,
    write_report,
)

MODELS_URL = "https://gms.ssafy.io/api/v1/common-codes/models"
RESPONSES_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1/responses"
MODEL = "gpt-6-luna"


class Citation(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    evidence_id: str
    quote: str


class Answer(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    status: Literal["answered", "insufficient"]
    answer: str
    citations: list[Citation]


def parse_response(payload):
    if not isinstance(payload, dict):
        raise TypeError("malformed_response_envelope")
    if payload.get("status") != "completed":
        raise ValueError("incomplete_response")
    model = payload.get("model", "")
    if not isinstance(model, str) or (
        model != MODEL and not model.startswith(MODEL + "-")
    ):
        raise ValueError("response_model_mismatch")
    parts = []
    output = payload.get("output")
    if not isinstance(output, list):
        raise TypeError("malformed_response_output")
    for item in output:
        if not isinstance(item, dict):
            raise TypeError("malformed_response_item")
        if item.get("type") == "message" and item.get("role") == "assistant":
            contents = item.get("content")
            if not isinstance(contents, list):
                raise ValueError("malformed_response_content")
            for content in contents:
                if not isinstance(content, dict):
                    raise TypeError("malformed_response_content")
                if content.get("type") == "refusal":
                    raise ValueError("model_refusal")
                if content.get("type") == "output_text":
                    if not isinstance(content.get("text"), str):
                        raise ValueError("malformed_response_text")
                    parts.append(content["text"])
    return Answer.model_validate_json("".join(parts)).model_dump()


def response_usage(payload):
    if not isinstance(payload, dict):
        return None
    usage = payload.get("usage")
    if not isinstance(usage, dict):
        return None
    values = [usage.get(k) for k in ("input_tokens", "output_tokens", "total_tokens")]
    if (
        not all(type(v) is int and v >= 0 for v in values)
        or values[0] + values[1] != values[2]
    ):
        return None
    return {"input_tokens": values[0], "output_tokens": values[1]}


def cost(usage, price):
    return (
        (
            usage["input_tokens"] * price["inputPrice"]
            + usage["output_tokens"] * price["outputPrice"]
        )
        if usage
        else None
    )


class _MeteredEmbedding:
    def __init__(self, inner, budget, report, price, path, counter):
        self.inner, self.budget, self.report = inner, budget, report
        self.price, self.path, self.counter = price, path, counter

    async def embed(self, texts, profile, purpose, request_key):
        reserved = (
            sum(self.counter.count(t.text) for t in texts) * self.price["inputPrice"]
        )
        self.budget.reserve(reserved)
        started = time.perf_counter()
        usage = None
        call = {
            "stage": purpose,
            "request_key": request_key,
            "model": profile.model,
            "failed": True,
        }
        self.report["calls"].append(call)
        try:
            result = await self.inner.embed(texts, profile, purpose, request_key)
            usage = result.usage
            call["failed"] = False
            return result
        except EmbeddingProviderError as exc:
            usage = exc.usage
            call["error"] = exc.code
            raise
        finally:
            raw = (
                {
                    "input_tokens": usage.input_tokens,
                    "output_tokens": usage.output_tokens,
                }
                if usage and usage.tokens_known
                else None
            )
            actual = cost(raw, self.price)
            self.budget.settle(reserved, actual)
            call.update(
                usage=raw,
                estimated_credits=actual,
                reserved_credits_if_unknown=reserved if actual is None else 0,
                elapsed_ms=round((time.perf_counter() - started) * 1000, 3),
            )
            write_report(self.path, self.report)


async def _answer(client, key, prompt, report, path, budget, price, row):
    # UTF-8 byte count + framing allowance conservatively bounds input tokens.
    reserved = (len((INSTRUCTIONS + prompt).encode("utf-8")) + 1024) * price[
        "inputPrice"
    ] + 512 * price["outputPrice"]
    budget.reserve(reserved)
    call = {"stage": "generation", "model": MODEL, "row": row["id"], "failed": True}
    report["calls"].append(call)
    started, payload = time.perf_counter(), {}
    try:
        async with client.stream(
            "POST",
            RESPONSES_URL,
            headers={"Authorization": f"Bearer {key.get_secret_value()}"},
            json={
                "model": MODEL,
                "input": prompt,
                "instructions": INSTRUCTIONS,
                "max_output_tokens": 512,
                "store": False,
                "text": {
                    "format": {
                        "type": "json_schema",
                        "name": "rag_evaluation",
                        "strict": True,
                        "schema": ANSWER_SCHEMA,
                    }
                },
            },
            timeout=45,
        ) as response:
            chunks, size = [], 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > 1_048_576:
                    raise ValueError("response_size_limit")
                chunks.append(chunk)
            try:
                payload = json.loads(b"".join(chunks))
            except (ValueError, UnicodeError):
                payload = {}
            if response.status_code != 200:
                # Do not copy provider error bodies/headers into reports or logs.
                raise RuntimeError(f"GMS HTTP {response.status_code}")
        row["raw_response"] = payload
        answer = parse_response(payload)
        call["failed"] = False
        return answer
    finally:
        usage = response_usage(payload) if isinstance(payload, dict) else None
        actual = cost(usage, price)
        budget.settle(reserved, actual)
        call.update(
            usage=usage,
            estimated_credits=actual,
            reserved_credits_if_unknown=reserved if actual is None else 0,
            elapsed_ms=round((time.perf_counter() - started) * 1000, 3),
        )
        row["generation_ms"] = call["elapsed_ms"]
        write_report(path, report)


def recover_rows(report):
    questions = {q["id"]: q for q in report["questions"]}
    chunks = {c["id"]: c for items in report["indexes"].values() for c in items}
    for row in report["rows"]:
        if row["status"] != "ok" and "raw_response" in row:
            try:
                answer = parse_response(row["raw_response"])
                evidence = [chunks[key] for key in row["evidence_ids"]]
                row.update(
                    status="ok",
                    answer=answer,
                    scores=answer_scores(
                        questions[row["question_id"]], answer, evidence
                    ),
                    recovered_from_checkpoint=True,
                )
            except (ValueError, KeyError, TypeError):
                row["error"] = "saved_output_validation_failed"


async def run_live(report, counter, path):
    if report.get("status") == "completed":
        raise ValueError("Completed experiment must not be repeated")
    settings = Settings()
    if settings.gms_key is None or not settings.gms_key.get_secret_value().strip():
        raise ValueError("GMS_KEY missing; set ai/.env without printing it")
    if settings.bee_default_model != MODEL:
        raise ValueError("Configured answer model differs from evaluation protocol")
    recover_rows(report)
    completed = {row["id"] for row in report["rows"]}
    if len(completed) != len(report["rows"]):
        raise ValueError("Duplicate checkpoint rows")
    budget = CallBudget(
        calls=len(report["calls"]),
        estimated_credits=sum(
            (c.get("estimated_credits") or 0) + c.get("reserved_credits_if_unknown", 0)
            for c in report["calls"]
        ),
    )
    report["status"] = "running"
    report["metadata"].setdefault("started_utc", datetime.now(UTC).isoformat())
    report["metadata"].setdefault("sessions", []).append(
        {
            "started_utc": datetime.now(UTC).isoformat(),
            "prior_rows": len(report["rows"]),
            "prior_calls": len(report["calls"]),
        }
    )
    report["metadata"]["instructions"] = INSTRUCTIONS
    report["metadata"]["embedding_reuse"] = (
        "Identical texts deduplicated across modes and repetitions; physical shared calls and per-mode logical tokens reported separately. Query vectors cached for all runs."
    )
    report["metadata"]["sampling"] = (
        "Provider defaults; no unsupported temperature/seed override; 3 independent generation calls per setting/question."
    )
    try:
        async with httpx.AsyncClient(trust_env=False, follow_redirects=False) as client:
            response = await client.get(MODELS_URL, timeout=20)
            response.raise_for_status()
            catalog = response.json()
            prices = {}
            for model in (MODEL, settings.gms_embedding_model):
                item = next(
                    x
                    for x in catalog
                    if x.get("modelName") == model and x.get("isActive") is True
                )
                prices[model] = {k: item[k] for k in ("inputPrice", "outputPrice")}
                if not all(
                    type(v) in (int, float) and 0 <= v <= 1
                    for v in prices[model].values()
                ):
                    raise ValueError("Unrecognized public model pricing")
            if report["metadata"].get("public_prices", prices) != prices:
                raise ValueError("Public pricing changed; resume refused")
            report["metadata"].update(
                public_prices=prices,
                price_source=MODELS_URL,
                cost_basis="Public per-token estimate, no account balance reconciliation or cache discount claim",
            )
            profile = embedding_profile(settings)
            report["metadata"]["embedding_profile"] = profile.model_dump(mode="json")
            provider = _MeteredEmbedding(
                GmsEmbeddingProvider(client, settings.gms_key),
                budget,
                report,
                prices[profile.model],
                path,
                counter,
            )
            unique_texts = list(
                dict.fromkeys(
                    c["embedding_text"]
                    for chunks in report["indexes"].values()
                    for c in chunks
                )
            )
            policy = EmbeddingPolicy(max_attempts=1)
            result = await embed_texts(
                tuple(
                    EmbeddingText(key=f"d{i}", text=text)
                    for i, text in enumerate(unique_texts)
                ),
                profile,
                "eval-documents",
                provider,
                counter,
                policy=policy,
            )
            by_key = {v.key: v.embedding for v in result.vectors}
            vectors = {text: by_key[f"d{i}"] for i, text in enumerate(unique_texts)}
            queries = report["questions"]
            result = await embed_texts(
                tuple(EmbeddingText(key=q["id"], text=q["question"]) for q in queries),
                profile,
                "eval-queries",
                provider,
                counter,
                purpose="query",
                policy=policy,
            )
            query_vectors = {v.key: v.embedding for v in result.vectors}
            report["logical_index_tokens"] = {
                mode: sum(counter.count(c["embedding_text"]) for c in chunks)
                for mode, chunks in report["indexes"].items()
            }
            for repetition in range(3):
                for mode, chunks in report["indexes"].items():
                    for question in queries:
                        row_id = f"{repetition}:{mode}:{question['id']}"
                        if row_id in completed:
                            continue
                        row = {
                            "id": row_id,
                            "mode": mode,
                            "question_id": question["id"],
                            "repetition": repetition,
                            "status": "error",
                        }
                        report["rows"].append(row)
                        started = time.perf_counter()
                        ranked = sorted(
                            chunks,
                            key=lambda c: (
                                -cosine(
                                    query_vectors[question["id"]],
                                    vectors[c["embedding_text"]],
                                ),
                                c["id"],
                            ),
                        )
                        evidence = select_context(ranked, counter)
                        row.update(
                            search_ms=round((time.perf_counter() - started) * 1000, 3),
                            ranked_ids=[c["id"] for c in ranked[:12]],
                            evidence_ids=[c["id"] for c in evidence],
                            retrieval={
                                str(k): retrieval_scores(ranked, question["gold"], k)
                                for k in (1, 3, 6, 12)
                            },
                            context_recall=retrieval_scores(
                                evidence, question["gold"], 6
                            )["recall"],
                        )
                        prompt = json.dumps(
                            {
                                "question": question["question"],
                                "evidence": [
                                    {"evidence_id": c["id"], "text": c["text"]}
                                    for c in evidence
                                ],
                            },
                            ensure_ascii=False,
                        )
                        try:
                            answer = await _answer(
                                client,
                                settings.gms_key,
                                prompt,
                                report,
                                path,
                                budget,
                                prices[MODEL],
                                row,
                            )
                            row.update(
                                status="ok",
                                answer=answer,
                                scores=answer_scores(question, answer, evidence),
                            )
                        except (ValueError, KeyError, TypeError) as exc:
                            # Invalid output is an observed failure, never removed from totals.
                            row["error"] = type(exc).__name__
                            if "budget" in str(exc):
                                raise
                        finally:
                            write_report(path, report)
                    print(
                        f"Completed repetition {repetition + 1}, mode {mode}; rows={len(report['rows'])}, calls={budget.calls}",
                        flush=True,
                    )
            report["status"] = "completed"
    except (
        ValueError,
        KeyError,
        TypeError,
        RuntimeError,
        StopIteration,
        httpx.HTTPError,
        EmbeddingError,
        OSError,
    ) as exc:
        report["status"] = "stopped"
        report["error"] = {
            "type": type(exc).__name__,
            "message": str(exc)
            if isinstance(exc, (RuntimeError, ValueError))
            and "validation error" not in str(exc)
            else "Evaluation stopped; inspect recorded calls and failed rows.",
        }
    finally:
        report["budget"] = {
            "calls": budget.calls,
            "estimated_credits_including_unknown_reservations": budget.estimated_credits,
            "max_calls": budget.max_calls,
            "max_credits": budget.max_credits,
        }
        report["metadata"]["finished_utc"] = datetime.now(UTC).isoformat()
        write_report(path, report)
