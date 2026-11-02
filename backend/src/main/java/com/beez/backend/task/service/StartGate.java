package com.beez.backend.task.service;

import com.beez.backend.task.entity.Task;
import com.beez.backend.validation.service.FakeValidationAnalyzer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 시작 게이트 — START/RESUME 전에 네 가지를 검사한다 (docs/design/04-task-lifecycle.md §3, 설계 D4).
 *
 * <p><b>반드시 project 행을 잠근 뒤(ProjectDesignLookup.lockProject) 같은 트랜잭션에서 부른다.</b>
 * 그래야 두 작업이 동시에 들어와도 G4(같은 항목 CHANGES 점유)가 경쟁 상태에서 맞다.
 *
 * <p>네 가지를 모두 검사하고 실패를 모아서 돌려준다. 에이전트는 왕복이 비싸서 한 번에 다 알려준다.
 */
@Component
public class StartGate {

    /** 실패 하나. code 는 명세 TSK-A07 오류 코드 */
    public record Failure(String code, String message) {
    }

    public record Result(List<Failure> failures) {
        public boolean passed() {
            return failures.isEmpty();
        }

        /** 첫 실패 코드를 HTTP 응답 코드로 쓴다 */
        public Failure first() {
            return failures.get(0);
        }

        public String combinedMessage() {
            StringBuilder sb = new StringBuilder();
            for (Failure f : failures) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(f.message());
            }
            return sb.toString();
        }
    }

    private final JdbcClient jdbc;

    /**
     * 서버용 가짜 분석기가 켜져 있는가 (beez.validation.fake-analyzer).
     * 꺼져 있으면 가짜 분석기가 낸 PASS 는 G1 에서 거절한다 — 실제 AI 로 바꾼 뒤 설계 대조 없는 PASS 로 시작·재개하지 못하게 (-159 리뷰).
     * final 이 아닌 것은 통합 테스트가 같은 컨텍스트에서 켜고 끄기 위해서다.
     */
    private boolean fakeAnalyzerEnabled;

    public StartGate(JdbcClient jdbc, @Value("${beez.validation.fake-analyzer:false}") boolean fakeAnalyzerEnabled) {
        this.jdbc = jdbc;
        this.fakeAnalyzerEnabled = fakeAnalyzerEnabled;
    }

    public Result evaluate(Task task, Long validationId) {
        List<Failure> failures = new ArrayList<>();
        boolean ownValidation = checkValidationPassed(task, validationId, failures);
        // G2 는 그 검증이 이 작업 것일 때만 본다. 남의 검증이면 G1 이 이미 거절했고, 그 내용을 더 읽지 않는다
        if (ownValidation) {
            checkReferencesCurrent(task, validationId, failures);
        }
        checkDependenciesCompleted(task, failures);
        checkNoChangesConflict(task, failures);
        return new Result(failures);
    }

    /**
     * G1: validation_id 가 이 작업의 현재 리비전에 대한 PASS 인가.
     * -172 합의 5번: 번호로 먼저 찾고 비교하지 않고, "이 번호 + 이 작업" 으로 찾는다.
     * 작업은 이미 프로젝트 조건으로 찾은 것이라 프로젝트 소속도 함께 보장된다.
     * 다른 작업의 검증과 없는 검증은 같은 응답이다.
     *
     * @return 이 작업의 검증이 있으면 true (PASS 여부와 상관없이). false 면 G2 를 건너뛴다
     */
    private boolean checkValidationPassed(Task task, Long validationId, List<Failure> failures) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        select plan_revision_id, status
                          from validation
                         where id = :id
                           and task_id = :taskId
                        """)
                .param("id", validationId)
                .param("taskId", task.getId())
                .query()
                .listOfRows();
        if (rows.isEmpty()) {
            // 요청한 번호를 메시지에 되풀이하지 않는다. 없는 번호와 남의 번호의 응답이 글자까지 같아야 한다
            failures.add(new Failure("validation_not_passed", "이 작업의 검증을 찾을 수 없습니다"));
            return false;
        }
        Map<String, Object> v = rows.get(0);
        long vRevision = ((Number) v.get("plan_revision_id")).longValue();
        String status = (String) v.get("status");
        if (task.getCurrentPlanRevisionId() == null || vRevision != task.getCurrentPlanRevisionId()) {
            failures.add(new Failure("validation_not_passed", "현재 계획(리비전)에 대한 검증이 아닙니다"));
            return true;
        }
        if (!"PASS".equals(status)) {
            failures.add(new Failure("validation_not_passed", "검증 상태가 PASS 가 아닙니다 (현재 " + status + ")"));
            return true;
        }
        if (!fakeAnalyzerEnabled && passedByFakeAnalyzer(validationId)) {
            failures.add(new Failure("validation_not_passed", "가짜 분석기로 낸 검증 결과라 쓸 수 없습니다. 검증을 다시 요청하세요"));
        }
        return true;
    }

    /**
     * 이 검증의 실행 중 가짜 분석기가 끝낸 것이 있는가. 실행 기록의 분석기 버전에 응답의 model 이 남는다(AnalysisResultApplier).
     * 규칙만으로 끝난 PASS 는 실행이 없어 여기에 걸리지 않는다.
     */
    private boolean passedByFakeAnalyzer(Long validationId) {
        Integer found = jdbc.sql("""
                        select count(*)
                          from validation_run
                         where validation_id = :id
                           and analyzer_version = :fake
                        """)
                .param("id", validationId)
                .param("fake", FakeValidationAnalyzer.MODEL)
                .query(Integer.class)
                .single();
        return found > 0;
    }

    /**
     * G2: 검증이 본 (항목, 버전) 이 지금 현재 baseline 의 버전과 모두 같은가.
     * 전역 baseline 번호는 비교하지 않는다 — 내가 참조한 항목만 본다.
     * evaluate 에서 G1 이 "이 작업의 검증" 을 찾았을 때만 부른다. 쿼리에도 task_id 조건을 걸어
     * 이 메서드만 따로 불려도 남의 검증이 본 설계 항목이 섞이지 않게 한다 (-172 합의 3·5번).
     */
    private void checkReferencesCurrent(Task task, Long validationId, List<Failure> failures) {
        List<Map<String, Object>> stale = jdbc.sql("""
                        select r.design_item_id, r.design_item_version_id as seen, bi.design_item_version_id as current
                          from validation_design_ref r
                          join validation v on v.id = r.validation_id and v.task_id = :taskId
                          left join project p on p.id = :projectId
                          left join baseline_item bi on bi.baseline_id = p.current_baseline_id
                                                    and bi.design_item_id = r.design_item_id
                         where r.validation_id = :validationId
                           and (bi.design_item_version_id is null or bi.design_item_version_id <> r.design_item_version_id)
                        """)
                .param("projectId", task.getProjectId())
                .param("taskId", task.getId())
                .param("validationId", validationId)
                .query()
                .listOfRows();
        if (!stale.isEmpty()) {
            StringBuilder items = new StringBuilder();
            for (Map<String, Object> row : stale) {
                if (items.length() > 0) {
                    items.append(", ");
                }
                items.append(row.get("design_item_id"));
            }
            failures.add(new Failure("version_conflict", "설계 참조가 오래됨 (design_item_id: " + items + ")"));
        }
    }

    /** G3: BLOCKS 선행 작업이 전부 COMPLETED 인가 */
    private void checkDependenciesCompleted(Task task, List<Failure> failures) {
        List<String> pending = jdbc.sql("""
                        select t.key
                          from task_dependency d
                          join task t on t.id = d.depends_on_task_id
                         where d.task_id = :taskId
                           and d.kind = 'BLOCKS'
                           and t.status <> 'COMPLETED'
                         order by t.key
                        """)
                .param("taskId", task.getId())
                .query(String.class)
                .list();
        if (!pending.isEmpty()) {
            failures.add(new Failure("dependency_not_completed", "선행 작업 미완료: " + String.join(", ", pending)));
        }
    }

    /**
     * G4: 이 계획이 CHANGES 하는 항목을, 다른 IN_PROGRESS/WAITING 작업의 현재 계획도 CHANGES 하고 있는가.
     * READS/IMPLEMENTS 끼리는 충돌이 아니다. 충돌 상대의 키·담당자를 응답에 넣어 조율할 수 있게 한다 (AC-04).
     */
    private void checkNoChangesConflict(Task task, List<Failure> failures) {
        if (task.getCurrentPlanRevisionId() == null) {
            return;
        }
        List<Map<String, Object>> conflicts = jdbc.sql("""
                        select distinct t.key, t.assignee_member_id, r.design_item_id
                          from task t
                          join task_plan_design_ref r on r.plan_revision_id = t.current_plan_revision_id
                         where t.project_id = :projectId
                           and t.id <> :taskId
                           and t.status in ('IN_PROGRESS', 'WAITING')
                           and r.relation = 'CHANGES'
                           and r.design_item_id in (
                                 select mine.design_item_id
                                   from task_plan_design_ref mine
                                  where mine.plan_revision_id = :revisionId
                                    and mine.relation = 'CHANGES')
                         order by t.key
                        """)
                .param("projectId", task.getProjectId())
                .param("taskId", task.getId())
                .param("revisionId", task.getCurrentPlanRevisionId())
                .query()
                .listOfRows();
        if (!conflicts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> c : conflicts) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(c.get("key")).append("(담당 member ").append(c.get("assignee_member_id"))
                        .append(", design_item ").append(c.get("design_item_id")).append(")");
            }
            failures.add(new Failure("task_conflict", "같은 설계 항목을 변경 중인 작업이 있습니다: " + sb));
        }
    }
}
