package com.beez.backend.task.service;

import com.beez.backend.common.api.ApiException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 프로젝트의 "현재 승인 설계" 를 읽는 조회 전용 도우미.
 * 설계 항목·baseline 엔티티(오서현, S15P31A108-98)가 아직 없어 SQL 로 읽는다. 읽기만 하므로 엔티티를 두 벌 만들지 않는다.
 *
 * <p>원칙(ERD): 현재 버전의 유일한 기준은 project.current_baseline_id 이고, 항목의 현재 버전은 baseline_item 조인으로 구한다.
 */
@Component
public class ProjectDesignLookup {

    private final JdbcClient jdbc;

    public ProjectDesignLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 프로젝트 행을 잠근다 (SELECT … FOR UPDATE). 이 트랜잭션이 끝날 때까지 같은 프로젝트를 잠그려는 다른 요청은 기다린다.
     * 표시 키 발급, 시작 게이트처럼 "프로젝트 단위로 한 줄씩 처리해야 하는 일" 앞에서 부른다. 반드시 @Transactional 안에서 호출.
     *
     * @return 프로젝트 행이 있으면 true
     */
    public boolean lockProject(Long projectId) {
        // 삭제된(archived) 프로젝트는 잠그지 않는다 → 호출자가 404 로 처리
        Optional<Long> row = jdbc.sql("select id from project where id = :id and archived_at is null for update")
                .param("id", projectId)
                .query(Long.class)
                .optional();
        return row.isPresent();
    }

    /** 현재 baseline id. 잠그지 않는 읽기 */
    public Optional<Long> currentBaselineId(Long projectId) {
        return jdbc.sql("select current_baseline_id from project where id = :id")
                .param("id", projectId)
                .query(Long.class)
                .optional();
    }

    /**
     * 현재 baseline 에서 설계 항목의 버전 id. 폐기된 항목(design_item.retired_at)은 없는 것으로 본다.
     *
     * @return 항목이 현재 baseline 에 없거나 폐기됐으면 비어 있음
     */
    public Optional<Long> currentVersionIdOf(Long projectId, Long designItemId) {
        return jdbc.sql("""
                        select bi.design_item_version_id
                          from project p
                          join baseline_item bi on bi.baseline_id = p.current_baseline_id
                          join design_item di on di.id = bi.design_item_id and di.project_id = p.id
                         where p.id = :projectId
                           and bi.design_item_id = :itemId
                           and di.retired_at is null
                        """)
                .param("projectId", projectId)
                .param("itemId", designItemId)
                .query(Long.class)
                .optional();
    }

    /**
     * 지정한 baseline 에서 설계 항목의 버전 id. 현재 포인터 대신 번호를 받는 이유: 설계 발행 트랜잭션 안(현재 포인터를 새 기준으로
     * 바꾸기 전)에서 새 기준으로 규칙을 돌려야 하는 곳(-163 재접수)이 있다. 폐기된 항목은 없는 것으로 본다.
     */
    public Optional<Long> versionIdIn(Long projectId, Long baselineId, Long designItemId) {
        return jdbc.sql("""
                        select bi.design_item_version_id
                          from baseline_item bi
                          join design_item di on di.id = bi.design_item_id and di.project_id = :projectId
                         where bi.baseline_id = :baselineId
                           and bi.design_item_id = :itemId
                           and di.retired_at is null
                        """)
                .param("projectId", projectId).param("baselineId", baselineId).param("itemId", designItemId)
                .query(Long.class)
                .optional();
    }

    /**
     * 설계 발행(-163) 의 WAITING 대상: 현재 계획이 참조하거나 시작 검증이 실제 인용한 정책 버전이 바뀐 IN_PROGRESS 작업.
     * (항목, 변경 전 버전) 쌍을 한 번의 조회로 묶는다(VALUES). 작업 번호 오름차순, 중복 없음.
     */
    public List<Long> inProgressTaskIdsReferencingAtOrBelow(Long projectId, List<Long> itemIds, List<Long> beforeVersionIds) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < itemIds.size(); i++) {
            if (i > 0) {
                values.append(", ");
            }
            values.append("(:item").append(i).append(", :before").append(i).append(")");
        }
        JdbcClient.StatementSpec spec = jdbc.sql(("""
                        with changed(item_id, before_version_id) as (values %s), affected as (
                        select t.id
                          from changed
                          join design_item_version bv on bv.id = changed.before_version_id
                          join task_plan_design_ref r on r.design_item_id = changed.item_id
                          join design_item_version rv on rv.id = r.design_item_version_id and rv.version_no <= bv.version_no
                          join task t on t.current_plan_revision_id = r.plan_revision_id
                         where t.project_id = :projectId
                           and t.status = 'IN_PROGRESS'
                        union
                        select t.id
                          from changed
                          join design_item_version bv on bv.id = changed.before_version_id
                          join validation_policy_ref r on r.design_item_id = changed.item_id
                          join design_item_version rv on rv.id = r.design_item_version_id and rv.version_no <= bv.version_no
                          join task t on t.started_validation_id = r.validation_id
                         where t.project_id = :projectId
                           and t.status = 'IN_PROGRESS'
                        ) select id from affected order by id
                        """).formatted(values))
                .param("projectId", projectId);
        for (int i = 0; i < itemIds.size(); i++) {
            spec = spec.param("item" + i, itemIds.get(i)).param("before" + i, beforeVersionIds.get(i));
        }
        return spec.query(Long.class).list();
    }

    /** baseline 이 한 번도 발행되지 않은 프로젝트면 409 baseline_missing (명세 VAL-A01 와 같은 코드) */
    public Long requireCurrentBaseline(Long projectId) {
        Optional<Long> baseline = currentBaselineId(projectId);
        if (baseline.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_missing", "승인된 기준 설계가 아직 없습니다");
        }
        return baseline.get();
    }
}
