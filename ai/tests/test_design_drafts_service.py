"""Draft composition verifies model output against the inputs; fakes only."""

import json
from hashlib import sha256

import pytest

from app.schemas.design_drafts import MAX_ITEMS, DesignDraftRequest
from app.services.design_drafts import (
    DraftProposal,
    LLMDraftProposer,
    ModelItem,
    apply_patch,
    draft_key,
    generate_design_drafts,
)
from app.services.design_drafts import proposer as proposer_module
from app.services.document_parsing.pipeline import parse_source
from app.services.llm_client import (
    LLMClient,
    LLMContext,
    LLMError,
    LLMUsage,
    ProviderError,
    ProviderResponse,
)

DOC = "# 업로드\n\n단일 파일 한도는 30 MB이다.\n\n## 권한\n\n발행은 리더만 수행한다.\n"
DOCUMENT = {"document_id": "301", "document_version_id": "311", "version_no": 1}
APPROVED = {
    "design_item_version_id": "1101",
    "design_item_id": "1001",
    "key": "PLN-001",
    "kind": "CONSTRAINT",
    "version_no": 1,
    "status": "APPROVED",
    "content": {"description": "단일 파일 한도는 30 MB이다."},
}
DRAFT_ITEM = {
    "design_item_version_id": "1102",
    "design_item_id": "1002",
    "key": "PLN-002",
    "kind": "POLICY",
    "version_no": 1,
    "status": "DRAFT",
    "content": {"description": "발행은 리더만 수행한다."},
}


def evidence(line=3, quote="단일 파일 한도는 30 MB이다."):
    return {
        **DOCUMENT,
        "block_id": "blk_unused",
        "locator": {"type": "text_lines", "start_line": line, "end_line": line},
        "location": f"L{line}-L{line}",
        "quote": quote,
    }


def stored_question(key, text, status="ANSWERED", answer_text="MiB"):
    entry = {
        "question": {
            "question_key": key,
            "candidate_key": key.removeprefix("q-"),
            "rank": 1,
            "question_type": "SHORT_ANSWER",
            "text": text,
            "answer_format": {"type": "SHORT_ANSWER", "max_length": 200},
            "origin": "MODEL",
            "basis": "REVIEW_CANDIDATE",
        },
        "evidence": [evidence()],
        "status": status,
        "answer": None,
    }
    if status == "ANSWERED":
        entry["answer"] = {
            "question_key": key,
            "answer": {"type": "SHORT_ANSWER", "text": answer_text},
            "answered_by": "11",
            "answered_at": "2026-11-03T03:00:00Z",
        }
    return entry


def request(content=DOC, **overrides):
    digest = sha256(content.encode("utf-8")).hexdigest()
    body = {
        "project_id": "101",
        "request_key": "draft-1",
        "document": DOCUMENT,
        "kind": "PLAN",
        "media_type": "text/markdown",
        "content": content,
        "content_hash": digest,
        "text_hash": digest,
        "normalization_version": "utf8-lf-v1",
        "parser_profile": "markdown-v1",
        "existing_items": [APPROVED, DRAFT_ITEM],
        "questions": [
            stored_question("q-1", "한도 단위는 무엇인가요?"),
            stored_question("q-2", "보관 기간은?", status="OPEN"),
        ],
    }
    body.update(overrides)
    return DesignDraftRequest.model_validate_json(json.dumps(body, ensure_ascii=False))


def usage():
    return LLMUsage(
        request_key="call-1",
        feature="BEE_DRAFT",
        project_id="101",
        model="fake",
        latency_ms=1,
        failed=False,
        tokens_known=True,
        input_tokens=10,
        output_tokens=5,
    )


def item(kind, description, *, base=None, sources=(), refs=(), rationale="근거"):
    return {
        "kind": kind,
        "content": {"description": description},
        "base_design_item_version_id": base,
        "sources": list(sources),
        "answer_refs": list(refs),
        "rationale": rationale,
    }


class FakeProposer:
    """Line anchors become real block ids; literal block ids are used as is."""

    def __init__(self, *items, unresolved=()):
        self.items = items
        self.unresolved = unresolved
        self.calls = []

    async def propose(self, request, document):
        self.calls.append((request, document))

        def block_for(source):
            if "block_id" in source:
                return source["block_id"]
            for block in document.blocks:
                if block.locator.start_line <= source["line"] <= block.locator.end_line:
                    return block.block_id
            raise KeyError(source)

        proposed = tuple(
            ModelItem.model_validate(
                {
                    **entry,
                    "sources": [
                        {"block_id": block_for(s), "quote": s["quote"]}
                        for s in entry["sources"]
                    ],
                }
            )
            for entry in self.items
        )
        return DraftProposal(proposed, tuple(self.unresolved), "fake", (usage(),))


