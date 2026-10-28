-- 임시 번호: 병합 직전 V6/V7 및 다른 작업의 migration 번호를 확인하고 재배정한다.
-- 기존 design_item_dependency의 EXPLICIT은 승인 이력이 아니므로 자동 복사하지 않는다.
ALTER TABLE baseline_item ADD CONSTRAINT uq_baseline_item_version
    UNIQUE (baseline_id, design_item_id, design_item_version_id);

-- 각 관계 확인/해제를 당시 기준 설계와 두 항목의 정확한 버전에 연결한다.
-- 발행된 설계 항목 자체를 변경하거나 task 상태를 바꾸지 않는다.
CREATE TABLE design_dependency_confirmation_event (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id           BIGINT NOT NULL,
    baseline_id          BIGINT NOT NULL,
    from_item_id         BIGINT NOT NULL,
    from_version_id      BIGINT NOT NULL,
    to_item_id           BIGINT NOT NULL,
    to_version_id        BIGINT NOT NULL,
    action               TEXT NOT NULL CHECK (action IN ('CONFIRM', 'REVOKE')),
    reason               TEXT NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 2000),
    actor_account_id     BIGINT NOT NULL REFERENCES account(id),
    occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (from_item_id <> to_item_id),
    FOREIGN KEY (baseline_id, project_id) REFERENCES baseline(id, project_id),
    FOREIGN KEY (baseline_id, from_item_id, from_version_id)
        REFERENCES baseline_item(baseline_id, design_item_id, design_item_version_id),
    FOREIGN KEY (baseline_id, to_item_id, to_version_id)
        REFERENCES baseline_item(baseline_id, design_item_id, design_item_version_id)
);
CREATE INDEX ix_dependency_confirmation_latest
    ON design_dependency_confirmation_event(project_id, baseline_id, from_item_id, to_item_id, id DESC);
CREATE TRIGGER trg_dependency_confirmation_immutable
    BEFORE UPDATE OR DELETE ON design_dependency_confirmation_event
    FOR EACH ROW EXECUTE FUNCTION forbid_update();
