# #229 production intake capture and recovery verification

Run from the repository root on Windows with JDK 21, Docker Desktop, Python 3 and the Gradle dependencies available:

```powershell
# The runner builds this checkout's JAR before starting its isolated resources.
python infra/cdc-poc/recovery-229/run.py
```

Optional real frontend verification uses the repository's installed frontend dependencies and a Playwright browser:

```powershell
$env:BEEZ_229_UI = 'true'
python infra/cdc-poc/recovery-229/run.py
```

The runner refuses to touch an existing `beez-cdc-recovery-229-test` Compose project. It owns only the resources it starts, publishes PostgreSQL `15329`, Kafka `29229` and Connect `18329` on loopback, and removes its containers/network in `finally`. It never removes existing volumes or uses the shared development database. Credentials, accounts and text are synthetic.

The Spring application and Debezium use **the same PostgreSQL database**. The connector configuration is read from `infra/cdc/dev/intake-connector.json`; only the isolated database credentials, topic prefix, slot and publication identifiers are substituted. The production `id,status` column allowlist and initial-snapshot selection are preserved. Debezium uses a dedicated non-superuser replication role with only `SELECT(id,status)` and a publication restricted to those columns. Direct reads of `project_id` and account emails are verified to fail with PostgreSQL permission error `42501`.

The single ordered test verifies:

1. A real approval/publication transaction creates an intake before connector registration; the initial Debezium snapshot (`op=r`) triggers the actual intake listener and dispatcher.
2. A subsequent real publication is captured as a streaming insert (`op=c`) and finishes with one durable attempt.
3. While the actual application listener is stopped, a newly published row remains PENDING, the group has positive lag and no attempt exists. Restarting the same listener/group consumes it and completes the analysis.
4. After the group is empty, its real committed offset is rewound to zero. The same topic/partition/offsets are delivered again and durable claims prevent additional analysis attempts or calls for completed rows.
5. A production approval/publication transaction is rolled back. It leaves no intake or capture record; a later committed publication acts as the stream boundary sentinel.
6. Captured `after` images contain exactly `id` and `status`, without the synthetic private text, account email or password field. The legacy inbox remains empty.
7. A separate process runs the built application JAR and reaches a deliberately blocked HTTP AI call. The process is forcibly terminated while its real intake is RUNNING. A new application process waits for the natural lease expiry, reclaims attempt 2 and stores exactly one final result. The lease is not edited in SQL. Test-only AI timeouts are 5 seconds read and 1 second connect, producing the normal 66-second lease calculation.
8. With `BEEZ_229_UI=true`, the frontend loads the actual stored analysis through a live backend, displays its status and re-fetches it after refresh. The temporary browser input file is deleted and only sanitized evidence/screenshots are retained.

The normal recovery scan and the automatic consumer restart interval are delayed to one hour **only in the parent capture-test context**, so its snapshot/stream/replay cases cannot pass accidentally through the fallback scan, and an intentionally stopped consumer stays stopped. The independent application processes in the force-kill case use the real recovery scanner at a one-second test polling interval. Neither change alters production defaults.

AI is a deterministic double in capture/replay cases and a local HTTP fixture in the force-kill case. Empty candidates without sufficient supporting coverage remain `INSUFFICIENT_EVIDENCE`; they are not labelled confirmed zero impact. These tests demonstrate durable processing and result persistence, not exactly-once external model execution. Separate opt-in FastAPI/GMS tests and the monitoring harness supply provider and alert-delivery evidence.

Raw runtime logs are local diagnostics, not committed evidence. The test prints concise `CDC229 ...=PASS` assertions to the Gradle report. Worker process logs remain in the operating system temporary directory if diagnosis is needed.

For another isolated test environment, `BEEZ_229_EXPORT_FIXTURE` can optionally name a local SQL file. This exports only the synthetic fixture database as column inserts, excluding Flyway and HTTP sessions. It is not production data or publication evidence, and must never be restored into a shared database. The export is disabled by default.

## Related regression evidence and reproduction

Run these commands from the repository root with Docker available. The captured full-suite totals include opt-in skips; the separately enabled CDC, FastAPI and GMS runs below are recorded independently, not added to the full-suite count as unique tests.