def by_description(result):
    return {entry.content.description: entry for entry in result.items}


@pytest.mark.asyncio
async def test_update_of_an_approved_item_keeps_the_approved_content_as_before():
    proposer = FakeProposer(
        item(
            "CONSTRAINT",
            "단일 파일 한도는 30 MiB이다.",
            base="1101",
            sources=[{"line": 3, "quote": "단일 파일 한도는 30 MB이다."}],
            refs=["q-1"],
        )
    )
    body = request()
    outcome = await generate_design_drafts(body, proposer)
    result = outcome.result
    assert len(result.items) == 1 and len(result.changes) == 1
    draft = result.items[0]
    assert draft.status == "DRAFT" and draft.base_status == "APPROVED"
    assert draft.support == "DOCUMENT_AND_ANSWER"
    assert draft.sources[0].location == "업로드 / L3-L3"
    change = result.changes[0]
    assert change.change == "UPDATE" and change.base_design_item_version_id == "1101"
    assert change.before.description == "단일 파일 한도는 30 MB이다."
    assert [f.field for f in change.changed_fields] == ["description"]
    assert change.answer_refs == ("q-1",)
    # The supplied approved item is untouched; only the draft differs from it.
    assert body.existing_items[0].content.description == "단일 파일 한도는 30 MB이다."
    assert result.untouched_existing_ids == ("1102",)
    assert result.unanswered_question_keys == ("q-2",)
    assert outcome.origin == "MODEL" and outcome.model == "fake"
    assert outcome.usage[0].request_key == "call-1"
    api = outcome.to_api_dict()
    assert set(api) == {"result", "origin", "model", "usage"}
    assert api["usage"][0]["feature"] == "BEE_DRAFT"


@pytest.mark.asyncio
async def test_fabricated_quote_is_dropped_and_unsupported_items_rank_last():
    proposer = FakeProposer(
        item("CONSTRAINT", "응답 시간은 1초 이내다."),
        item(
            "REQUIREMENT",
            "한도 단위는 MiB다.",
            sources=[{"line": 3, "quote": "한도는 50 MB이다."}],
            refs=["q-1"],
        ),
        item(
            "POLICY",
            "발행 전 검토를 거친다.",
            sources=[{"line": 7, "quote": "발행은 리더만 수행한다."}],
        ),
    )
    outcome = await generate_design_drafts(request(), proposer)
    drafts = by_description(outcome.result)
    assert drafts["한도 단위는 MiB다."].support == "ANSWER"
    assert drafts["한도 단위는 MiB다."].sources == ()
    assert "50 MB" not in json.dumps(outcome.to_api_dict(), ensure_ascii=False)
    assert drafts["발행 전 검토를 거친다."].support == "DOCUMENT"
    assert [entry.support for entry in outcome.result.items] == [
        "ANSWER",
        "DOCUMENT",
        "NONE",
    ]
    assert all(change.change == "CREATE" for change in outcome.result.changes)


@pytest.mark.asyncio
async def test_unchanged_restatement_is_dropped_and_split_updates_are_merged():
    quote = "단일 파일 한도는 30 MB이다."
    proposer = FakeProposer(
        item("POLICY", "발행은 리더만 수행한다.", base="1102"),
        item(
            "CONSTRAINT",
            "단일 파일 한도는 30 MiB이다.",
            base="1101",
            sources=[{"line": 3, "quote": quote}],
            refs=["q-1"],
            rationale="답변이 단위를 확정했습니다.",
        ),
        # The model split a second change to the same item into its own
        # proposal: it is merged, not silently dropped.
        with_content(
            item(
                "CONSTRAINT",
                "x",
                base="1101",
                sources=[
                    {"line": 3, "quote": quote},
                    {"line": 7, "quote": "발행은 리더만 수행한다."},
                ],
                refs=["q-1"],
                rationale="단위를 별도 필드로 둡니다.",
            ),
            {"unit": "MiB"},
        ),
    )
    outcome = await generate_design_drafts(request(), proposer)
    result = outcome.result
    assert len(result.items) == 1 and result.omitted_items == 0
    draft = result.items[0]
    assert draft.content.model_dump(mode="json") == {
        "description": "단일 파일 한도는 30 MiB이다.",
        "unit": "MiB",
    }
    assert [source.quote for source in draft.sources] == [
        quote,
        "발행은 리더만 수행한다.",
    ]
    assert draft.answer_refs == ("q-1",)
    assert draft.rationale == "답변이 단위를 확정했습니다. 단위를 별도 필드로 둡니다."
    assert [f.field for f in result.changes[0].changed_fields] == [
        "description",
        "unit",
    ]
    assert result.untouched_existing_ids == ("1102",)


