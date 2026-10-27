"""Catch misleading scores and evaluation-only policy/budget regressions."""

import asyncio
import importlib

import pytest


def subject():
    return importlib.import_module("evaluations.document_rag")


def test_recall_unions_split_ranges_without_counting_duplicates_or_wrong_versions():
    m = subject()
    gold = [{"document_version_id": "2", "start": 10, "end": 20}]
    first = {"document_version_id": "2", "spans": [[10, 15]]}
    second = {"document_version_id": "2", "spans": [[15, 20]]}
    wrong = {"document_version_id": "1", "spans": [[10, 20]]}
    assert m.retrieval_scores([wrong, first, first], gold, 3)["recall"] == 0
    assert m.retrieval_scores([first, second], gold, 2)["recall"] == 1
    assert m.retrieval_scores([wrong, first, second], gold, 3)["mrr"] == 0.5
    assert m.retrieval_scores([first], [], 1) == {"recall": None, "mrr": None}


def test_fixed_and_structure_chunks_preserve_source_and_count_context_in_budget():
    m = subject()
    counter, docs, _ = m.load_dataset()
    for mode in m.MODES:
        chunks = m.make_chunks(docs, mode, counter, max_tokens=120)
        assert chunks
        assert all(counter.count(c["embedding_text"]) <= 120 for c in chunks)
        for chunk in chunks:
            original = docs[chunk["document_version_id"]].normalized_text
            for start, end in chunk["spans"]:
                assert original[start:end].strip() in chunk["text"]


def test_answer_scores_do_not_confuse_allowed_citation_with_correct_claim():
    m = subject()
    evidence = [
        {"id": "e1", "text": "Limit is 30 MB.", "source_parts": ["Limit is 30 MB."]}
    ]
    q = {"gold": [1], "required": [["30"]]}
    wrong = {
        "status": "answered",
        "answer": "10 MB",
        "citations": [{"evidence_id": "e1", "quote": "30 MB"}],
    }
    scores = m.answer_scores(q, wrong, evidence)
    assert scores["citation_valid"] is True
    assert scores["required_fact_match"] is False
    wrong["citations"][0]["evidence_id"] = "not-supplied"
    assert m.answer_scores(q, wrong, evidence)["citation_valid"] is False
    wrong["status"] = "insufficient"
    assert m.answer_scores(q, wrong, evidence)["abstention_correct"] is False
    assert (
        m.answer_scores({"gold": [], "required": []}, wrong, evidence)[
            "abstention_correct"
        ]
        is False
    )


def test_budget_reserves_output_and_does_not_dispatch_when_exhausted():
    m = subject()
    budget = m.CallBudget(max_calls=1, max_credits=1.0)
    budget.reserve(0.5)
    with pytest.raises(ValueError, match="budget"):
        budget.reserve(0.1)
    with pytest.raises(ValueError, match="budget"):
        m.CallBudget(max_calls=5, max_credits=1.0).reserve(1.1)


def test_consistency_validator_catches_stale_content_under_new_citation():
    m = importlib.import_module("evaluations.consistency")
    trace = {
        "outcome": "answered",
        "validated_baseline": "b2",
        "allowed_versions": ["202"],
        "retrieved_versions": ["202"],
        "model_input_versions": ["202"],
        "citation_versions": ["202"],
        "answer_value": "10 MB",
        "expected_value": "30 MB",
    }
    assert "answer_content_mismatch" in m.validate_trace(trace)
    trace["answer_value"] = "30 MB"
    trace["citation_versions"] = ["201"]
    assert "citation_version_mismatch" in m.validate_trace(trace)


def test_retry_trace_must_not_expose_discarded_answer():
    m = importlib.import_module("evaluations.consistency")
    assert m.validate_trace({"outcome": "retry_required", "answer_value": "10 MB"}) == [
        "discarded_answer_exposed"
    ]


@pytest.mark.asyncio
async def test_barriers_distinguish_change_before_and_after_final_validation():
    m = subject()
    cases = await asyncio.wait_for(m.run_consistency(), timeout=3)
    by_id = {case["id"]: case for case in cases}
    assert by_id["change-after-search"]["outcome"] == "retry_required"
    assert by_id["change-before-final"]["outcome"] == "retry_required"
    assert by_id["change-after-final"]["outcome"] == "answered"
    assert by_id["change-after-final"]["validated_baseline"] == "b1"
    assert by_id["change-after-final"]["current_baseline_at_delivery"] == "b2"
    assert by_id["new-ready"]["answer_value"] == "30 MB"
    assert by_id["new-not-ready"]["outcome"] == "index_not_ready"
    assert all(not case["violations"] for case in cases)


