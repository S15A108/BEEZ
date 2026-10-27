# #130 AI Evaluation Execution

Continuation authorized by the user's request to finish their AI part. Spec: `docs/experiments/document-parsing-evaluation.md`, RAG development Tasks 4/6 and #130 contract. Work inline on existing #130 branch; keep #129 unchanged and preserve unrelated untracked files.

## Design and scope

Implement a standalone evaluation package, not a production search/chat endpoint. Reuse the real parser/chunker/GMS embedding adapter. Compare fixed token limits vs structure, each with heading/table context off/on, 600 tokens, overlap 0, exact cosine top-12, context at most 6 chunks/4,000 tokens. Record original Unicode source ranges as gold so chunk counts cannot change the denominator. Four synthetic Markdown kinds and twelve questions are a development pilot, not an independent human-reviewed or production benchmark. Preserve negative/no-evidence and document-instruction cases.

C uses controlled event barriers and an independent trace validator for version/content/citation agreement. Its reference backend is explicitly a simulation; export traces and BE acceptance instructions. Never label the simulation as PostgreSQL testing. Actual BE endpoints/DB/migrations and production #92 answering remain owned by their implementation tickets.

Live D uses configured `gpt-6-luna` through GMS Responses and text-embedding-3-small/1024. Same prompt and output cap across all settings, 3 repetitions, no retries, <=200 paid calls and <=500 estimated credits at captured public prices. Cache identical embedding texts within this run, attribute both logical index cost and actual shared calls. Reserve output cost before dispatch. Store usage, elapsed times, raw answers/citations and failures incrementally without credentials. No provider fallback, silent failed-row exclusion or automatic paid reruns. Unit tests never use GMS.

Alternative: waiting for BE would leave AI tooling unfinished; implementing BE storage here would cross ownership. The chosen standalone runner allows useful AI measurements now and later reruns against BE trace/results.

## Task 1 — Corpus, chunk comparison and scoring

- Add four synthetic documents and twelve authored gold questions/ranges. Mark agent-authored labels as awaiting human review.
- RED: fair gold denominators across split/duplicate chunks; wrong version excluded; partial evidence not counted as full recall; unknown citation/false abstention rejected; token budget/source reconstruction of fixed and structure modes.
- GREEN: implement evaluation-only chunk records, exact scoring and deterministic preparation. No runtime app changes.
- Verify targeted tests then full AI suite. Expected: pass, no network calls.

## Task 2 — Controlled C and bounded live D

- RED: stale answer before final validation rejected; change after final validation keeps validated snapshot; content v1 with citation v2 rejected; budget exhaustion blocks dispatch; incomplete/malformed Responses preserved as failures with usage.
- GREEN: event-barrier simulator + independent trace checks; capped GMS runner, dry plan, JSON checkpoint reports and provenance hashes. Reuse embeddings validation.
- Verify targeted tests and full suite; capture dry-run budget before paid execution. Expected: 3 repetitions × 4 settings × 12 questions = 144 generation rows, plus bounded document/query embedding batches.

## Task 3 — Run, review, handoff

- Execute C simulation and D live once under the fixed budget. Inspect all errors/answers; retain failures in totals. Record cost/latency distributions and numerator/denominator, not just averages.
- Write results, reproducible commands, provisional setting decision and exact BE/human-review blockers in `docs/experiments/`. No unsupported quality/DB readiness claims.
- Verify Ruff, complete AI tests and diff checks. Request one independent review; if unavailable disclose it rather than claim review passed.
- Commit with S15P31A108-130 and push only the #130 feature branch. Keep #130 in progress; don't merge #21 or create #130 PR before its prerequisite merge.

## Review focus

Source-range unions across split chunks; context counted in token limits but not gold evidence; zero-denominator metrics; failures and unknown usage in cost totals; response truncation/refusal; budget before calls; snapshot after-final semantics; evaluation surrogate scores vs human semantic judgement.

## Ledger

- Baseline: #130 at 75648d5; 156 tests passed with Windows session loop option, Ruff clean. Existing default Windows loop has documented intermittent WinError 10014; use session scope for reliable execution and retain Linux defaults.
- Choice: reuse existing development-evaluation design; this pilot's labels are agent-authored and require human review. Production acceptance and actual BE/DB C remain pending.
