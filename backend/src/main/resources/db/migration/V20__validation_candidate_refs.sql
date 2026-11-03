-- Search candidates are analysis inputs, not declared task-plan references.
-- Keep their examined versions separately so publication and start checks can
-- invalidate a PASS when one of those inputs changes.
CREATE TABLE validation_candidate_ref (
  validation_id          BIGINT NOT NULL REFERENCES validation(id),
  design_item_id         BIGINT NOT NULL,
  design_item_version_id BIGINT NOT NULL,
  PRIMARY KEY (validation_id, design_item_id),
  FOREIGN KEY (design_item_version_id, design_item_id) REFERENCES design_item_version(id, design_item_id)
);
CREATE TRIGGER trg_vcr_immutable BEFORE UPDATE ON validation_candidate_ref FOR EACH ROW EXECUTE FUNCTION forbid_update();
CREATE INDEX ix_vcr_item ON validation_candidate_ref(design_item_id);