def test_responses_parser_skips_reasoning_and_rejects_truncated_or_wrong_model():
    live = importlib.import_module("evaluations.live")
    payload = {
        "model": "gpt-6-luna",
        "status": "completed",
        "output": [
            {"type": "reasoning", "summary": []},
            {
                "type": "message",
                "role": "assistant",
                "content": [
                    {
                        "type": "output_text",
                        "text": '{"status":"insufficient","answer":"근거 없음","citations":[]}',
                    }
                ],
            },
        ],
    }
    assert live.parse_response(payload)["status"] == "insufficient"
    payload["status"] = "incomplete"
    with pytest.raises(ValueError, match="incomplete"):
        live.parse_response(payload)
    payload["status"], payload["model"] = "completed", "other-model"
    with pytest.raises(ValueError, match="model"):
        live.parse_response(payload)


def test_unknown_usage_retains_budget_reservation_and_malformed_usage_is_unknown():
    m = subject()
    live = importlib.import_module("evaluations.live")
    budget = m.CallBudget()
    budget.reserve(3.0)
    budget.settle(3.0, None)
    assert budget.estimated_credits == 3.0
    assert (
        live.response_usage(
            {"usage": {"input_tokens": True, "output_tokens": 1, "total_tokens": 2}}
        )
        is None
    )
    assert live.response_usage(
        {"usage": {"input_tokens": 4, "output_tokens": 2, "total_tokens": 6}}
    ) == {"input_tokens": 4, "output_tokens": 2}


@pytest.mark.parametrize(
    "payload",
    [
        [],
        {"status": "completed", "model": None},
        {"status": "completed", "model": "gpt-6-luna", "output": [None]},
    ],
)
def test_malformed_response_envelopes_are_recordable_failures(payload):
    live = importlib.import_module("evaluations.live")
    with pytest.raises((ValueError, TypeError)):
        live.parse_response(payload)


def test_consistency_cannot_relabel_old_policy_as_new_validated_baseline():
    c = importlib.import_module("evaluations.consistency")
    trace = {
        "outcome": "answered",
        "validated_baseline": "b2",
        "allowed_versions": ["201"],
        "retrieved_versions": ["201"],
        "model_input_versions": ["201"],
        "citation_versions": ["201"],
        "answer_value": "10 MB",
        "expected_value": "10 MB",
    }
    violations = c.validate_trace(trace)
    assert "answer_content_mismatch" in violations
    assert "retrieval_version_mismatch" in violations


def test_checkpoint_retries_transient_windows_replace_denial(tmp_path, monkeypatch):
    import json
    from pathlib import Path

    m = subject()
    original = Path.replace
    attempts = []

    def transient(path, target):
        attempts.append(1)
        if len(attempts) == 1:
            raise PermissionError("transient sharing violation")
        return original(path, target)

    monkeypatch.setattr(Path, "replace", transient)
    destination = tmp_path / "checkpoint.json"
    m.write_report(destination, {"status": "completed"})
    assert json.loads(destination.read_text(encoding="utf-8"))["status"] == "completed"


def test_resume_recovers_saved_paid_output_without_changing_question_or_evidence():
    live = importlib.import_module("evaluations.live")
    report = {
        "questions": [{"id": "q", "gold": [], "required": []}],
        "indexes": {"fixed": []},
        "rows": [
            {
                "id": "0:fixed:q",
                "mode": "fixed",
                "question_id": "q",
                "status": "error",
                "evidence_ids": [],
                "raw_response": {
                    "model": "gpt-6-luna",
                    "status": "completed",
                    "output": [
                        {
                            "type": "message",
                            "role": "assistant",
                            "content": [
                                {
                                    "type": "output_text",
                                    "text": '{"status":"insufficient","answer":"없음","citations":[]}',
                                }
                            ],
                        }
                    ],
                },
            }
        ],
    }
    live.recover_rows(report)
    assert report["rows"][0]["status"] == "ok"
    assert report["rows"][0]["scores"]["abstention_correct"] is True


def test_summary_keeps_planned_denominator_and_marks_zero_denominator_na():
    summary = importlib.import_module("evaluations.summarize")
    report = {
        "status": "stopped",
        "metadata": {"repetitions": 3, "dataset_id": "test"},
        "questions": [{"id": "q", "gold": [1]}],
        "indexes": {"fixed": []},
        "rows": [
            {"mode": "fixed", "question_id": "q", "id": "0:fixed:q", "status": "error"}
        ],
        "calls": [],
        "consistency": [],
    }
    rendered = summary.summarize(report)
    assert "0/3" in rendered
    assert "0/0" not in rendered


