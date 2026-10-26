package com.beez.backend.task.service;

import com.beez.backend.common.api.ApiException;
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
        Optional<Long> row = jdbc.sql("select id from project where id = :id for update")
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
     * 현재 baseline 에서 설계 항목의 버전 id.
     *
     * @return 항목이 현재 baseline 에 없으면 비어 있음
     */
    public Optional<Long> currentVersionIdOf(Long projectId, Long designItemId) {
        return jdbc.sql("""
                        select bi.design_item_version_id
                          from project p
                          join baseline_item bi on bi.baseline_id = p.current_baseline_id
                         where p.id = :projectId
                           and bi.design_item_id = :itemId
                        """)
                .param("projectId", projectId)
                .param("itemId", designItemId)
                .query(Long.class)
                .optional();
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
