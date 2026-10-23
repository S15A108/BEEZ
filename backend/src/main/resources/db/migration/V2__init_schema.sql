-- BEEZ 초기 스키마 (ERD v1.1, 43 테이블). 출처: docs/design/03-erd.md
-- 원칙
--   * 현재 승인 설계의 유일한 기준 = project.current_baseline_id. 항목/문서의 "현재 버전"은 baseline 조인으로 구한다.
--   * 과거 검증은 validation_design_ref / validation_task_ref / dev_context_packet 로 그대로 복원한다.
--   * 소유 관계는 (id, parent_id) 복합 FK로 보장한다. 단순 FK는 "존재하는 행"까지만 확인하기 때문.
--   * 불변 테이블은 트리거로 UPDATE를 막는다.
-- 규칙: 이 파일은 dev 머지 후 수정하지 않는다. 구조 변경은 새 번호(V3__...)로 추가한다.
-- pgvector 확장은 V1 에서 켰다. vector(1024) 의 차원 N 은 임베딩 모델 확정 시 새 마이그레이션으로 바꾼다.

-- 공통: 불변 행 보호
CREATE FUNCTION forbid_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = 'check_violation';
END $$;

------------------------------------------------------------------------
-- A. 계정 · 프로젝트 · 에이전트 (전진)
------------------------------------------------------------------------
CREATE TABLE account (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email         TEXT UNIQUE,                      -- 탈퇴 시 NULL (ACC-06)
  password_hash TEXT,
  display_name  TEXT,
  avatar_url    TEXT,
  deleted_at    TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name                TEXT NOT NULL,
  description         TEXT,
  owner_account_id    BIGINT NOT NULL REFERENCES account(id),
  current_baseline_id BIGINT,                     -- FK는 baseline 생성 뒤 추가 (순환)
  archived_at         TIMESTAMPTZ,                -- PRJ-05 숨김
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE project_member (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  account_id BIGINT NOT NULL REFERENCES account(id),
  role       TEXT NOT NULL CHECK (role IN ('LEADER','MEMBER')),
  area       TEXT,                                -- MEM-05 담당 영역(텍스트)
  active     BOOLEAN NOT NULL DEFAULT true,
  left_at    TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, account_id),
  UNIQUE (id, project_id)
);
-- 프로젝트당 활성 팀장은 한 명
CREATE UNIQUE INDEX ux_member_one_leader ON project_member(project_id) WHERE role = 'LEADER' AND active;

CREATE TABLE invitation (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id          BIGINT NOT NULL REFERENCES project(id),
  email               TEXT NOT NULL,
  token_hash          TEXT NOT NULL UNIQUE,
  invited_by          BIGINT NOT NULL REFERENCES account(id),
  expires_at          TIMESTAMPTZ NOT NULL,
  accepted_at         TIMESTAMPTZ,
  accepted_account_id BIGINT REFERENCES account(id),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_token (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id   BIGINT NOT NULL,
  account_id   BIGINT NOT NULL,
  name         TEXT NOT NULL,
  token_hash   TEXT NOT NULL UNIQUE,              -- 평문 저장 금지 (NFR-03)
  last_used_at TIMESTAMPTZ,
  revoked_at   TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- 그 프로젝트의 멤버만 토큰을 가진다. 현재 활성 여부는 요청마다 서비스에서 확인
  FOREIGN KEY (project_id, account_id) REFERENCES project_member(project_id, account_id),
  UNIQUE (id, project_id)
);

CREATE TABLE agent_session (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  agent_token_id  BIGINT NOT NULL REFERENCES agent_token(id),
  session_key     TEXT NOT NULL,
  current_task_id BIGINT,                         -- FK는 task 생성 뒤
  last_report_at  TIMESTAMPTZ,                    -- MON-07, AC-14
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (agent_token_id, session_key)
);

------------------------------------------------------------------------
-- B. 문서 · 설계 항목 · 기준 설계 · 변경 · 승인 · 협의 (오서현)
------------------------------------------------------------------------
CREATE TABLE document (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  type       TEXT NOT NULL CHECK (type IN ('PLAN','ERD','REQ_SPEC','API_SPEC')),
  title      TEXT NOT NULL,
  domain     TEXT,                                -- DOC-04
  created_by BIGINT NOT NULL REFERENCES account(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (id, project_id)
);

CREATE TABLE document_version (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id        BIGINT NOT NULL REFERENCES document(id),
  version_no         INT NOT NULL CHECK (version_no > 0),
  status             TEXT NOT NULL CHECK (status IN ('DRAFT','APPROVED','SUPERSEDED','REJECTED')),
  parse_status       TEXT NOT NULL DEFAULT 'PENDING' CHECK (parse_status IN ('PENDING','OK','FAILED')),
  parse_error        TEXT,
  storage_key        TEXT NOT NULL,
  content_hash       TEXT NOT NULL,
  content_text       TEXT,
  parent_version_id  BIGINT REFERENCES document_version(id),
  change_proposal_id BIGINT,                      -- 수정안으로 생긴 버전 (FK는 아래)
  created_by         BIGINT REFERENCES account(id),
  approved_by        BIGINT REFERENCES account(id),
  approved_at        TIMESTAMPTZ,
  lock_version       INT NOT NULL DEFAULT 0,      -- DOC-10
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_id, version_no),
  UNIQUE (id, document_id),
  CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL) OR status IN ('SUPERSEDED'))
);

