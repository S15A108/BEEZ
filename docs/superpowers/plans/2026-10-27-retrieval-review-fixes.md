# PR #21 Retrieval Review Fixes Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline. Track the checklist below; run one final independent review.

**Goal:** Resolve PR #21's list fragmentation, runtime vocabulary download and blocking token validation feedback under S15P31A108-129.

**Architecture:** Preserve list-root and item source ranges, grouping sibling items within the existing token budget. Provision the pinned tokenizer vocabulary explicitly and load it locally at runtime. Move embedding batch validation to a worker thread within the total request deadline.

**Tech Stack:** Existing Python 3.12, FastAPI, markdown-it-py 4.2.0, tiktoken 0.14.0, pytest and Ruff.

**Spec:** PR #21 review discussed with the user; [RAG plan](2026-10-26-document-rag-development.md), `ai/app/services/retrieval/README.md`.

## Constraints and decisions

- Keep the existing API payload, project/approval boundary and embedding profile; BE storage/search integration remains separate.
- Preserve every source span, endpoint/table/quote boundary and the configured max_chunks guard.
- Add bullet/ordered list roots to the internal SourceContainer metadata, retaining item locators. Advance parser/chunker versions because artifact identity changes. These types are not a public BE/AI contract.
- Runtime vocabulary loading must never download; missing/corrupt assets fail before the server becomes ready. An explicit preparation command downloads and verifies the pinned public vocabulary.
- Keep cl100k_base's pinned pattern/special tokens compatible with the official tokenizer and test that compatibility. Runtime reads the verified local asset without a cache-miss network fallback.
- Preserve validation-before-provider-call behavior, usage accounting, cancellation and the 2,000,000-character limit.
- Keep credentials in ignored ai/.env; no live GMS request is necessary for these fixes.

## Task 1 — Group sibling list items

Files: `schemas/documents.py`, `services/document_parsing/adapters/markdown.py`, `services/retrieval/chunking.py`, source/chunking tests.

- [x] Add failing tests for sibling batching, 1,500-item input, nested siblings and distinct list/quote/endpoint boundaries.
- [x] Preserve full list-container ranges and omit only the leaf item identity from the chunk boundary.
- [x] Bump parser/chunker versions and update source-container expectations.
- [x] Verify source parsing and chunking tests.

## Task 2 — Prepare vocabulary and start offline

Files: `services/retrieval/tokenization.py`, a vocabulary helper, `scripts/prepare_tokenizer.py`, `main.py`, tokenizer/startup tests, CI and README.

- [x] Add failing tests proving cold runtime never downloads and missing/corrupt cache is rejected.
- [x] Load the SHA-256-verified local vocabulary and check token compatibility with official cl100k_base.
- [x] Add explicit preparation command and asynchronous startup warm-up; document persistent image/cache provisioning and runtime-user access.
- [x] Verify tokenizer/startup tests and the preparation command without a model call.

## Task 3 — Keep token validation off the event loop

Files: `services/retrieval/embeddings.py`, embedding tests, README.

- [x] Add failing synchronization-based tests for event-loop progress and validation deadline expiry before provider calls.
- [x] Offload batch validation as one worker task and start the total deadline before validation.
- [x] Verify all AI tests, Ruff and diff checks; obtain final independent review.

Integration: commit fixes with S15P31A108-129 and push to the existing PR #21 branch. The resulting commit and remote verification are recorded in the chat and Git history.

## Ledger

- Start: local and remote PR #21 are 187ff0c; tracked files clean. Existing AI baseline: 136 passing tests, Ruff clean.
- Task 1: six regression cases failed before the change; source/chunking suite then passed 79 cases.
- Task 2: missing/corrupt cache and readiness cases failed before the change; five offline/runtime cases then passed, preparation command succeeded without a model call.
- Task 3: event-loop progress, pre-provider deadline and cancellation cases all failed before offloading.
- Ruling: increase the existing retry-budget test's total window from 10ms to 1s and retry delay to 2s. Validation now belongs to the total budget, so 10ms can legitimately expire before the first provider call. The test still verifies rejection of a retry without sleeping, while the new controlled test verifies validation timeouts.
- Verification: explicit cache and default cache preparation succeeded. Windows Python 3.12.12 bare pytest and session-loop pytest both passed all 150 tests; Ruff lint/format and diff checks passed. No live GMS request was made.
- Final independent review: no substantive findings. Vendor tokenizer pattern, full ranks and special tokens independently confirmed identical with networking blocked.
- Reproduction after fixes: five list items produce two chunks (heading + list), 1,500 items/45,016 characters produce 19 chunks with at most 595 tokens per chunk.