@pytest.mark.asyncio
async def test_http_failure_preserves_valid_usage_without_provider_body(tmp_path):
    import httpx
    from pydantic import SecretStr

    live = importlib.import_module("evaluations.live")
    m = subject()
    report, row = {"calls": []}, {"id": "q"}
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                500,
                json={
                    "error": "DO-NOT-STORE",
                    "usage": {"input_tokens": 7, "output_tokens": 2, "total_tokens": 9},
                },
            )
        )
    ) as client:
        with pytest.raises(RuntimeError, match="HTTP 500"):
            await live._answer(
                client,
                SecretStr("test-only"),
                "question",
                report,
                tmp_path / "result.json",
                m.CallBudget(),
                {"inputPrice": 0.001, "outputPrice": 0.005},
                row,
            )
    assert report["calls"][0]["usage"] == {"input_tokens": 7, "output_tokens": 2}
    assert "DO-NOT-STORE" not in (tmp_path / "result.json").read_text(encoding="utf-8")


@pytest.mark.asyncio
async def test_live_runner_records_every_row_and_actual_usage_without_network(
    tmp_path, monkeypatch
):
    import httpx

    from app.core.config import Settings

    m = subject()
    live = importlib.import_module("evaluations.live")
    counter, docs, spec = m.load_dataset()
    chunks = m.make_chunks(docs, "fixed", counter)[:1]
    report = {
        "metadata": {},
        "indexes": {"fixed": chunks},
        "questions": spec["questions"][:1],
        "rows": [],
        "calls": [],
    }

    def respond(request):
        if request.method == "GET":
            return httpx.Response(
                200,
                json=[
                    {
                        "modelName": model,
                        "isActive": True,
                        "inputPrice": 0.001,
                        "outputPrice": 0.005,
                    }
                    for model in ("gpt-6-luna", "text-embedding-3-small")
                ],
            )
        import json

        data = json.loads(request.content)
        if request.url.path.endswith("/embeddings"):
            return httpx.Response(
                200,
                json={
                    "object": "list",
                    "model": "text-embedding-3-small",
                    "data": [
                        {
                            "object": "embedding",
                            "index": i,
                            "embedding": [1.0] + [0.0] * 1023,
                        }
                        for i in range(len(data["input"]))
                    ],
                    "usage": {"prompt_tokens": 10, "total_tokens": 10},
                },
            )
        return httpx.Response(
            200,
            json={
                "model": "gpt-6-luna",
                "status": "completed",
                "output": [
                    {
                        "type": "message",
                        "role": "assistant",
                        "content": [
                            {
                                "type": "output_text",
                                "text": '{"status":"insufficient","answer":"근거 없음","citations":[]}',
                            }
                        ],
                    }
                ],
                "usage": {"input_tokens": 20, "output_tokens": 5, "total_tokens": 25},
            },
        )

    original = httpx.AsyncClient
    monkeypatch.setattr(
        live.httpx,
        "AsyncClient",
        lambda **kw: original(transport=httpx.MockTransport(respond), **kw),
    )
    monkeypatch.setattr(
        live, "Settings", lambda: Settings(_env_file=None, gms_key="test-only")
    )
    await live.run_live(report, counter, tmp_path / "result.json")
    assert report["status"] == "completed"
    assert len(report["rows"]) == 3
    assert all(row["scores"]["required_fact_match"] is False for row in report["rows"])
    assert report["budget"]["calls"] == 5
    assert report["budget"][
        "estimated_credits_including_unknown_reservations"
    ] == pytest.approx(0.155)


@pytest.mark.asyncio
async def test_resume_profile_change_is_rejected_before_paid_dispatch(
    tmp_path, monkeypatch
):
    import httpx

    from app.core.config import Settings

    m = subject()
    live = importlib.import_module("evaluations.live")
    counter, docs, spec = m.load_dataset()
    report = {
        "metadata": {"embedding_profile": {"dimension": 1024}},
        "indexes": {"fixed": m.make_chunks(docs, "fixed", counter)[:1]},
        "questions": spec["questions"][:1],
        "rows": [],
        "calls": [],
    }

    def response(request):
        if request.method == "GET":
            return httpx.Response(
                200,
                json=[
                    {
                        "modelName": model,
                        "isActive": True,
                        "inputPrice": 0.001,
                        "outputPrice": 0.005,
                    }
                    for model in ("gpt-6-luna", "text-embedding-3-small")
                ],
            )
        return httpx.Response(500, json={"error": "must-not-dispatch"})

    original = httpx.AsyncClient
    monkeypatch.setattr(
        live.httpx,
        "AsyncClient",
        lambda **kw: original(transport=httpx.MockTransport(response), **kw),
    )
    monkeypatch.setattr(
        live,
        "Settings",
        lambda: Settings(
            _env_file=None, gms_key="test-only", gms_embedding_dimension=3
        ),
    )
    await live.run_live(report, counter, tmp_path / "result.json")
    assert report["status"] == "stopped"
    assert report["calls"] == []