CREATE TABLE design_item (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  key        TEXT NOT NULL,                       -- REQ-01 / API-07 / ENT-02 / POL-01, backend 발급
  kind       TEXT NOT NULL CHECK (kind IN ('REQUIREMENT','API_CONTRACT','DATA_CONTRACT','POLICY','CONSTRAINT')),
  domain     TEXT,
  retired_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, key),
  UNIQUE (id, project_id)
);

CREATE TABLE design_item_version (
  id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  design_item_id             BIGINT NOT NULL REFERENCES design_item(id),
  version_no                 INT NOT NULL CHECK (version_no > 0),
  status                     TEXT NOT NULL CHECK (status IN ('DRAFT','APPROVED','SUPERSEDED','REJECTED')),
  title                      TEXT NOT NULL,
  schema_version             INT NOT NULL,         -- kind별 content 스키마 버전
  content                    JSONB NOT NULL,       -- 구조화 본문 (API: method, path, request/response fields …)
  content_hash               TEXT NOT NULL,
  source_document_version_id BIGINT REFERENCES document_version(id),
  source_locator             JSONB,
  origin                     TEXT NOT NULL CHECK (origin IN ('HUMAN','AI_EXTRACTED','AI_PROPOSED')),
  created_by                 BIGINT REFERENCES account(id),
  approved_at                TIMESTAMPTZ,
  created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (design_item_id, version_no),
  UNIQUE (id, design_item_id)
);
-- 승인 이후 본문 불변. 상태는 APPROVED → SUPERSEDED 만 허용
CREATE FUNCTION design_item_version_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'DRAFT' THEN
    IF NEW.content IS DISTINCT FROM OLD.content OR NEW.title IS DISTINCT FROM OLD.title
       OR NEW.schema_version IS DISTINCT FROM OLD.schema_version OR NEW.design_item_id IS DISTINCT FROM OLD.design_item_id
       OR NEW.version_no IS DISTINCT FROM OLD.version_no THEN
      RAISE EXCEPTION 'design_item_version % is immutable once %', OLD.id, OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF NOT (OLD.status = NEW.status OR (OLD.status = 'APPROVED' AND NEW.status = 'SUPERSEDED')) THEN
      RAISE EXCEPTION 'illegal status change % -> %', OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_div_guard BEFORE UPDATE ON design_item_version FOR EACH ROW EXECUTE FUNCTION design_item_version_guard();

CREATE TABLE baseline (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id         BIGINT NOT NULL REFERENCES project(id),
  version_no         INT NOT NULL CHECK (version_no > 0),
  published_by       BIGINT NOT NULL REFERENCES account(id),
  published_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  change_proposal_id BIGINT,                      -- v1은 NULL (FK는 아래)
  note               TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, version_no),
  UNIQUE (id, project_id)
);
CREATE TRIGGER trg_baseline_immutable BEFORE UPDATE ON baseline FOR EACH ROW EXECUTE FUNCTION forbid_update();

ALTER TABLE project ADD CONSTRAINT fk_project_current_baseline
  FOREIGN KEY (current_baseline_id, id) REFERENCES baseline(id, project_id) DEFERRABLE INITIALLY DEFERRED;

-- 기준 설계 = 항목 버전 스냅샷 + 문서 버전 스냅샷 (DOC-08)
CREATE TABLE baseline_item (
  baseline_id            BIGINT NOT NULL,
  project_id             BIGINT NOT NULL,
  design_item_id         BIGINT NOT NULL,
  design_item_version_id BIGINT NOT NULL,
  PRIMARY KEY (baseline_id, design_item_id),     -- 한 기준 설계에 항목당 버전 하나
  FOREIGN KEY (baseline_id, project_id)                REFERENCES baseline(id, project_id),
  FOREIGN KEY (design_item_id, project_id)             REFERENCES design_item(id, project_id),
  FOREIGN KEY (design_item_version_id, design_item_id) REFERENCES design_item_version(id, design_item_id)
);
CREATE TRIGGER trg_baseline_item_immutable BEFORE UPDATE ON baseline_item FOR EACH ROW EXECUTE FUNCTION forbid_update();
CREATE INDEX ix_baseline_item_version ON baseline_item(design_item_version_id);

