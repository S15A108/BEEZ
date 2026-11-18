-- REVIEW ONLY. A database owner must review replica identity, SELECT/replication
-- privileges, WAL retention and access controls before manually creating this.
-- Not a Flyway migration and never applied by the application or compose.
CREATE PUBLICATION beez_published_analysis_cdc
    FOR TABLE public.published_change_analysis (id, status)
    WITH (publish = 'insert, update, delete');
