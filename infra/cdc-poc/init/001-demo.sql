CREATE SCHEMA cdc_demo;

-- Local illustrative subset of the proposed production schema; no application data.
CREATE TABLE cdc_demo.baselines (
    id bigint PRIMARY KEY,
    project_id bigint NOT NULL,
    description text NOT NULL,
    UNIQUE (id, project_id)
);

CREATE TABLE cdc_demo.publication_outbox (
    event_id uuid PRIMARY KEY,
    event_type text NOT NULL CHECK (event_type = 'DESIGN_PUBLISHED'),
    event_version integer NOT NULL CHECK (event_version > 0),
    project_id bigint NOT NULL,
    baseline_id bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    FOREIGN KEY (baseline_id, project_id) REFERENCES cdc_demo.baselines(id, project_id),
    UNIQUE (baseline_id, event_type)
);