CREATE TABLE baseline_document (
  baseline_id         BIGINT NOT NULL,
  project_id          BIGINT NOT NULL,
  document_id         BIGINT NOT NULL,
  document_version_id BIGINT NOT NULL,
  PRIMARY KEY (baseline_id, document_id),
  FOREIGN KEY (baseline_id, project_id)            REFERENCES baseline(id, project_id),
  FOREIGN KEY (document_id, project_id)            REFERENCES document(id, project_id),
  FOREIGN KEY (document_version_id, document_id)   REFERENCES document_version(id, document_id)
);
CREATE TRIGGER trg_baseline_document_immutable BEFORE UPDATE ON baseline_document FOR EACH ROW EXECUTE FUNCTION forbid_update();

-- 설계 항목 간 명시적 의존 (design_relation 대체: 종류별 실제 FK)
CREATE TABLE design_item_dependency (
  project_id   BIGINT NOT NULL,
  from_item_id BIGINT NOT NULL,
  to_item_id   BIGINT NOT NULL,
  origin       TEXT NOT NULL CHECK (origin IN ('EXPLICIT','AI_CANDIDATE')),
  confirmed_by BIGINT REFERENCES account(id),
  confirmed_at TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (from_item_id, to_item_id),
  CHECK (from_item_id <> to_item_id),
  FOREIGN KEY (from_item_id, project_id) REFERENCES design_item(id, project_id),
  FOREIGN KEY (to_item_id, project_id)   REFERENCES design_item(id, project_id)
);

-- 기획 보완 질문·답변 (PLN-04·05)
CREATE TABLE planning_question (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id          BIGINT NOT NULL REFERENCES project(id),
  document_version_id BIGINT REFERENCES document_version(id),
  design_item_id      BIGINT,
  kind                TEXT NOT NULL CHECK (kind IN ('MISSING_CANDIDATE','CLARIFICATION','CONFLICT')),
  question            TEXT NOT NULL,
  origin              TEXT NOT NULL CHECK (origin IN ('model','template','model_failed_template','human')),
  status              TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ANSWERED','DISMISSED')),
  answer              TEXT,
  answered_by         BIGINT REFERENCES account(id),
  answered_at         TIMESTAMPTZ,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (design_item_id, project_id) REFERENCES design_item(id, project_id),
  CHECK ((status = 'ANSWERED') = (answer IS NOT NULL AND answered_at IS NOT NULL))
);