# S15P31A108-388: one draft that contradicts the inputs used to discard every
# other draft of the answer; real documents lost their drafts 5 times in 8.
@pytest.mark.asyncio
async def test_split_proposal_of_another_kind_is_dropped_and_the_rest_merges():
    proposer = FakeProposer(
        item("CONSTRAINT", "단일 파일 한도는 30 MiB이다.", base="1101"),
        item("POLICY", "b", base="1101"),
    )
    outcome = await generate_design_drafts(request(), proposer)
    [draft] = outcome.result.items
    assert draft.kind == "CONSTRAINT"
    assert draft.content.description == "단일 파일 한도는 30 MiB이다."
    assert outcome.result.changes[0].conflicting_fields == ()


@pytest.mark.asyncio
async def test_a_contradicting_draft_is_dropped_without_losing_the_others():
    proposer = FakeProposer(
        item("CONSTRAINT", "x", base="9999"),
        item("POLICY", "발행 전 검토를 거친다."),
        {**item("API_CONTRACT", "x"), "content": {"description": "x", "method": "GET"}},
    )
    outcome = await generate_design_drafts(request(), proposer)
    assert [d.content.description for d in outcome.result.items] == [
        "발행 전 검토를 거친다."
    ]
    assert not outcome.usage[0].failed


@pytest.mark.asyncio
async def test_answer_references_to_no_answered_question_are_dropped():
    proposer = FakeProposer(
        item("CONSTRAINT", "한도 단위는 MiB다.", refs=["q-9", "q-2", "q-1"])
    )
    outcome = await generate_design_drafts(request(), proposer)
    assert outcome.result.items[0].answer_refs == ("q-1",)


@pytest.mark.parametrize(
    "bad",
    [
        item("CONSTRAINT", "x", base="9999"),
        item("POLICY", "x", base="1101"),
        {**item("API_CONTRACT", "x"), "content": {"description": "x", "method": "GET"}},
        {**item("REQUIREMENT", "x"), "content": {"description": "   "}},
        # The BE DesignJson rejects a blank field name at any depth.
        {
            **item("REQUIREMENT", "x"),
            "content": {"description": "x", "metadata": {"": "value"}},
        },
    ],
)
@pytest.mark.asyncio
async def test_no_usable_draft_is_invalid_output_with_failed_usage(bad):
    with pytest.raises(LLMError) as error:
        await generate_design_drafts(request(), FakeProposer(bad))
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed and error.value.usage[0].input_tokens == 10


@pytest.mark.asyncio
async def test_keys_are_content_derived_and_order_independent():
    first = item("REQUIREMENT", "한도 단위는 MiB다.", refs=["q-1"])
    second = item("POLICY", "발행 전 검토를 거친다.")
    one = await generate_design_drafts(request(), FakeProposer(first, second))
    two = await generate_design_drafts(request(), FakeProposer(second, first))
    keys_one = {d.content.description: d.candidate_key for d in one.result.items}
    keys_two = {d.content.description: d.candidate_key for d in two.result.items}
    assert keys_one == keys_two
    assert all(key.startswith("draft-") for key in keys_one.values())
    duplicate = await generate_design_drafts(request(), FakeProposer(first, first))
    assert len(duplicate.result.items) == 1
    content = one.result.items[0].content
    assert draft_key("REQUIREMENT", None, content) != draft_key("POLICY", None, content)


@pytest.mark.asyncio
async def test_cap_omits_lowest_ranked_drafts_and_restores_their_bases():
    proposer = FakeProposer(
        item("CONSTRAINT", "응답 시간은 1초 이내다."),
        item("CONSTRAINT", "단일 파일 한도는 30 MiB이다.", base="1101", refs=["q-1"]),
        unresolved=["  보관 기간은 답변이 없다.  ", "보관 기간은 답변이 없다.", "   "],
    )
    outcome = await generate_design_drafts(request(max_items=1), proposer)
    result = outcome.result
    assert [entry.support for entry in result.items] == ["ANSWER"]
    assert result.omitted_items == 1 and result.max_items == 1
    assert result.unresolved == ("보관 기간은 답변이 없다.",)
    capped = await generate_design_drafts(
        request(max_items=1),
        FakeProposer(
            item(
                "CONSTRAINT", "단일 파일 한도는 30 MiB이다.", base="1101", refs=["q-1"]
            ),
            item("POLICY", "발행 전 검토를 거친다.", base="1102", refs=["q-1"]),
        ),
    )
    assert capped.result.items[0].base_design_item_version_id == "1101"
    assert capped.result.untouched_existing_ids == ("1102",)


