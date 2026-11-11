ALTER TABLE meeting ADD COLUMN conflict_guidance JSONB
    CHECK (conflict_guidance IS NULL OR (origin = 'BEE_CONFLICT' AND jsonb_typeof(conflict_guidance) = 'object'));

CREATE TRIGGER trg_meeting_conflict_guidance_immutable
    BEFORE UPDATE OF conflict_guidance ON meeting
    FOR EACH ROW WHEN (OLD.conflict_guidance IS DISTINCT FROM NEW.conflict_guidance)
    EXECUTE FUNCTION forbid_update();