CREATE TABLE change_proposal (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id            BIGINT NOT NULL REFERENCES project(id),
  key                   TEXT NOT NULL,              -- CP-012
  status                TEXT NOT NULL CHECK (status IN ('PROPOSED','IN_REVIEW','APPROVED','REJECTED','PUBLISHED')),
  base_baseline_id      BIGINT NOT NULL,            -- APR-05 충돌 검사
  proposer_kind         TEXT NOT NULL CHECK (proposer_kind IN ('HUMAN','AGENT')),
  proposer_account_id   BIGINT NOT NULL REFERENCES account(id),
  proposer_token_id     BIGINT REFERENCES agent_token(id),
  task_id               BIGINT,                     -- FK는 task 생성 뒤
  validation_id         BIGINT,                     -- FK는 validation 생성 뒤
  reason                TEXT NOT NULL,
  published_baseline_id BIGINT,
  lock_version          INT NOT NULL DEFAULT 0,     -- NFR-05
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, key),
  UNIQUE (id, project_id),
  FOREIGN KEY (base_baseline_id, project_id)      REFERENCES baseline(id, project_id),
  FOREIGN KEY (published_baseline_id, project_id) REFERENCES baseline(id, project_id),
  CHECK ((proposer_kind = 'AGENT') = (proposer_token_id IS NOT NULL)),
  CHECK ((status = 'PUBLISHED') = (published_baseline_id IS NOT NULL))
);
ALTER TABLE baseline ADD CONSTRAINT fk_baseline_proposal
  FOREIGN KEY (change_proposal_id, project_id) REFERENCES change_proposal(id, project_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE document_version ADD CONSTRAINT fk_docver_proposal
  FOREIGN KEY (change_proposal_id) REFERENCES change_proposal(id);

CREATE TABLE change_proposal_item (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  change_proposal_id BIGINT NOT NULL REFERENCES change_proposal(id),
  op                 TEXT NOT NULL CHECK (op IN ('CREATE','UPDATE','RETIRE')),
  design_item_id     BIGINT REFERENCES design_item(id),
  before_version_id  BIGINT,
  new_key            TEXT,                         -- CREATE 시 희망 키 (최종 발급은 backend)
  new_kind           TEXT,
  proposed_content   JSONB,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (before_version_id, design_item_id) REFERENCES design_item_version(id, design_item_id),
  CHECK ((op = 'CREATE') = (design_item_id IS NULL)),
  CHECK (op = 'CREATE' OR before_version_id IS NOT NULL),
  CHECK (op = 'RETIRE' OR proposed_content IS NOT NULL)
);
CREATE UNIQUE INDEX ux_cpi_item ON change_proposal_item(change_proposal_id, design_item_id) WHERE design_item_id IS NOT NULL;

CREATE TABLE change_comment (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  change_proposal_id BIGINT NOT NULL REFERENCES change_proposal(id),
  author_account_id  BIGINT NOT NULL REFERENCES account(id),
  kind               TEXT NOT NULL CHECK (kind IN ('COMMENT','REVIEW_REQUEST','DECISION')),
  body               TEXT NOT NULL,
  target_account_id  BIGINT REFERENCES account(id),  -- REVIEW_REQUEST 대상 (MTG-02)
  resolved_at        TIMESTAMPTZ,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((kind = 'REVIEW_REQUEST') = (target_account_id IS NOT NULL))
);

CREATE TABLE approval (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  change_proposal_id BIGINT NOT NULL UNIQUE REFERENCES change_proposal(id),
  decided_by         BIGINT NOT NULL REFERENCES account(id),  -- 사람 세션 + 현재 LEADER (서비스 검사)
  decision           TEXT NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
  reason             TEXT,
  base_baseline_id   BIGINT NOT NULL REFERENCES baseline(id),
  result_baseline_id BIGINT REFERENCES baseline(id),
  decided_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (decision = 'APPROVED' OR reason IS NOT NULL),   -- 반려는 사유 필수 (APR-02)
  CHECK (decision = 'APPROVED' OR result_baseline_id IS NULL)
);
CREATE TRIGGER trg_approval_immutable BEFORE UPDATE ON approval FOR EACH ROW EXECUTE FUNCTION forbid_update();

-- 회의: MTG-01~03은 change_comment, MTG-04~09는 아래 세 테이블
CREATE TABLE meeting (
  id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id         BIGINT NOT NULL REFERENCES project(id),
  change_proposal_id BIGINT,
  title              TEXT NOT NULL,
  status             TEXT NOT NULL CHECK (status IN ('OPEN','CLOSED')),
  created_by         BIGINT NOT NULL REFERENCES account(id),
  ai_summary         JSONB,                       -- 요약·결정·누락 후보 (MTG-06·08)
  ai_origin          TEXT CHECK (ai_origin IN ('model','template','model_failed_template')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (id, project_id),
  FOREIGN KEY (change_proposal_id, project_id) REFERENCES change_proposal(id, project_id)
);
CREATE TABLE meeting_participant (
  meeting_id   BIGINT NOT NULL REFERENCES meeting(id),
  account_id   BIGINT NOT NULL REFERENCES account(id),
  status       TEXT NOT NULL CHECK (status IN ('INVITED','JOINED','DECLINED')),
  invited_by   BIGINT REFERENCES account(id),      -- NULL = 비비가 호출
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (meeting_id, account_id)
);
CREATE TABLE meeting_entry (                       -- AI가 분석할 텍스트 기록
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  meeting_id        BIGINT NOT NULL REFERENCES meeting(id),
  author_kind       TEXT NOT NULL CHECK (author_kind IN ('HUMAN','BEE')),
  author_account_id BIGINT REFERENCES account(id),
  body              TEXT NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((author_kind = 'HUMAN') = (author_account_id IS NOT NULL))
);

------------------------------------------------------------------------
-- C. 작업 · 계획 · 검증 (변준성)
------------------------------------------------------------------------
CREATE TABLE task (
  id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id               BIGINT NOT NULL REFERENCES project(id),
  key                      TEXT NOT NULL,          -- T-101
  title                    TEXT NOT NULL,
  assignee_member_id       BIGINT,
  status                   TEXT NOT NULL CHECK (status IN ('DRAFT','READY','IN_PROGRESS','WAITING','COMPLETED','CANCELLED')),
  waiting_reason           TEXT CHECK (waiting_reason IN ('INPUT','APPROVAL','DEPENDENCY','REVALIDATION')),
  current_plan_revision_id BIGINT,
  started_validation_id    BIGINT,                 -- 시작을 허용한 검증
  started_at               TIMESTAMPTZ,
  completed_at             TIMESTAMPTZ,
  cancelled_at             TIMESTAMPTZ,
  lock_version             INT NOT NULL DEFAULT 0,
  created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (project_id, key),
  UNIQUE (id, project_id),
  FOREIGN KEY (assignee_member_id, project_id) REFERENCES project_member(id, project_id),
  CHECK ((status = 'WAITING') = (waiting_reason IS NOT NULL)),
  CHECK (status NOT IN ('IN_PROGRESS','WAITING','COMPLETED') OR started_validation_id IS NOT NULL),
  CHECK (status = 'DRAFT' OR status = 'CANCELLED' OR current_plan_revision_id IS NOT NULL)
);
CREATE INDEX ix_task_project_status ON task(project_id, status);

ALTER TABLE agent_session ADD CONSTRAINT fk_session_task FOREIGN KEY (current_task_id) REFERENCES task(id);
ALTER TABLE change_proposal ADD CONSTRAINT fk_cp_task FOREIGN KEY (task_id, project_id) REFERENCES task(id, project_id);

CREATE TABLE task_plan_revision (                  -- 불변
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id               BIGINT NOT NULL REFERENCES task(id),
  revision_no           INT NOT NULL CHECK (revision_no > 0),
  title                 TEXT NOT NULL,           -- 리비전 당시 제목 스냅샷 (현재 제목은 task.title)
  goal                  TEXT NOT NULL,
  included_scope        JSONB NOT NULL DEFAULT '[]',
  excluded_scope        JSONB NOT NULL DEFAULT '[]',
  approach              TEXT,
  dependency_task_ids   JSONB NOT NULL DEFAULT '[]', -- 리비전 당시 선행 작업 스냅샷 (현재 관계는 task_dependency)
  unlinked              JSONB NOT NULL DEFAULT '[]',  -- 신규·항목 미연결 내용 (AI 분석 대상)
  no_contract_change    BOOLEAN NOT NULL DEFAULT false, -- 명시적 "계약 변경 없음" 선언
  completion_criteria   JSONB NOT NULL DEFAULT '[]',
  input_hash            TEXT NOT NULL,
  created_by_account_id BIGINT NOT NULL REFERENCES account(id),   -- 에이전트 작성이면 토큰 소유 계정
  created_by_token_id   BIGINT REFERENCES agent_token(id),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (task_id, revision_no),
  UNIQUE (id, task_id)
);
CREATE TRIGGER trg_plan_immutable BEFORE UPDATE ON task_plan_revision FOR EACH ROW EXECUTE FUNCTION forbid_update();

ALTER TABLE task ADD CONSTRAINT fk_task_current_plan
  FOREIGN KEY (current_plan_revision_id, id) REFERENCES task_plan_revision(id, task_id) DEFERRABLE INITIALLY DEFERRED;

-- 계획 리비전이 어떤 버전의 어떤 항목을 어떻게 다루는지 (TRC-02). task_design_link 대체
-- 항목당 한 행, 관계는 가장 강한 것 하나: CHANGES > IMPLEMENTS > READS
CREATE TABLE task_plan_design_ref (
  plan_revision_id       BIGINT NOT NULL REFERENCES task_plan_revision(id),
  design_item_id         BIGINT NOT NULL,
  design_item_version_id BIGINT NOT NULL,          -- 계획이 기준으로 삼은 버전
  relation               TEXT NOT NULL CHECK (relation IN ('READS','IMPLEMENTS','CHANGES')),
  proposed_content       JSONB,                    -- CHANGES일 때 변경 후 구조화 내용
  PRIMARY KEY (plan_revision_id, design_item_id),
  FOREIGN KEY (design_item_version_id, design_item_id) REFERENCES design_item_version(id, design_item_id),
  CHECK ((relation = 'CHANGES') = (proposed_content IS NOT NULL))
);
CREATE TRIGGER trg_plan_ref_immutable BEFORE UPDATE ON task_plan_design_ref FOR EACH ROW EXECUTE FUNCTION forbid_update();
CREATE INDEX ix_plan_ref_item ON task_plan_design_ref(design_item_id, relation);  -- 겹침·영향 계산

CREATE TABLE task_dependency (
  project_id         BIGINT NOT NULL,
  task_id            BIGINT NOT NULL,
  depends_on_task_id BIGINT NOT NULL,
  kind               TEXT NOT NULL CHECK (kind IN ('BLOCKS','RELATES')),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (task_id, depends_on_task_id),
  CHECK (task_id <> depends_on_task_id),          -- 순환은 서비스에서 거부
  FOREIGN KEY (task_id, project_id)            REFERENCES task(id, project_id),
  FOREIGN KEY (depends_on_task_id, project_id) REFERENCES task(id, project_id)
);

CREATE TABLE validation (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id             BIGINT NOT NULL,
  plan_revision_id    BIGINT NOT NULL,
  baseline_id         BIGINT NOT NULL REFERENCES baseline(id),  -- 당시 기준 (이력용, 시작 조건 아님)
  status              TEXT NOT NULL CHECK (status IN ('NOT_RUN','RUNNING','PASS','NEEDS_INPUT','NEEDS_REVIEW','STALE','ERROR')),
  trigger             TEXT NOT NULL CHECK (trigger IN ('AGENT','REVALIDATION','DELTA')),
  input_snapshot_hash TEXT NOT NULL,               -- 리비전 + 참조 버전 + 규칙·분석기 버전
  rule_version        TEXT NOT NULL,
  current_attempt     INT NOT NULL DEFAULT 0,      -- 늦은 결과 차단 (fencing)
  summary             JSONB,
  stale_reason        TEXT,
  requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at         TIMESTAMPTZ,
  lock_version        INT NOT NULL DEFAULT 0,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (id, task_id),
  FOREIGN KEY (plan_revision_id, task_id) REFERENCES task_plan_revision(id, task_id),
  CHECK ((status = 'STALE') = (stale_reason IS NOT NULL))
);
CREATE INDEX ix_validation_task ON validation(task_id, requested_at DESC);
CREATE INDEX ix_validation_snapshot ON validation(input_snapshot_hash);

ALTER TABLE task ADD CONSTRAINT fk_task_started_validation
  FOREIGN KEY (started_validation_id, id) REFERENCES validation(id, task_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE change_proposal ADD CONSTRAINT fk_cp_validation FOREIGN KEY (validation_id) REFERENCES validation(id);

-- 검증이 실제로 본 설계 항목 버전 (D4·D5의 기준)
CREATE TABLE validation_design_ref (
  validation_id          BIGINT NOT NULL REFERENCES validation(id),
  design_item_id         BIGINT NOT NULL,
  design_item_version_id BIGINT NOT NULL,
  relation               TEXT NOT NULL CHECK (relation IN ('READS','IMPLEMENTS','CHANGES','DEPENDENCY')),
  PRIMARY KEY (validation_id, design_item_id),
  FOREIGN KEY (design_item_version_id, design_item_id) REFERENCES design_item_version(id, design_item_id)
);
CREATE TRIGGER trg_vdr_immutable BEFORE UPDATE ON validation_design_ref FOR EACH ROW EXECUTE FUNCTION forbid_update();
CREATE INDEX ix_vdr_item ON validation_design_ref(design_item_id);   -- 무효화 경로

-- 검증이 대조한 다른 작업과 그 계획 리비전
CREATE TABLE validation_task_ref (
  validation_id            BIGINT NOT NULL REFERENCES validation(id),
  related_task_id          BIGINT NOT NULL,
  related_plan_revision_id BIGINT NOT NULL,
  PRIMARY KEY (validation_id, related_task_id),
  FOREIGN KEY (related_plan_revision_id, related_task_id) REFERENCES task_plan_revision(id, task_id)
);
CREATE TRIGGER trg_vtr_immutable BEFORE UPDATE ON validation_task_ref FOR EACH ROW EXECUTE FUNCTION forbid_update();

CREATE TABLE validation_run (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_id    BIGINT NOT NULL REFERENCES validation(id),
  attempt          INT NOT NULL CHECK (attempt > 0),
  lease_token      UUID,                           -- 실행 소유권 (워커 식별)
  kind             TEXT NOT NULL CHECK (kind IN ('RULE','MODEL')),
  analyzer_version TEXT,
  cache_hit        BOOLEAN NOT NULL DEFAULT false,
  status           TEXT NOT NULL CHECK (status IN ('RUNNING','OK','TIMEOUT','ERROR','DISCARDED')),
  error            TEXT,
  started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at      TIMESTAMPTZ,
  duration_ms      INT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (validation_id, attempt)
);

CREATE TABLE validation_job (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_id BIGINT NOT NULL UNIQUE REFERENCES validation(id),
  status        TEXT NOT NULL CHECK (status IN ('QUEUED','LEASED','DONE','FAILED','CANCELLED')),
  attempts      INT NOT NULL DEFAULT 0,
  lease_token   UUID,
  lease_until   TIMESTAMPTZ,
  next_run_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((status = 'LEASED') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX ix_job_ready ON validation_job(next_run_at) WHERE status = 'QUEUED';
CREATE INDEX ix_job_lease ON validation_job(lease_until) WHERE status = 'LEASED';

CREATE TABLE validation_finding (
  id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  validation_id          BIGINT NOT NULL REFERENCES validation(id),
  validation_run_id      BIGINT REFERENCES validation_run(id),
  source                 TEXT NOT NULL CHECK (source IN ('RULE','MODEL')),
  kind                   TEXT NOT NULL CHECK (kind IN ('MISMATCH','MISSING_INPUT','MISSING_POLICY','CONFLICT','STALE_REF','ERROR')),
  severity               TEXT NOT NULL CHECK (severity IN ('INFO','WARNING','BLOCKING')),
  design_item_id         BIGINT,
  design_item_version_id BIGINT,
  related_task_id        BIGINT REFERENCES task(id),
  message                TEXT NOT NULL,
  sources                JSONB NOT NULL DEFAULT '[]',
  resolution             TEXT NOT NULL CHECK (resolution IN ('FIX_PLAN','PROPOSE_CHANGE','HUMAN_REVIEW','RETRY')),
  next_action            TEXT,
  created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (design_item_version_id, design_item_id) REFERENCES design_item_version(id, design_item_id),
  CHECK ((design_item_id IS NULL) = (design_item_version_id IS NULL)),
  CHECK (source = 'RULE' OR validation_run_id IS NOT NULL)
);
CREATE INDEX ix_finding_validation ON validation_finding(validation_id);

-- 완료 보고 (불변, 자기 보고)
CREATE TABLE task_completion_report (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id              BIGINT NOT NULL,
  plan_revision_id     BIGINT NOT NULL,
  delta                JSONB NOT NULL DEFAULT '[]',
  has_delta            BOOLEAN NOT NULL,
  summary              TEXT,
  completion_checks    JSONB NOT NULL DEFAULT '[]',
  delta_validation_id  BIGINT,
  reported_by_token_id BIGINT REFERENCES agent_token(id),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (plan_revision_id, task_id)    REFERENCES task_plan_revision(id, task_id),
  FOREIGN KEY (delta_validation_id, task_id) REFERENCES validation(id, task_id),
  CHECK (has_delta = (jsonb_array_length(delta) > 0))
);
CREATE TRIGGER trg_report_immutable BEFORE UPDATE ON task_completion_report FOR EACH ROW EXECUTE FUNCTION forbid_update();

------------------------------------------------------------------------
-- C'. 개발 컨텍스트 (패킷·공유 기록: 박상은 / 조회 이력·비용: 변준성)
------------------------------------------------------------------------
CREATE TABLE dev_context_packet (                  -- get_development_context 결과 (TRC-03 "당시 패킷")
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id       BIGINT NOT NULL REFERENCES project(id),
  task_id          BIGINT,
  agent_token_id   BIGINT REFERENCES agent_token(id),
  request          TEXT NOT NULL,
  baseline_id      BIGINT NOT NULL,
  content          JSONB NOT NULL,                 -- 설계 항목·제약·미확정·출처
  content_hash     TEXT NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (baseline_id, project_id) REFERENCES baseline(id, project_id),
  FOREIGN KEY (task_id, project_id)     REFERENCES task(id, project_id)
);
CREATE TRIGGER trg_packet_immutable BEFORE UPDATE ON dev_context_packet FOR EACH ROW EXECUTE FUNCTION forbid_update();
CREATE TABLE dev_context_packet_ref (
  packet_id              BIGINT NOT NULL REFERENCES dev_context_packet(id),
  design_item_id         BIGINT NOT NULL,
  design_item_version_id BIGINT NOT NULL,
  PRIMARY KEY (packet_id, design_item_id),
  FOREIGN KEY (design_item_version_id, design_item_id) REFERENCES design_item_version(id, design_item_id)
);
-- 계획이 어떤 패킷을 보고 작성됐는지. 검증은 리비전을 통해 따라간다 (NULL = 근거 패킷 없음)
ALTER TABLE task_plan_revision ADD COLUMN context_packet_id BIGINT REFERENCES dev_context_packet(id);

-- 공유 작업 기록 (find/read/write_context). 승인 설계가 아니다.
-- 수정 = 새 리비전 행. previous_id 유니크로 같은 리비전에서 두 갈래 수정이 나오면 두 번째를 거부(409)
CREATE TABLE context_record (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id        BIGINT NOT NULL REFERENCES project(id),
  root_id           BIGINT REFERENCES context_record(id),   -- 첫 리비전의 id (첫 리비전은 NULL)
  previous_id       BIGINT UNIQUE REFERENCES context_record(id),
  revision_no       INT NOT NULL CHECK (revision_no > 0),
  task_id           BIGINT,
  plan_revision_id  BIGINT,                      -- 작성 당시 계획 리비전
  source_packet_id  BIGINT REFERENCES dev_context_packet(id),  -- 작성 당시 보고 있던 패킷
  author_account_id BIGINT NOT NULL REFERENCES account(id),
  author_token_id   BIGINT REFERENCES agent_token(id),         -- NULL = 사람이 웹에서 작성
  kind              TEXT NOT NULL CHECK (kind IN ('DECISION','PROGRESS','ISSUE','HANDOVER')),
  visibility        TEXT NOT NULL CHECK (visibility IN ('SHARED','PRIVATE')),
  summary           TEXT NOT NULL,                -- find 대상, summary 조회
  detail            TEXT,                         -- detail 조회
  embedding         vector(1024),                 -- summary 임베딩, 차원 N 미정
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (task_id, project_id)       REFERENCES task(id, project_id),
  FOREIGN KEY (plan_revision_id, task_id) REFERENCES task_plan_revision(id, task_id),
  CHECK ((revision_no = 1) = (previous_id IS NULL)),
  CHECK ((revision_no = 1) = (root_id IS NULL))
);
CREATE TRIGGER trg_context_record_immutable BEFORE UPDATE ON context_record FOR EACH ROW EXECUTE FUNCTION forbid_update();
CREATE INDEX ix_context_record_root ON context_record(coalesce(root_id, id));
-- 최신 리비전 = 후속 리비전이 없는 행

-- 기록이 참조한 설계 항목 버전 (조회 시 현재 기준과 비교해 "과거 버전" 표시, MCP-06)
CREATE TABLE context_record_ref (
  record_id              BIGINT NOT NULL REFERENCES context_record(id),
  design_item_id         BIGINT NOT NULL,
  design_item_version_id BIGINT NOT NULL,
  PRIMARY KEY (record_id, design_item_id),
  FOREIGN KEY (design_item_version_id, design_item_id) REFERENCES design_item_version(id, design_item_id)
);
CREATE TRIGGER trg_context_record_ref_immutable BEFORE UPDATE ON context_record_ref FOR EACH ROW EXECUTE FUNCTION forbid_update();

CREATE TABLE document_chunk (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_version_id BIGINT NOT NULL REFERENCES document_version(id),
  chunk_no            INT NOT NULL,
  text                TEXT NOT NULL,
  locator             JSONB,
  embedding           vector(1024),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_version_id, chunk_no)
);
CREATE INDEX ix_chunk_embedding ON document_chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE context_access_log (                  -- CTX-05·06
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id     BIGINT NOT NULL REFERENCES project(id),
  agent_token_id BIGINT REFERENCES agent_token(id),
  task_id        BIGINT REFERENCES task(id),
  tool           TEXT NOT NULL,
  target_kind    TEXT NOT NULL,
  target_id      BIGINT,
  baseline_id    BIGINT REFERENCES baseline(id),
  packet_id      BIGINT REFERENCES dev_context_packet(id),
  accessed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE llm_usage (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id        BIGINT NOT NULL REFERENCES project(id),
  feature           TEXT NOT NULL CHECK (feature IN ('DOCUMENT_PARSE','TASK_VALIDATION','CHANGE_IMPACT','EMBEDDING','BEE_CHAT','BEE_DRAFT','TASK_ASSIGNMENT','MEETING_ANALYSIS')),
  request_key       TEXT NOT NULL,
  task_id           BIGINT REFERENCES task(id),
  validation_run_id BIGINT REFERENCES validation_run(id),  -- 시도별 합산 기준
  provider          TEXT NOT NULL,
  model             TEXT NOT NULL,
  tokens_known      BOOLEAN NOT NULL,              -- false면 토큰 수를 0으로 채우지 않는다
  input_tokens      INT,
  output_tokens     INT,
  cache_tokens      INT,
  latency_ms        INT NOT NULL,
  estimated_cost_amount NUMERIC(12,6),             -- 추정. 단가 없으면 NULL
  currency          TEXT,
  failed            BOOLEAN NOT NULL,
  origin            TEXT NOT NULL CHECK (origin IN ('model','template','model_failed_template')),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (request_key),                            -- 같은 호출 중복 집계 방지
  CHECK (tokens_known OR (input_tokens IS NULL AND output_tokens IS NULL)),
  CHECK ((estimated_cost_amount IS NULL) = (currency IS NULL))
);
CREATE INDEX ix_usage_project ON llm_usage(project_id, created_at);

------------------------------------------------------------------------
-- D. 운영 공통 (전진)
------------------------------------------------------------------------
CREATE TABLE notification (
  id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id           BIGINT NOT NULL REFERENCES project(id),
  recipient_account_id BIGINT NOT NULL REFERENCES account(id),
  type                 TEXT NOT NULL,
  title                TEXT NOT NULL,
  body                 TEXT,
  ref_kind             TEXT,
  ref_id               BIGINT,
  dedupe_key           TEXT NOT NULL UNIQUE,
  read_at              TIMESTAMPTZ,
  resolved_at          TIMESTAMPTZ,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_notification_inbox ON notification(recipient_account_id, read_at);

CREATE TABLE activity_event (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  project_id  BIGINT NOT NULL REFERENCES project(id),
  actor_kind  TEXT NOT NULL CHECK (actor_kind IN ('HUMAN','AGENT','SYSTEM')),
  actor_id    BIGINT,
  event_type  TEXT NOT NULL,
  ref_kind    TEXT,
  ref_id      BIGINT,
  payload     JSONB NOT NULL DEFAULT '{}',
  dedupe_key  TEXT NOT NULL UNIQUE,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_activity_timeline ON activity_event(project_id, occurred_at DESC);

CREATE TABLE mcp_audit_log (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  agent_token_id BIGINT REFERENCES agent_token(id),
  account_id     BIGINT REFERENCES account(id),
  project_id     BIGINT REFERENCES project(id),
  tool           TEXT NOT NULL,
  target_ids     JSONB NOT NULL DEFAULT '[]',
  result         TEXT NOT NULL CHECK (result IN ('OK','DENIED','ERROR')),
  latency_ms     INT,
  called_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
