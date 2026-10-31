CREATE SCHEMA cdc_demo;
CREATE TABLE cdc_demo.document (
    id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    title TEXT NOT NULL,
    created_by BIGINT NOT NULL
);
CREATE TABLE cdc_demo.document_version (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES cdc_demo.document(id),
    version_no INTEGER NOT NULL,
    status TEXT NOT NULL,
    parse_status TEXT NOT NULL DEFAULT 'PENDING',
    lock_version INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    content_text TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    created_by BIGINT NOT NULL
);

-- Separate test-only source identity. Publication column lists limit pgoutput,
-- while column SELECT privileges separately restrict initial snapshot reads.
CREATE ROLE doc_capture_reader WITH LOGIN REPLICATION PASSWORD 'doc_reader_local_only';
GRANT USAGE ON SCHEMA cdc_demo TO doc_capture_reader;
GRANT SELECT (id, project_id) ON cdc_demo.document TO doc_capture_reader;
GRANT SELECT (id, document_id, version_no, status, parse_status, lock_version, updated_at)
    ON cdc_demo.document_version TO doc_capture_reader;
CREATE PUBLICATION doc_capture_publication FOR TABLE
    cdc_demo.document (id, project_id),
    cdc_demo.document_version (id, document_id, version_no, status, parse_status, lock_version, updated_at);