class FakeProvider:
    def __init__(self, output):
        self.output = output
        self.calls = []

    async def complete(self, request, *, timeout_seconds):
        self.calls.append(request)
        if isinstance(self.output, Exception):
            raise self.output
        return ProviderResponse(
            json.dumps(self.output, ensure_ascii=False), request.model, 12, 7
        )


def llm_proposer(provider, *, project="101", **kwargs):
    return LLMDraftProposer(
        LLMClient(provider),
        context=LLMContext(project_id=project, feature="BEE_DRAFT", request_key="d"),
        model="fake",
        call_key="call-1",
        timeout_seconds=3,
        **kwargs,
    )


@pytest.mark.asyncio
async def test_llm_proposer_sends_blocks_items_and_answered_questions_only():
    provider = FakeProvider({"items": [], "unresolved": []})
    body = request()
    document = parse_source(body.to_source_input())
    result = await llm_proposer(provider).propose(body, document)
    assert result.items == () and result.model == "fake"
    assert result.usage[0].input_tokens == 12 and not result.usage[0].failed
    llm_request = provider.calls[0]
    assert "untrusted" in llm_request.messages[0].content
    payload = json.loads(llm_request.messages[1].content)
    assert payload["document"]["document_version_id"] == "311"
    assert payload["blocks"][1]["text"].startswith("단일 파일 한도는")
    assert [entry["key"] for entry in payload["existing_items"]] == [
        "PLN-001",
        "PLN-002",
    ]
    assert [q["question_key"] for q in payload["answered_questions"]] == ["q-1"]
    assert payload["answered_questions"][0]["answer"] == {
        "type": "SHORT_ANSWER",
        "text": "MiB",
    }
    assert payload["unanswered_question_count"] == 1
    assert payload["max_items"] == 50
    assert llm_request.context.feature == "BEE_DRAFT"
    assert llm_request.response_format == {"type": "json_object"}
    assert llm_request.model_options == {"max_completion_tokens": 16384}


@pytest.mark.parametrize(
    "bad_output",
    [
        {"items": [{"kind": "POLICY", "content": {"description": "x"}}]},
        {
            "items": [
                {
                    "kind": "DEFECT",
                    "content": {"description": "x"},
                    "rationale": "r",
                }
            ],
            "unresolved": [],
        },
        {"items": [], "unresolved": [], "approved": True},
        {"items": "none", "unresolved": []},
        ["not", "an", "object"],
    ],
)
@pytest.mark.asyncio
async def test_structurally_invalid_model_output_keeps_failed_usage(bad_output):
    provider = FakeProvider(bad_output)
    body = request()
    with pytest.raises(LLMError) as error:
        await llm_proposer(provider).propose(body, parse_source(body.to_source_input()))
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed and error.value.usage[0].input_tokens == 12


# S15P31A108-388: the shapes the real model returned on 3,000-7,000-character
# documents. Each used to discard the whole answer.
@pytest.mark.asyncio
async def test_broken_items_are_dropped_and_usable_ones_are_kept(caplog):
    good = item("POLICY", "발행 전 검토를 거친다.")
    provider = FakeProvider(
        {
            "items": [
                {**good, "sources": [{"block_id": "blk_x", "quote": ""}]},
                {**item("REQUIREMENT", "우선순위가 붙은 항목"), "priority": "HIGH"},
                {**item("REQUIREMENT", "근거 없는 항목"), "rationale": ""},
                "문자열 항목",
                {**item("CONSTRAINT", "참조가 빈 항목"), "answer_refs": ["", "q-1"]},
            ],
            "unresolved": ["x" * 2001, "  ", 3],
        }
    )
    body = request()
    proposal = await llm_proposer(provider).propose(
        body, parse_source(body.to_source_input())
    )
    assert [entry.content["description"] for entry in proposal.items] == [
        "발행 전 검토를 거친다.",
        "우선순위가 붙은 항목",
        "참조가 빈 항목",
    ]
    assert proposal.items[0].sources == []
    assert proposal.items[2].answer_refs == ["q-1"]
    assert proposal.dropped_items == 2
    assert proposal.unresolved == ("x" * 2000,)
    assert not proposal.usage[0].failed
    # 지운 필드는 이름만 남겨, 모델이 계약에서 벗어나기 시작하면 로그로 보인다.
    assert "'priority': 1" in caplog.text


