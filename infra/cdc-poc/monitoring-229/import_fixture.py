"""Import an explicitly supplied synthetic publication dump from the companion recovery test.

Only INSERT statements from pg_dump are imported; identity sequences are recomputed. The destination is the
same test-owned PostgreSQL service; no production database connection is used.
"""
import os
from pathlib import Path
import control

source = Path(os.environ["BEEZ_229_PUBLICATION_FIXTURE"])
lines = source.read_text(encoding="utf-8").splitlines()
statements = [s for s in lines if s.startswith("INSERT INTO public.")]
# The application's intake trigger rebuilds this derived counter while importing rows.
statements = [s for s in statements if not s.startswith("INSERT INTO public.published_analysis_result_counter ")]
if not any("'leader@test'" in s for s in statements) or any("flyway_schema_history" in s or "spring_session" in s for s in statements):
    raise RuntimeError("Not the expected synthetic recovery-test fixture")
if control.sql("SELECT count(*) FROM account WHERE id=1;").strip() != "0":
    raise RuntimeError("Synthetic low-ID fixture already exists; refusing to overwrite it")
control.sql("""
BEGIN;
SET CONSTRAINTS ALL DEFERRED;
DELETE FROM project_member WHERE project_id=229001;
INSERT INTO project_member(id,project_id,account_id,role) OVERRIDING SYSTEM VALUE VALUES(229001,229001,229001,'LEADER');
""" + "\n".join(statements) + """
DO $$ DECLARE r record; seq text; n bigint; BEGIN
 FOR r IN SELECT table_schema,table_name FROM information_schema.columns WHERE table_schema='public' AND column_name='id' AND is_identity='YES' LOOP
   seq := pg_get_serial_sequence(format('%I.%I',r.table_schema,r.table_name),'id');
   EXECUTE format('SELECT max(id) FROM %I.%I',r.table_schema,r.table_name) INTO n;
   IF seq IS NOT NULL AND n IS NOT NULL THEN PERFORM setval(seq,n,true); END IF;
 END LOOP;
END $$;
COMMIT;
""")
control.event("synthetic_publication_fixture_imported", source="companion real-publication recovery test", production_data=False)
