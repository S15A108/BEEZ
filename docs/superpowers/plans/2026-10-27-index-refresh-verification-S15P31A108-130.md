# #130 Index Refresh Verification Preparation

> **For agentic workers:** Use superpowers:executing-plans inline; retain one final independent review.

**Goal:** Prepare #130's READY/publication fixtures and BE verification proposal; keep #129 focused on chunking, embedding and search DTOs.

**Architecture:** Use the existing #129 parser/chunker/PreparedIndex module with a marked synthetic provider. No runtime route, DB service, migration or actual model-call change.

**Tech Stack:** Existing Python/Pydantic/tiktoken/pytest, Markdown and JSON.

**Spec:** [#130 proposal](../../design/document-index-refresh-contract-S15P31A108-130.md), Jira S15P31A108-130, original RAG Task 4/6.

## Constraints

- #130 branch starts at latest fetched dev448eb48 and merges #129's prerequisite module explicitly.
- Wait for PR #21 to merge, then merge latest dev normally, compare the remaining changes and create a separate dev-targeting #130 PR. Do not mix ticket scopes in an immediate PR.
- Preserve published Git history; move net changes with a corrective #129 commit and new #130 commit. No force push.
- Fixture vectors/usage are synthetic; real BE/DB and C/D tests remain incomplete.
- Do not read credentials, call GMS, change shared API/DB, or mark Jira #129/#130 complete.

## Preparation checklist

- [x] Generate four source/artifact examples and 26 BE test cases deterministically.
- [x] Distinguish prior READY from new candidate; state expected surviving key for each publication outcome.
- [x] Validate key sets, source spans, dimensions, API-A04 envelopes, profile and scenario references.
- [x] Verify full AI suite and scope separation after moving the files.

## BE follow-up #213

User scope decision on 2026-10-27 supersedes the former shared completion gate: #130 covers AI fixtures/tools, development measurements and handoff, then closes after its PR merges to dev. BE ownership/DTO/manifest implementation, real PostgreSQL atomicity/isolation and operational C/D tests continue in S15P31A108-213 and do not block #130 closure. See the current #130 proposal and PR handoff body for acceptance criteria. Synthetic tests remain explicitly distinct from actual DB results.

## Ledger

- Original e509d96 mixed #129 DTO notes and #130 fixture preparation; the corrective #129 commit preserves history and removes #130's net changes.
- Current preparation reuses the already reviewed generator/fixture logic, now attributed to S15P31A108-130. The original READY/candidate review finding was fixed before migration.
- Scope audit: #129 corrective commit d34530f differs from55241ac only in runtime README and its own DTO/search contract. #130 restores the exact reviewed fixture/test logic; generator changes are its #130 docstring and Jira traceability metadata.
- Verification after dependency merge: 156 AI tests passed, Ruff lint/format and diff checks clean. A fresh subagent scope review was unavailable; the scope and file provenance checks were performed inline, without claiming another independent review.
