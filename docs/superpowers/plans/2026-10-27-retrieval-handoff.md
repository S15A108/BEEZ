# Retrieval Integration Handoff Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline; one final independent review.

**Goal:** Deliver the user-selected AI↔BE proposal and executable synthetic verification data before BE integration.

**Architecture:** Keep current runtime APIs and DB unchanged. Generate contract examples through the existing parser, chunker and PreparedIndex service with a clearly marked synthetic embedding provider.

**Tech Stack:** Existing Python/Pydantic/tiktoken/pytest, Markdown and JSON.

**Spec:** [Integration proposal](../../design/document-retrieval-integration-proposal.md), [RAG Task 4](2026-10-26-document-rag-development.md).

## Constraints

- Do not publish the proposed document-chunks API or choose BE migration numbers.
- Do not call GMS, read credentials or assert that mock fixture verification proves DB authorization/atomicity.
- Use 1024-dimensional finite nonzero synthetic vectors; mark provider/profile/usage as fixture-only.
- Keep expected outcomes independent from any fake BE filtering implementation.
- Preserve parser/chunker/profile/source identity and produce deterministic outputs.

## Tasks

- [x] Draft the ownership, request/response, publication and search invariants for BE review.
- [x] Define two-project/version/membership fixtures and explicit DB test scenarios.
- [x] Add failing generator/packet-integrity tests and implement the generator.
- [x] Generate the review artifact; verify source spans, key sets, dimensions and expected-result references.
- [x] Run all AI tests, Ruff and independent review.

Integration: commit the reviewable handoff under S15P31A108-129; the integration result is recorded in the chat and Git history.

## Ledger

- User selected contract proposal and verification data, rather than BE storage/search implementation.
- Latest fetched dev 448eb48 has no document/retrieval service; PR #21 head55241ac is not merged into dev. PreparedIndex and source types are internal, while public DTOs remain proposals.
- The generator CLI test failed for the missing generator, then five artifact tests passed. Independent review found that READY seed and candidate were identical; a new test reproduced that weakness before the fix.
- The prior READY for version202 now has a distinct chunking profile, keys, embedding input and vector values, with the same document version and embedding profile. All publication cases specify the expected surviving READY index; replay first performs a successful publication.
- Final verification: 156 AI tests passed, Ruff lint/format and diff checks clean; four synthetic document artifacts and 26 BE scenarios generated. No GMS/DB request or runtime API/DB modification.
- Final review: prior READY/candidate finding resolved; no remaining substantive findings. The deliverable JSON was regenerated after the fix.
