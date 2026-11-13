package com.beez.backend.bee.repository;

import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.designitem.support.SourceLocators;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import org.springframework.stereotype.Repository;

/** bee_answer · bee_answer_feedback · 현재 상태 계산 SQL. 권한은 서비스가 본다 */
@Repository
public class BeeRepository {

    /** 현재 활동 한 건. kind 는 DOCUMENT_REVIEW 또는 MEETING */
    public record Activity(String kind, long targetId, String title) {
    }

    private final JdbcClient jdbc;

    public BeeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 프로젝트의 현재 baseline ID. 아직 승인된 기준이 없으면 비어 있다 */
    public Optional<Long> currentBaseline(long projectId) {
        return jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).optional();
    }

    /**
     * 이 baseline 의 승인(APPROVED) 설계 항목을 AI 요청 모양으로(키 순, 최대 limit). 승인 상태가 아닌 버전은 baseline 에 있어도 제외한다.
     * 출처(source)는 그 baseline 에서의 유효 출처다(baseline_item_effective_source): 문서가 바뀐 baseline 은 옮긴 줄 위치를 쓰고,
     * 옮기지 못한 항목(NEEDS_CONFIRMATION)은 보내지 않는다.
     */
    public List<Map<String, Object>> approvedItems(long projectId, long baselineId, int limit) {
        var rows = jdbc.sql("""
                        select i.id as item_id, i.key, i.kind, v.id as version_id, v.version_no, v.status,
                               v.schema_version, v.content::text as content,
                               dv.id as source_version_id, d.id as source_document_id, dv.version_no as source_version_no,
                               es.source_locator::text as source_locator, es.source_status
                          from baseline_item bi
                          join design_item i on i.id = bi.design_item_id and i.project_id = bi.project_id
                          join design_item_version v on v.id = bi.design_item_version_id and v.design_item_id = i.id
                          join baseline_item_effective_source es on es.baseline_id = bi.baseline_id and es.design_item_id = bi.design_item_id
                          left join document_version dv on dv.id = es.source_document_version_id
                          left join document d on d.id = dv.document_id and d.project_id = bi.project_id
                         where bi.project_id = :p and bi.baseline_id = :b and v.status = 'APPROVED'
                         order by i.key, i.id
                         limit :limit
                        """)
                .param("p", projectId).param("b", baselineId).param("limit", limit).query().listOfRows();
        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("version_id").toString());
            item.put("design_item_id", row.get("item_id").toString());
            item.put("key", row.get("key"));
            item.put("kind", row.get("kind"));
            item.put("version_no", row.get("version_no"));
            item.put("status", row.get("status"));
            item.put("schema_version", row.get("schema_version"));
            item.put("content", DesignJson.JSON.readValue(row.get("content").toString(), new TypeReference<Map<String, Object>>() { }));
            // 문서가 바뀌어 인용 줄을 옮기지 못한 항목의 출처는 보내지 않는다(-406).
            // 인용 없음으로 확인된 항목도 인용할 원문 위치가 없으므로 출처를 보내지 않는다(-422).
            if (row.get("source_document_id") != null && row.get("source_locator") != null
                    && !"NEEDS_CONFIRMATION".equals(row.get("source_status"))
                    && !SourceLocators.isNoQuote(row.get("source_locator"))) {
                Map<String, Object> locator = DesignJson.JSON.readValue(row.get("source_locator").toString(),
                        new TypeReference<Map<String, Object>>() { });
                String location = Objects.toString(locator.get("location"), "");
                if (!location.isBlank()) {
                    item.put("source", Map.of("document_id", row.get("source_document_id").toString(),
                            "document_version_id", row.get("source_version_id").toString(),
                            "version_no", row.get("source_version_no"), "location", location));
                }
            }
            items.add(item);
        }
        return items;
    }

    /** 답변 발급 기록(대화 본문은 저장하지 않는다). 근거로 인용한 문서 버전만 남겨 피드백이 그대로 복사한다 */
    public long issueAnswer(long projectId, long accountId, String mode, List<Long> documentVersionIds, long baselineId) {
        String ids = documentVersionIds.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        return jdbc.sql("""
                        insert into bee_answer(project_id, account_id, mode, document_version_ids, baseline_id)
                        values (:p, :a, :mode, cast(:ids as jsonb), :b) returning id
                        """)
                .param("p", projectId).param("a", accountId).param("mode", mode).param("ids", ids).param("b", baselineId)
                .query(Long.class).single();
    }

    /**
     * 본인이 이 프로젝트에서 받은 답변이면 피드백을 저장(같은 답변 재전송은 덮어씀)하고 true.
     * 남의 답변·다른 프로젝트 답변은 저장하지 않고 false. 근거 문서 버전은 답변 발급 기록에서 가져온다.
     */
    public boolean upsertFeedback(long projectId, long answerId, long accountId, String rating, String comment) {
        return jdbc.sql("""
                        insert into bee_answer_feedback(answer_id, account_id, rating, comment, document_version_ids)
                        select a.id, a.account_id, :rating, :comment, a.document_version_ids
                          from bee_answer a
                         where a.id = :answer and a.project_id = :project and a.account_id = :account
                        on conflict (answer_id, account_id) do update
                           set rating = excluded.rating, comment = excluded.comment,
                               document_version_ids = excluded.document_version_ids, updated_at = now()
                        """)
                .param("rating", rating).param("comment", comment).param("answer", answerId)
                .param("project", projectId).param("account", accountId).update() == 1;
    }

    /**
     * 실행 중인 문서 검토(최근 staleMinutes 분 안)와 열린 회의 중 가장 최근 하나.
     * 검토 대상은 아직 버전이 아닌 작성 사본이라 문서 ID 를 쓴다.
     */
    public Optional<Activity> latestActivity(long projectId, int staleMinutes) {
        return jdbc.sql("""
                        select kind, target_id, title from (
                          select 'DOCUMENT_REVIEW' as kind, d.id as target_id,
                                 d.title as title, r.created_at as at
                            from document_bee_review_request r
                            join document_working_copy w on w.document_id = r.document_id
                            join document d on d.id = w.document_id
                           where d.project_id = :p and r.status = 'RUNNING'
                             and r.created_at > now() - make_interval(mins => :stale)
                          union all
                          select 'MEETING', m.id, m.title, m.created_at
                            from meeting m
                           where m.project_id = :p and m.status = 'OPEN'
                        ) t order by at desc, target_id desc limit 1
                        """)
                .param("p", projectId).param("stale", staleMinutes)
                .query((rs, n) -> new Activity(rs.getString("kind"), rs.getLong("target_id"), rs.getString("title")))
                .optional();
    }
}
