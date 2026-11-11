-- S15P31A108-397. Capture a team's approved baseline for asynchronous backlog generation.
CREATE TABLE task_breakdown_job (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT NOT NULL,
    baseline_id BIGINT NOT NULL,
    requested_by BIGINT REFERENCES account(id),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    input_snapshot JSONB,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    error_code TEXT,
    retryable BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (baseline_id, project_id) REFERENCES baseline(id, project_id),
    CHECK (status = 'COMPLETED' OR requested_by IS NOT NULL),
    CHECK ((status IN ('PENDING', 'RUNNING')) = (input_snapshot IS NOT NULL)),
    CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL)),
    CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    CHECK ((status = 'FAILED') = (error_code IS NOT NULL)),
    CHECK ((status = 'FAILED') = (retryable IS NOT NULL))
);

CREATE UNIQUE INDEX ux_task_breakdown_job_current ON task_breakdown_job(project_id) WHERE status <> 'FAILED';
CREATE INDEX ix_task_breakdown_job_project ON task_breakdown_job(project_id, id DESC);
CREATE INDEX ix_task_breakdown_job_queue ON task_breakdown_job(id) WHERE status IN ('PENDING', 'RUNNING');

-- Projects generated before async jobs were introduced already have a completed batch.
INSERT INTO task_breakdown_job (project_id, baseline_id, status)
SELECT project_id, baseline_id, 'COMPLETED' FROM task_breakdown_batch;
