-- S15P31A108-167 관계 조회 API: 설계 항목 의존관계(design_item_dependency)에 상태·근거를 두고,
-- 바뀔 때마다 이력을 남기며, 확정 관계 집합이 바뀔 때마다 프로젝트별 번호(관계 집합 리비전)를 올린다.
-- V2 의 표는 항목 쌍마다 현재 값 한 줄뿐이라 과거 근거·수정 이력과 "재확인 필요"를 남길 수 없다 (#40 3.5절 최소 항목).
-- 이 표를 쓰는 코드는 아직 없어 행이 없다. 혹시 있는 행은 당시 기준·항목 버전·근거를 복원할 수 없으므로
-- 확인자·시각이 있어도 확정으로 보지 않고 모두 재확인 필요(AI 후보는 후보)로 둔다. 팀장이 유지(KEEP)하면 그때 근거가 남는다.
-- 번호(V9)는 머지 직전 dev 기준으로 다시 확인한다.

------------------------------------------------------------------------
-- 1) 현재 관계: 상태와 근거
--    물리 삭제 없음(ERD 공통 규칙). 해제·거절도 상태로 남긴다.
--    EXPLICIT : CONFIRMED(확정) · NEEDS_RECHECK(연결 항목이 바뀌어 팀장 재확인 필요) · RELEASED(해제)
--    AI_CANDIDATE : CANDIDATE(후보) · REJECTED(거절). 후보는 판정·무효화·규칙 확정 근거로 쓰지 않는다 (D5)
------------------------------------------------------------------------
ALTER TABLE design_item_dependency
  ADD COLUMN status TEXT,
  ADD COLUMN reason TEXT;

UPDATE design_item_dependency SET status = 'CANDIDATE' WHERE origin = 'AI_CANDIDATE';
UPDATE design_item_dependency SET status = 'NEEDS_RECHECK' WHERE origin = 'EXPLICIT';

ALTER TABLE design_item_dependency
  ALTER COLUMN status SET NOT NULL,
  ADD CONSTRAINT ck_design_item_dependency_status CHECK (
        (origin = 'EXPLICIT'     AND status IN ('CONFIRMED','NEEDS_RECHECK','RELEASED'))
     OR (origin = 'AI_CANDIDATE' AND status IN ('CANDIDATE','REJECTED'))),
  ADD CONSTRAINT ck_design_item_dependency_confirmed CHECK (
        status <> 'CONFIRMED' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL));

-- 항목 기준 조회(양방향)
CREATE INDEX ix_design_item_dependency_to ON design_item_dependency(to_item_id);

------------------------------------------------------------------------
-- 2) 관계 집합 리비전: 프로젝트의 확정 관계 집합(CONFIRMED·NEEDS_RECHECK 의 구성과 상태)이 바뀔 때마다 1 씩 올린다.
--    행이 없으면 0 (관계 변경이 한 번도 없음). 후보 추가·거절은 올리지 않는다.
--    영향 후보(#197)는 계산에 쓴 값을 저장해 두고 현재 값과 비교해 최신성을 판단한다.
------------------------------------------------------------------------
CREATE TABLE design_relation_set (
  project_id BIGINT PRIMARY KEY REFERENCES project(id),
  revision   BIGINT NOT NULL CHECK (revision > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 위에서 재확인 필요가 된 관계가 있는 프로젝트는 집합이 이미 있으므로 1 에서 시작한다 (0 = 관계 변경이 없었음과 구분)
INSERT INTO design_relation_set (project_id, revision)
SELECT DISTINCT project_id, 1 FROM design_item_dependency WHERE origin = 'EXPLICIT';

------------------------------------------------------------------------
-- 3) 관계 이력 (불변). 한 행 = 한 쌍의 한 번 변경.
--    확정·유지: 유효 기준(baseline)과 그 기준의 양 끝 항목 버전, 근거, 확인자·시각을 반드시 남긴다.
--    재확인 표시·해제: 항목이 기준에서 빠졌을 수 있어 버전은 있을 때만.
--    후보 추가·거절: 집합을 바꾸지 않으므로 relation_set_revision 이 없다.
------------------------------------------------------------------------
CREATE TABLE design_item_dependency_history (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id            BIGINT NOT NULL,
  from_item_id          BIGINT NOT NULL,
  to_item_id            BIGINT NOT NULL,
  action                TEXT NOT NULL CHECK (action IN
                          ('CONFIRM','MARK_RECHECK','KEEP','RELEASE','ADD_CANDIDATE','REJECT_CANDIDATE')),
  origin_after          TEXT NOT NULL CHECK (origin_after IN ('EXPLICIT','AI_CANDIDATE')),
  status_after          TEXT NOT NULL CHECK (status_after IN
                          ('CONFIRMED','NEEDS_RECHECK','RELEASED','CANDIDATE','REJECTED')),
  baseline_id           BIGINT,
  from_version_id       BIGINT,
  to_version_id         BIGINT,
  change_proposal_id    BIGINT,
  relation_set_revision BIGINT,
  reason                TEXT,
  actor_account_id      BIGINT NOT NULL REFERENCES account(id),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (from_item_id <> to_item_id),
  FOREIGN KEY (from_item_id, project_id)       REFERENCES design_item(id, project_id),
  FOREIGN KEY (to_item_id, project_id)         REFERENCES design_item(id, project_id),
  FOREIGN KEY (baseline_id, project_id)        REFERENCES baseline(id, project_id),
  FOREIGN KEY (from_version_id, from_item_id)  REFERENCES design_item_version(id, design_item_id),
  FOREIGN KEY (to_version_id, to_item_id)      REFERENCES design_item_version(id, design_item_id),
  FOREIGN KEY (change_proposal_id, project_id) REFERENCES change_proposal(id, project_id),
  CHECK ((action IN ('ADD_CANDIDATE','REJECT_CANDIDATE')) = (relation_set_revision IS NULL)),
  CHECK (action NOT IN ('CONFIRM','KEEP')
         OR (baseline_id IS NOT NULL AND from_version_id IS NOT NULL AND to_version_id IS NOT NULL)),
  CHECK (action IN ('MARK_RECHECK') OR reason IS NOT NULL)
);
CREATE INDEX ix_dependency_history_from ON design_item_dependency_history(project_id, from_item_id, id);
CREATE INDEX ix_dependency_history_to   ON design_item_dependency_history(project_id, to_item_id, id);
CREATE TRIGGER trg_dependency_history_immutable BEFORE UPDATE ON design_item_dependency_history
  FOR EACH ROW EXECUTE FUNCTION forbid_update();
