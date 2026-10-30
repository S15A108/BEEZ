-- REVIEW ONLY. A database owner must inspect and approve the column list and
-- replica identities before running this manually. This file is not a Flyway
-- migration and is not applied by docker compose or the backend.
-- PostgreSQL 17: each UPDATE/DELETE column list includes the primary key.
CREATE PUBLICATION beez_document_cdc
    FOR TABLE
        public.document (id, project_id, type, created_at, updated_at),
        public.document_version (id, document_id, version_no, status, parse_status,
                                 parent_version_id, change_proposal_id, lock_version,
                                 created_at, updated_at)
    WITH (publish = 'insert, update, delete');