# S15P31A108-388: the real model wrote every ERD data contract with fields keyed
# by name, and all of them were rejected for lacking the contract's array.
@pytest.mark.asyncio
async def test_data_contract_fields_keyed_by_name_become_the_array():
    provider = FakeProvider(
        {
            "items": [
                {
                    **item("DATA_CONTRACT", "회원"),
                    "content": {
                        "description": "회원",
                        "entity": "users",
                        "fields": {
                            "id": "BIGINT, 기본 키",
                            "nickname": {"type": "VARCHAR(20)"},
                            # PR #272 리뷰: 값의 name(표시 이름)이 키(필드 식별자)를 덮으면 안 된다.
                            "user_id": {"name": "회원 ID", "type": "BIGINT"},
                            "email": {"name": "email", "type": "VARCHAR(100)"},
                        },
                    },
                }
            ],
            "unresolved": [],
        }
    )
    body = request()
    proposal = await llm_proposer(provider).propose(
        body, parse_source(body.to_source_input())
    )
    assert proposal.items[0].content["fields"] == [
        {"name": "id", "description": "BIGINT, 기본 키"},
        {"name": "nickname", "type": "VARCHAR(20)"},
        {"name": "user_id", "type": "BIGINT", "label": "회원 ID"},
        {"name": "email", "type": "VARCHAR(100)"},
    ]
    outcome = await generate_design_drafts(
        body,
        FakeProposer(
            {**item("DATA_CONTRACT", "회원"), **proposal.items[0].model_dump()}
        ),
    )
    assert outcome.result.items[0].content.model_dump()["entity"] == "users"


# S15P31A108-388 리뷰: 갱신 제안의 fields 패치는 배열을 통째로 바꾸므로, 바뀐 필드만 담은 객체를 배열로
# 바꾸면 기준 항목의 나머지 필드가 조용히 사라진다. 갱신은 바꾸지 않고(그 초안만 거절) 새 항목만 바꾼다.
@pytest.mark.asyncio
async def test_fields_keyed_by_name_are_converted_only_for_a_new_data_contract():
    patch = {
        "description": "회원",
        "entity": "users",
        "fields": {"nickname": "VARCHAR(30)"},
    }
    provider = FakeProvider(
        {
            "items": [
                {**item("DATA_CONTRACT", "회원"), "content": patch},
                {
                    **item("DATA_CONTRACT", "회원"),
                    "content": patch,
                    "base_design_item_version_id": "1103",
                },
            ],
            "unresolved": [],
        }
    )
    body = request()
    proposal = await llm_proposer(provider).propose(
        body, parse_source(body.to_source_input())
    )
    assert proposal.items[0].content["fields"] == [
        {"name": "nickname", "description": "VARCHAR(30)"}
    ]
    assert proposal.items[1].content["fields"] == {"nickname": "VARCHAR(30)"}


@pytest.mark.asyncio
async def test_items_beyond_the_cap_are_cut_and_logged(caplog):
    many = [item("POLICY", f"정책 {n}") for n in range(MAX_ITEMS + 3)]
    body = request()
    proposal = await llm_proposer(
        FakeProvider({"items": many, "unresolved": []})
    ).propose(body, parse_source(body.to_source_input()))
    assert len(proposal.items) == MAX_ITEMS
    assert (
        f"had {MAX_ITEMS + 3} usable items; kept the first {MAX_ITEMS}" in caplog.text
    )


@pytest.mark.asyncio
async def test_an_answer_whose_items_are_all_broken_is_invalid_output():
    provider = FakeProvider({"items": ["a", {"kind": "POLICY"}], "unresolved": []})
    body = request()
    with pytest.raises(LLMError) as error:
        await llm_proposer(provider).propose(body, parse_source(body.to_source_input()))
    assert error.value.code == "invalid_output"
    assert error.value.usage[0].failed


@pytest.mark.asyncio
async def test_scope_mismatch_and_oversized_input_never_reach_the_provider(
    monkeypatch,
):
    provider = FakeProvider({"items": [], "unresolved": []})
    body = request()
    document = parse_source(body.to_source_input())
    with pytest.raises(LLMError) as error:
        await llm_proposer(provider, project="other").propose(body, document)
    assert error.value.code == "invalid_input" and not provider.calls
    monkeypatch.setattr(proposer_module, "MAX_PAYLOAD_BYTES", 10)
    with pytest.raises(LLMError) as error:
        await llm_proposer(provider).propose(body, document)
    assert error.value.code == "invalid_input" and not provider.calls