```powershell
.\backend\gradlew.bat -p backend --no-daemon test integrationTest --console=plain
.\backend\gradlew.bat -p backend --no-daemon test --tests '*PublishedAnalysisDispatcherTest' --console=plain
```

`evidence/backend-suite.json` records 456 fast tests (2 skipped) and 992 integration tests (25 skipped), with no failures/errors. `evidence/dispatcher-regression.json` records the four dispatcher tests after the retry-latch fix.

The FastAPI tests require an installed Python environment containing the repository's AI dependencies. Set `BEEZ_E2E_PYTHON` to its absolute executable path; the recorded run used an existing Windows AI virtual environment. Both runs start their own FastAPI fixture process and Testcontainers PostgreSQL.

```powershell
$env:BEEZ_E2E_PYTHON = (Resolve-Path '.\ai\.venv\Scripts\python.exe').Path
$env:BEEZ_RUN_AI_E2E = 'true'
$env:BEEZ_IMPACT_E2E_REAL_GMS = 'false'
Remove-Item Env:BEEZ_IMPACT_E2E_GMS_KEY -ErrorAction SilentlyContinue
.\backend\gradlew.bat -p backend --no-daemon integrationTest --tests '*ImpactBackendFastApiE2ETest' --console=plain -PintegrationTestForks=1
```

`evidence/fastapi-e2e.json` records five passed deterministic FastAPI tests and the one intentionally skipped real-GMS test. For the separate provider call, supply `BEEZ_IMPACT_E2E_GMS_KEY` through the local secret store or a process environment without printing it. The recorded run read `GMS_KEY` from an existing local dotenv file into that child-process variable in memory; no credential file or value is included in evidence.

```powershell
# BEEZ_IMPACT_E2E_GMS_KEY must already be populated in this process.
$env:BEEZ_IMPACT_E2E_REAL_GMS = 'true'
.\backend\gradlew.bat -p backend --no-daemon integrationTest --tests '*ImpactBackendFastApiE2ETest.publishedAnalysisAfterUnsupportedDocumentChangeUsesRealGms' --console=plain -PintegrationTestForks=1
Remove-Item Env:BEEZ_IMPACT_E2E_GMS_KEY -ErrorAction SilentlyContinue
Remove-Item Env:BEEZ_IMPACT_E2E_REAL_GMS -ErrorAction SilentlyContinue
Remove-Item Env:BEEZ_RUN_AI_E2E -ErrorAction SilentlyContinue
```

`evidence/real-gms.json` records one passed test, actual provider token usage, lifecycle `COMPLETED` and the expected coverage guard `INSUFFICIENT_EVIDENCE`. This provider test invokes publication processing separately from the Debezium capture acceptance test; it is not a claim that the entire capture test used GMS.

The existing Kafka regression produces deterministic event fixtures into a real broker and uses real Spring/PostgreSQL processing. Disable daemon reuse so its test JVM inherits this invocation's environment and permissions:

```powershell
$env:GRADLE_OPTS = '-Dorg.gradle.daemon=false'
python infra/cdc-poc/intake-integration-227/run.py
Remove-Item Env:GRADLE_OPTS -ErrorAction SilentlyContinue
```

`evidence/existing-kafka-regression.json` records one passed test. The optional PostgreSQL ordering/metrics test uses only temporary tables, but its database must still be disposable. This reproduces the isolated database used for `evidence/postgres-ordering-regression.json` (one passed test):

```powershell
$optionalPg = docker run --rm -d --name beez-229-postgres-optional -p 127.0.0.1:15330:5432 -e POSTGRES_USER=beez_test -e POSTGRES_DB=beez_test -e POSTGRES_HOST_AUTH_METHOD=trust pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d
if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated PostgreSQL container' }
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        docker exec $optionalPg pg_isready -U beez_test *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Isolated PostgreSQL did not become ready' }
    $env:BEEZ_CDC_TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:15330/beez_test'
    .\backend\gradlew.bat -p backend --no-daemon integrationTest --tests '*PublishedAnalysisPostgresIntegrationTest' --console=plain
} finally {
    Remove-Item Env:BEEZ_CDC_TEST_DATABASE_URL -ErrorAction SilentlyContinue
    docker stop $optionalPg
}
```
