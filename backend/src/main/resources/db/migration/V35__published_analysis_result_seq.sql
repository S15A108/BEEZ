-- 발행 후 영향 분석 결과를 에이전트가 이어 받기 위한 결과 순번 (S15P31A108-221, MCP-03 get_design_changes 의 impact_updates).
-- 번호: dev 가 V33 까지, 열린 회의 요약 브랜치가 V34 를 쓴다.
--
-- 결과가 정해질 때(COMPLETED·NOT_EVALUATED·FAILED 로 바뀔 때)마다 프로젝트 안에서 1씩 오르는 번호를 붙인다.
-- 에이전트는 "마지막으로 받은 번호" 이후만 받는다 (result_cursor).
--
-- 번호는 프로젝트별 카운터 행을 잠가 매긴다. 잠금은 커밋까지 유지되므로, 번호가 작은 결과가 먼저 커밋된다.
-- 그래서 읽는 쪽이 번호 N 을 봤다면 N 보다 작은 번호는 이미 모두 보인다 — 건너뛰는 결과가 없다.
-- (DB 시퀀스는 번호 받는 순서와 커밋 순서가 엇갈릴 수 있어 쓰지 않는다)
--
-- 결과를 쓰는 곳(결과 저장·실패 확정·리스 만료 실패·제안 없는 발행의 미평가)이 여러 곳이라 트리거로 한 번에 처리한다.
-- 이 트리거 전에 이미 끝난 결과(V19 이관분 포함)는 번호가 없다 — 에이전트는 "지금부터" 받는다.
-- INSERT … ON CONFLICT DO NOTHING 은 충돌을 확인하기 전에 BEFORE 트리거가 돌아 번호 하나가 비어도 된다 (받는 쪽은 "이 번호 다음"만 본다).

CREATE TABLE published_analysis_result_counter (
  project_id BIGINT PRIMARY KEY REFERENCES project(id),
  last_seq   BIGINT NOT NULL CHECK (last_seq > 0)
);

ALTER TABLE published_change_analysis ADD COLUMN result_seq BIGINT;
CREATE UNIQUE INDEX ux_published_analysis_result_seq ON published_change_analysis(project_id, result_seq)
  WHERE result_seq IS NOT NULL;

CREATE FUNCTION assign_published_analysis_result_seq() RETURNS trigger AS $$
BEGIN
  IF NEW.status IN ('COMPLETED', 'NOT_EVALUATED', 'FAILED')
     AND (TG_OP = 'INSERT'
          OR OLD.status IS DISTINCT FROM NEW.status
          OR OLD.finished_at IS DISTINCT FROM NEW.finished_at) THEN
    -- 첫 결과면 카운터 행을 만들고, 있으면 1 올린다. 어느 쪽이든 그 행을 커밋까지 잠근다
    INSERT INTO published_analysis_result_counter(project_id, last_seq) VALUES (NEW.project_id, 1)
    ON CONFLICT (project_id) DO UPDATE SET last_seq = published_analysis_result_counter.last_seq + 1
    RETURNING last_seq INTO NEW.result_seq;
  END IF;
  RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_published_analysis_result_seq
  BEFORE INSERT OR UPDATE ON published_change_analysis
  FOR EACH ROW EXECUTE FUNCTION assign_published_analysis_result_seq();