@pytest.mark.asyncio
async def test_provider_failure_keeps_real_failed_usage():
    provider = FakeProvider(
        ProviderError(
            "model_unavailable", model="fake", input_tokens=4, output_tokens=0
        )
    )
    body = request()
    with pytest.raises(LLMError) as error:
        await llm_proposer(provider).propose(body, parse_source(body.to_source_input()))
    assert error.value.code == "model_unavailable"
    assert error.value.usage[0].failed and error.value.usage[0].input_tokens == 4


API_ITEM = {
    "design_item_version_id": "1103",
    "design_item_id": "1003",
    "key": "API-001",
    "kind": "API_CONTRACT",
    "version_no": 1,
    "status": "DRAFT",
    "content": {
        "description": "로그인 API",
        "method": "POST",
        "path": "/api/auth/login",
        "parameters": [{"name": "email"}, {"name": "password"}],
        "responses": {"200": "세션 발급"},
    },
}
MANUAL_POLICY = {
    "design_item_version_id": "1104",
    "design_item_id": "1004",
    "key": "PLN-004",
    "kind": "POLICY",
    "version_no": 1,
    "status": "APPROVED",
    "content": {"description": "발행 전 검토를 거친다.", "method": "manual"},
}


def with_content(entry, content):
    return {**entry, "content": content}


@pytest.mark.asyncio
async def test_update_keeps_omitted_fields_and_a_null_removes_one():
    body = request(existing_items=[APPROVED, DRAFT_ITEM, API_ITEM, MANUAL_POLICY])
    partial = with_content(
        item("API_CONTRACT", "x", base="1103", refs=["q-1"]),
        {
            "description": "이메일·비밀번호 로그인 API",
            "method": "POST",
            "path": "/api/auth/login",
        },
    )
    outcome = await generate_design_drafts(body, FakeProposer(partial))
    change = outcome.result.changes[0]
    assert change.after.model_dump(mode="json") == {
        **API_ITEM["content"],
        "description": "이메일·비밀번호 로그인 API",
    }
    assert [field.field for field in change.changed_fields] == ["description"]
    assert change.before.model_dump(mode="json") == API_ITEM["content"]

    removal = with_content(item("API_CONTRACT", "x", base="1103"), {"responses": None})
    outcome = await generate_design_drafts(body, FakeProposer(removal))
    change = outcome.result.changes[0]
    after = change.after.model_dump(mode="json")
    assert "responses" not in after and after["description"] == "로그인 API"
    assert after["parameters"] == API_ITEM["content"]["parameters"]
    assert [field.field for field in change.changed_fields] == ["responses"]
    assert change.changed_fields[0].before == {"200": "세션 발급"}
    assert change.changed_fields[0].after is None

    created = with_content(
        item("POLICY", "x"), {"description": "검토를 거친다.", "note": None}
    )
    outcome = await generate_design_drafts(body, FakeProposer(created))
    assert outcome.result.items[0].content.model_dump(mode="json") == {
        "description": "검토를 거친다."
    }


def test_apply_patch_merges_objects_and_drops_nulls_at_any_depth():
    assert apply_patch({}, {"a": {"b": None, "c": 1}, "d": [None]}) == {
        "a": {"c": 1},
        "d": [None],
    }
    assert apply_patch({"a": "x"}, {"a": {"b": 1}}) == {"a": {"b": 1}}
    assert apply_patch({"a": {"b": 1}}, {"a": "x"}) == {"a": "x"}
    assert apply_patch({"a": {"b": 1}}, {"a": None}) == {}


@pytest.mark.asyncio
async def test_nested_objects_merge_key_by_key_and_arrays_replace_whole():
    body = request(existing_items=[APPROVED, DRAFT_ITEM, API_ITEM, MANUAL_POLICY])
    partial = with_content(
        item("API_CONTRACT", "x", base="1103"),
        {"responses": {"400": "잘못된 요청"}, "parameters": [{"name": "email"}]},
    )
    outcome = await generate_design_drafts(body, FakeProposer(partial))
    change = outcome.result.changes[0]
    after = change.after.model_dump(mode="json")
    # The response code the model did not mention survives; the array is
    # replaced as a whole; untouched fields stay.
    assert after["responses"] == {"200": "세션 발급", "400": "잘못된 요청"}
    assert after["parameters"] == [{"name": "email"}]
    assert after["path"] == "/api/auth/login"
    assert [f.field for f in change.changed_fields] == ["parameters", "responses"]
    assert change.changed_fields[1].before == {"200": "세션 발급"}

    nested_removal = with_content(
        item("API_CONTRACT", "x", base="1103"),
        {"responses": {"200": None, "401": "인증 실패"}},
    )
    outcome = await generate_design_drafts(body, FakeProposer(nested_removal))
    after = outcome.result.changes[0].after.model_dump(mode="json")
    assert after["responses"] == {"401": "인증 실패"}

    # Proposals for one base apply in order, so the later value wins, and
    # the field they disagreed on is flagged for the reviewer.
    first = with_content(
        item("API_CONTRACT", "x", base="1103"), {"responses": {"400": "a"}}
    )
    second = with_content(
        item("API_CONTRACT", "x", base="1103"),
        {"responses": {"400": "b", "404": "없음"}},
    )
    outcome = await generate_design_drafts(body, FakeProposer(first, second))
    change = outcome.result.changes[0]
    after = change.after.model_dump(mode="json")
    assert after["responses"] == {"200": "세션 발급", "400": "b", "404": "없음"}
    assert change.conflicting_fields == ("responses",)


