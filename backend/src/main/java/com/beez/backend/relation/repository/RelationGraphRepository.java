package com.beez.backend.relation.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * MON-A03 관계 그래프에 필요한 다른 영역 표 조회 (task·계획 참조·검증·변경 제안·설계 항목).
 * 엔티티 없이 필요한 칸만 읽고, 모든 조회에 프로젝트 조건을 건다 (프로젝트 칸이 없는 표는 task 까지 조인).
 * 결과 한 줄은 칸 이름 → 값 Map 이다 (listOfRows).
 */
@Repository
public class RelationGraphRepository {

    private final JdbcClient jdbc;

    public RelationGraphRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> task(long projectId, long taskId) {
        return jdbc.sql("""
                        select id, key, title, status, current_plan_revision_id from task
                         where id = :t and project_id = :p
                        """)
                .param("t", taskId).param("p", projectId).query().listOfRows().stream().findFirst();
    }

    /** 작업 번호 → "키 제목". 이 프로젝트 작업만 */
    public Map<Long, String> taskLabels(long projectId, Collection<Long> taskIds) {
        Map<Long, String> out = new HashMap<>();
        if (taskIds.isEmpty()) {
            return out;
        }
        for (Map<String, Object> row : jdbc.sql("select id, key, title from task where id in (:ids) and project_id = :p")
                .param("ids", taskIds).param("p", projectId).query().listOfRows()) {
            out.put(((Number) row.get("id")).longValue(), row.get("key") + " " + row.get("title"));
        }
        return out;
    }

    /**
     * 항목 번호 → "키 제목". 제목은 현재 기준 설계의 버전, 기준에 없으면 가장 최근 버전 것이다.
     * coalesce: 앞 값이 NULL 이면 다음 값을 쓴다.
     */
    public Map<Long, String> itemLabels(long projectId, Collection<Long> itemIds) {
        Map<Long, String> out = new HashMap<>();
        if (itemIds.isEmpty()) {
            return out;
        }
        for (Map<String, Object> row : jdbc.sql("""
                        select di.id, di.key,
                               coalesce(
                                 (select v.title from project p
                                    join baseline_item bi on bi.baseline_id = p.current_baseline_id and bi.design_item_id = di.id
                                    join design_item_version v on v.id = bi.design_item_version_id
                                   where p.id = di.project_id),
                                 (select v.title from design_item_version v where v.design_item_id = di.id
                                   order by v.version_no desc limit 1)) as title
                          from design_item di
                         where di.id in (:ids) and di.project_id = :p
                        """)
                .param("ids", itemIds).param("p", projectId).query().listOfRows()) {
            String label = String.valueOf(row.get("key"));
            if (row.get("title") != null) {
                label = label + " " + row.get("title");
            }
            out.put(((Number) row.get("id")).longValue(), label);
        }
        return out;
    }

    /** 계획 리비전이 참조한 설계 항목 (task_plan_design_ref). 리비전은 작업 행에서 읽은 것만 넘긴다 */
    public List<Map<String, Object>> planRefs(long projectId, long planRevisionId) {
        return jdbc.sql("""
                        select r.design_item_id, r.design_item_version_id, r.relation
                          from task_plan_design_ref r
                          join task_plan_revision pr on pr.id = r.plan_revision_id
                          join task t on t.id = pr.task_id
                         where r.plan_revision_id = :r and t.project_id = :p
                         order by r.design_item_id
                        """)
                .param("r", planRevisionId).param("p", projectId).query().listOfRows();
    }

    /** 이 작업이 시작 또는 도착인 작업 간 선후 관계 */
    public List<Map<String, Object>> taskDependencies(long projectId, long taskId) {
        return jdbc.sql("""
                        select task_id, depends_on_task_id, kind from task_dependency
                         where project_id = :p and (task_id = :t or depends_on_task_id = :t)
                         order by task_id, depends_on_task_id
                        """)
                .param("p", projectId).param("t", taskId).query().listOfRows();
    }

    /** 작업의 현재 계획 리비전에 대한 가장 최근 검증 */
    public Optional<Map<String, Object>> latestValidation(long projectId, long taskId, long planRevisionId) {
        return jdbc.sql("""
                        select v.id, v.status from validation v join task t on t.id = v.task_id
                         where v.task_id = :t and v.plan_revision_id = :r and t.project_id = :p
                         order by v.id desc limit 1
                        """)
                .param("t", taskId).param("r", planRevisionId).param("p", projectId)
                .query().listOfRows().stream().findFirst();
    }

    public List<Map<String, Object>> validationDesignRefs(long projectId, long validationId) {
        return jdbc.sql("""
                        select r.design_item_id, r.design_item_version_id, r.relation
                          from validation_design_ref r
                          join validation v on v.id = r.validation_id
                          join task t on t.id = v.task_id
                         where r.validation_id = :v and t.project_id = :p
                         order by r.design_item_id
                        """)
                .param("v", validationId).param("p", projectId).query().listOfRows();
    }

    public List<Map<String, Object>> validationTaskRefs(long projectId, long validationId) {
        return jdbc.sql("""
                        select r.related_task_id, r.related_plan_revision_id
                          from validation_task_ref r
                          join validation v on v.id = r.validation_id
                          join task t on t.id = v.task_id
                         where r.validation_id = :v and t.project_id = :p
                         order by r.related_task_id
                        """)
                .param("v", validationId).param("p", projectId).query().listOfRows();
    }

    /** 이 작업에서 나온 변경 제안 */
    public List<Map<String, Object>> proposalsOfTask(long projectId, long taskId) {
        return jdbc.sql("""
                        select id, key, status from change_proposal where project_id = :p and task_id = :t order by id
                        """)
                .param("p", projectId).param("t", taskId).query().listOfRows();
    }

    /** 현재 계획이 이 항목을 참조하는 작업 */
    public List<Map<String, Object>> tasksReferencing(long projectId, long itemId) {
        return jdbc.sql("""
                        select t.id, r.design_item_version_id, r.relation
                          from task t join task_plan_design_ref r on r.plan_revision_id = t.current_plan_revision_id
                         where t.project_id = :p and r.design_item_id = :i
                         order by t.id
                        """)
                .param("p", projectId).param("i", itemId).query().listOfRows();
    }

    /**
     * 이 항목을 바꾸려는 진행 중 변경 제안 (제출·검토·승인, 발행·반려 제외).
     * CREATE 는 아직 항목 번호가 없어(V2 CHECK) 여기 나오지 않는다. 제안·항목당 한 줄이다(ux_cpi_item)
     */
    public List<Map<String, Object>> openProposalsOfItem(long projectId, long itemId) {
        return jdbc.sql("""
                        select cp.id, cp.key, cpi.op
                          from change_proposal cp join change_proposal_item cpi on cpi.change_proposal_id = cp.id
                         where cp.project_id = :p and cpi.design_item_id = :i
                           and cp.status in ('PROPOSED','IN_REVIEW','APPROVED')
                         order by cp.id, cpi.op
                        """)
                .param("p", projectId).param("i", itemId).query().listOfRows();
    }
}
