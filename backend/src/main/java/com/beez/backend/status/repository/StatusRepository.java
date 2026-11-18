package com.beez.backend.status.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** MON-D01·C01 집계. 읽기 전용이고 모든 조회에 프로젝트 조건을 건다 */
@Repository
public class StatusRepository {

    private final JdbcClient jdbc;

    public StatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 현재 기준. 최초 발행 전이면 비어 있다 */
    public Optional<Map<String, Object>> currentBaseline(long projectId) {
        return jdbc.sql("""
                        select b.id, b.version_no, b.published_at
                          from project p join baseline b on b.id = p.current_baseline_id and b.project_id = p.id
                         where p.id = :p
                        """)
                .param("p", projectId).query().listOfRows().stream().findFirst();
    }

    /**
     * 문서 종류(DB 값 REQ_SPEC 등)별 문서 수. 종류당 문서는 하나라 draft·approved 는 0 또는 1이다.
     * draft = 미승인 DRAFT 버전이 있거나 작성 건(DOC-A13)이 EDITING·SUBMITTED·REJECTED 인 문서,
     * approved = 승인본(APPROVED 버전)이 있는 문서. 최초 기준 발행 전에도 DOC-A21 승인을 센다.
     * 기준 발행 뒤에는 승인본이 모두 기준에 들어가므로 "기준에 든 문서"와 결과가 같다.
     */
    public List<Map<String, Object>> documentCounts(long projectId) {
        return jdbc.sql("""
                        select d.type,
                               count(*) filter (where exists (select 1 from document_version v
                                                               where v.document_id = d.id and v.status = 'DRAFT')
                                                   or exists (select 1 from document_working_copy w
                                                               where w.document_id = d.id
                                                                 and w.status in ('EDITING', 'SUBMITTED', 'REJECTED'))) as draft_count,
                               count(*) filter (where exists (select 1 from document_version v
                                                               where v.document_id = d.id and v.status = 'APPROVED')) as approved_count
                          from document d
                         where d.project_id = :p
                         group by d.type
                        """)
                .param("p", projectId).query().listOfRows();
    }

    /** 상태별 변경 제안 수 (건수가 있는 상태만) */
    public List<Map<String, Object>> proposalCounts(long projectId) {
        return jdbc.sql("select status, count(*) as cnt from change_proposal where project_id = :p group by status")
                .param("p", projectId).query().listOfRows();
    }
}