@pytest.mark.asyncio
async def test_conflicting_proposals_are_flagged_and_agreeing_ones_are_not():
    body = request(existing_items=[APPROVED, DRAFT_ITEM, API_ITEM, MANUAL_POLICY])

    async def result_of(*patches):
        proposals = [
            with_content(item("API_CONTRACT", "x", base="1103", refs=["q-1"]), p)
            for p in patches
        ]
        return (await generate_design_drafts(body, FakeProposer(*proposals))).result

    async def conflicts(*patches):
        change = (await result_of(*patches)).changes[0]
        return change.conflicting_fields, change.after.model_dump(mode="json")

    flagged, after = await conflicts({"path": "/a"}, {"path": "/b"})
    assert flagged == ("path",) and after["path"] == "/b"
    # Different keys inside one object, or the same value twice: no conflict.
    flagged, _ = await conflicts(
        {"responses": {"400": "a"}}, {"responses": {"404": "b"}}
    )
    assert flagged == ()
    flagged, _ = await conflicts({"path": "/a"}, {"path": "/a", "note": "n"})
    assert flagged == ()
    # One proposal replaces a whole value, another sets a key inside it.
    flagged, _ = await conflicts({"responses": None}, {"responses": {"400": "a"}})
    assert flagged == ("responses",)
    flagged, _ = await conflicts({"responses": {"400": "a"}}, {"responses": "없음"})
    assert flagged == ("responses",)
    # A type change alone is a conflict too: JSON true is not 1.
    flagged, _ = await conflicts({"flag": True}, {"flag": 1})
    assert flagged == ("flag",)
    # An empty object replaces a scalar (a conflict), but it neither
    # overrides nor is overridden by keys set inside the object.
    flagged, after = await conflicts({"meta": "text"}, {"meta": {}})
    assert flagged == ("meta",) and after["meta"] == {}
    flagged, _ = await conflicts({"responses": {"400": "a"}}, {"responses": {}})
    assert flagged == ()
    flagged, _ = await conflicts({"responses": {}}, {"responses": {"400": "a"}})
    assert flagged == ()
    # Proposals that end up back at the stored value still disagreed: the
    # draft is kept, with no field change, so the reviewer sees it.
    result = await result_of({"path": "/a"}, {"path": "/api/auth/login"})
    change = result.changes[0]
    assert change.conflicting_fields == ("path",) and change.changed_fields == ()
    assert change.after == change.before
    assert "1103" not in result.untouched_existing_ids
    # Every verified source and answer reference of the group is kept.
    body = request(
        existing_items=[APPROVED, DRAFT_ITEM, API_ITEM],
        questions=[
            stored_question(f"q-{index}", f"질문 {index}?") for index in range(1, 12)
        ],
    )
    quotes = ["단일 파일", "한도는", "30 MB", "이다", "파일 한도", "단일"]
    proposals = [
        with_content(
            item(
                "API_CONTRACT",
                "x",
                base="1103",
                sources=[{"line": 3, "quote": quotes[index % len(quotes)]}],
                refs=[f"q-{index + 1}"],
            ),
            {"responses": {str(400 + index): "오류"}},
        )
        for index in range(11)
    ]
    outcome = await generate_design_drafts(body, FakeProposer(*proposals))
    draft = outcome.result.items[0]
    assert len(draft.answer_refs) == 11 and len(draft.sources) == 6
    assert outcome.result.changes[0].conflicting_fields == ()


def field_tuples(change):
    return [(f.field, f.change, f.before, f.after) for f in change.changed_fields]


