"""Controlled reference traces, explicitly not tests of BE transactions or SQL."""

import asyncio


def validate_trace(trace):
    if trace["outcome"] != "answered":
        return (
            ["discarded_answer_exposed"]
            if trace.get("answer_value") or trace.get("citation_versions")
            else []
        )
    errors = []
    allowed = set(trace["allowed_versions"])
    for key, error in (
        ("retrieved_versions", "retrieval_version_mismatch"),
        ("model_input_versions", "model_input_version_mismatch"),
        ("citation_versions", "citation_version_mismatch"),
    ):
        if not trace[key] or not set(trace[key]) <= allowed:
            errors.append(error)
    if trace["answer_value"] != trace["expected_value"]:
        errors.append("answer_content_mismatch")
    if not trace["validated_baseline"]:
        errors.append("missing_final_validation")
    return errors


async def _case(case_id, change_at=None, initial="b1", ready=True):
    reached, released = asyncio.Event(), asyncio.Event()
    current = initial
    events = []
    trace = {
        "id": case_id,
        "backend": "simulation",
        "outcome": "index_not_ready",
        "events": events,
    }

    async def checkpoint(name):
        events.append(name)
        if name == change_at:
            reached.set()
            await released.wait()

    async def approval():
        nonlocal current
        await reached.wait()
        current = "b2"
        events.append("approval_commit:b2")
        released.set()

    mutation = asyncio.create_task(approval()) if change_at else None
    try:
        if ready:
            searched = current
            version = "201" if searched == "b1" else "202"
            value = "10 MB" if searched == "b1" else "30 MB"
            trace.update(retrieved_versions=[version], model_input_versions=[version])
            await checkpoint("after_search")
            events.append("model_response")
            await checkpoint("before_final")
            trace["validated_baseline"] = current
            trace["outcome"] = "answered" if searched == current else "retry_required"
            if trace["outcome"] == "answered":
                trace.update(
                    allowed_versions=[version],
                    citation_versions=[version],
                    answer_value=value,
                    expected_value=value,
                )
            await checkpoint("after_final")
        trace["current_baseline_at_delivery"] = current
        trace["violations"] = validate_trace(trace)
        return trace
    finally:
        if mutation:
            if not mutation.done():
                mutation.cancel()
            await asyncio.gather(mutation, return_exceptions=True)


async def run_consistency():
    return [
        await _case("old-ready"),
        await _case("new-ready", initial="b2"),
        await _case("new-not-ready", initial="b2", ready=False),
        await _case("change-after-search", change_at="after_search"),
        await _case("change-before-final", change_at="before_final"),
        await _case("change-after-final", change_at="after_final"),
    ]