@pytest.mark.asyncio
async def test_null_valued_base_fields_are_kept_and_their_removal_is_a_change():
    nullable = with_content(
        DRAFT_ITEM, {"description": "발행은 리더만 수행한다.", "note": None}
    )
    body = request(existing_items=[APPROVED, nullable])
    # The stored content is passed through untouched, null included.
    assert body.existing_items[1].content.model_dump(mode="json") == {
        "description": "발행은 리더만 수행한다.",
        "note": None,
    }
    # The BE keeps an absent field and a null one as different JSON, so
    # removing the null field is a change the reviewer sees as REMOVED.
    removal = with_content(item("POLICY", "x", base="1102"), {"note": None})
    outcome = await generate_design_drafts(body, FakeProposer(removal))
    change = outcome.result.changes[0]
    assert "note" not in change.after.model_dump(mode="json")
    assert field_tuples(change) == [("note", "REMOVED", None, None)]
    assert outcome.result.untouched_existing_ids == ("1101",)
    # Changing another field keeps the null field in before and after alike.
    reworded = item("POLICY", "발행은 프로젝트 리더만 수행한다.", base="1102")
    outcome = await generate_design_drafts(body, FakeProposer(reworded))
    change = outcome.result.changes[0]
    assert change.before.model_dump(mode="json")["note"] is None
    assert change.after.model_dump(mode="json")["note"] is None
    assert [(f.field, f.change) for f in change.changed_fields] == [
        ("description", "CHANGED")
    ]
    # Giving the null field a value is CHANGED; on an item that never had
    # the field it is ADDED. Either way before is null, and `change` tells.
    added = with_content(item("POLICY", "x", base="1102"), {"note": "검토 필요"})
    outcome = await generate_design_drafts(body, FakeProposer(added))
    assert field_tuples(outcome.result.changes[0]) == [
        ("note", "CHANGED", None, "검토 필요")
    ]
    outcome = await generate_design_drafts(request(), FakeProposer(added))
    assert field_tuples(outcome.result.changes[0]) == [
        ("note", "ADDED", None, "검토 필요")
    ]


@pytest.mark.asyncio
async def test_json_type_changes_are_changes():
    flagged = with_content(
        MANUAL_POLICY, {"description": "발행 전 검토를 거친다.", "strict": True}
    )
    body = request(existing_items=[APPROVED, DRAFT_ITEM, flagged])
    update = with_content(
        item("POLICY", "x", base="1104"),
        {"description": "발행 전 두 명이 검토한다.", "strict": 1},
    )
    outcome = await generate_design_drafts(body, FakeProposer(update))
    changes = outcome.result.changes[0].changed_fields
    assert [(f.field, f.before, f.after) for f in changes] == [
        ("description", "발행 전 검토를 거친다.", "발행 전 두 명이 검토한다."),
        ("strict", True, 1),
    ]
    # The same inside an array of objects: a DATA field default going from
    # 1 to true is a change, not an unchanged restatement.
    data_item = {
        "design_item_version_id": "1105",
        "design_item_id": "1005",
        "key": "DATA-001",
        "kind": "DATA_CONTRACT",
        "version_no": 1,
        "status": "DRAFT",
        "content": {
            "description": "설정",
            "entity": "setting",
            "fields": [{"name": "enabled", "default": 1}],
        },
    }
    body = request(existing_items=[APPROVED, DRAFT_ITEM, data_item])
    toggled = with_content(
        item("DATA_CONTRACT", "x", base="1105"),
        {"fields": [{"name": "enabled", "default": True}]},
    )
    outcome = await generate_design_drafts(body, FakeProposer(toggled))
    changes = outcome.result.changes[0].changed_fields
    assert [(f.field, f.change) for f in changes] == [("fields", "CHANGED")]
    assert changes[0].after == [{"name": "enabled", "default": True}]
    assert "1105" not in outcome.result.untouched_existing_ids


@pytest.mark.asyncio
async def test_free_form_fields_on_other_kinds_are_accepted_and_preserved():
    body = request(existing_items=[APPROVED, DRAFT_ITEM, API_ITEM, MANUAL_POLICY])
    assert body.existing_items[3].content.extra_fields() == {"method": "manual"}
    update = with_content(
        item("POLICY", "x", base="1104"), {"description": "발행 전 두 명이 검토한다."}
    )
    outcome = await generate_design_drafts(body, FakeProposer(update))
    change = outcome.result.changes[0]
    assert change.after.model_dump(mode="json") == {
        "description": "발행 전 두 명이 검토한다.",
        "method": "manual",
    }
    assert outcome.result.items[0].base_status == "APPROVED"
    # The merged API content still has to satisfy its kind.
    broken = with_content(item("API_CONTRACT", "x", base="1103"), {"path": None})
    with pytest.raises(LLMError) as error:
        await generate_design_drafts(body, FakeProposer(broken))
    assert error.value.code == "invalid_output"
