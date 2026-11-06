package com.beez.backend.designchange.service;

import com.beez.backend.common.api.Ids;
import com.beez.backend.designchange.dto.BaselineSummaryResponse;
import com.beez.backend.designchange.dto.DesignChangesResponse.ImpactUpdates;
import com.beez.backend.designchange.dto.ImpactCandidateResponse;
import com.beez.backend.designchange.dto.ImpactUpdateResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * get_design_changes 의 impact_updates (S15P31A108-221). 발행 후 영향 분석(published_change_analysis, 오서현 -197·-227)의 결과가
 * 정해진 것을 결과 순번(result_seq, V36) 순서로 이어 준다.
 *
 * <p>정한 것 (상세는 docs/design/14-design-changes-impact-updates.md):
 * <ol>
 *   <li>result_cursor 가 없으면 "지금부터": 결과 없이 지금의 마지막 순번을 시작점으로 준다. 과거 후보는 대시보드에서 본다</li>
 *   <li>결과 순번은 커밋 순서대로 오른다(V36 카운터 잠금). 그래서 "마지막으로 받은 순번 다음" 만 읽어도 건너뛰는 결과가 없다</li>
 *   <li>후보는 규칙(explicit_result)·AI(ai_result.candidates) 둘 다. 같은 출처·같은 대상은 한 번만, 규칙 먼저, 작업·항목 번호 순, 최대 10개.
 *       넘친 수는 candidates_truncated</li>
 *   <li>task_id 를 주면 그 작업이 후보에 든 결과만, 후보도 그 작업 것만. 상한은 같다</li>
 *   <li>미평가(NOT_EVALUATED)·실패(FAILED)도 결과로 준다 — 영향 0건과 구분하게. 다만 후보가 없으므로 task_id 범위에는 나오지 않는다</li>
 *   <li>후보는 승인 설계가 아니고 작업 상태를 바꾸지 않는다 (도구 안내에도 적는다)</li>
 * </ol>
 */
@Component
public class ImpactUpdateReader {

    /** 결과 하나에 싣는 후보 상한 (MVP 경계 문서 MCP-03 3번) */
    static final int CANDIDATE_LIMIT = 10;

    /**
     * 결과 한 줄의 후보 전부를 (출처, 순서, 원소) 로 펼치는 SQL 조각. a = published_change_analysis.
     * jsonb_array_elements 는 JSON 배열을 원소마다 한 줄로 펼치는 Postgres 함수, with ordinality 는 원래 순서 번호를 붙인다.
     * ai_result 는 {candidates:[…]} 모양이지만 배열이 아니면(미평가 등) 빈 배열로 본다.
     */
    private static final String CANDIDATES_OF_A = """
            (select 'RULE' as origin, x.e, x.ord
               from jsonb_array_elements(coalesce(a.explicit_result, '[]'::jsonb)) with ordinality as x(e, ord)
             union all
             select 'AI' as origin, y.e, y.ord
               from jsonb_array_elements(case when jsonb_typeof(a.ai_result -> 'candidates') = 'array'
                                              then a.ai_result -> 'candidates' else '[]'::jsonb end) with ordinality as y(e, ord))
            """;

    private final JdbcClient jdbc;

    public ImpactUpdateReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param cursorText 요청의 result_cursor. null·빈 값이면 "지금부터"
     * @param taskId     요청의 task_id (없으면 null)
     * @param limit      한 번에 줄 결과 수
     */
    public ImpactUpdates read(Long projectId, Long taskId, String cursorText, int limit) {
        if (cursorText == null || cursorText.isBlank()) {
            // 지금부터: 결과는 주지 않고 지금 보이는 마지막 순번을 시작점으로. 아직 커밋 안 된 결과는 이보다 큰 번호라 다음에 받는다
            long latest = jdbc.sql("select coalesce(max(result_seq), 0) from published_change_analysis where project_id = :p")
                    .param("p", projectId)
                    .query(Long.class)
                    .single();
            return new ImpactUpdates(List.of(), false, new ImpactUpdatesCursor(latest, taskId).encode());
        }
        ImpactUpdatesCursor cursor = ImpactUpdatesCursor.decode(cursorText);
        cursor.requireSameTask(taskId);

        List<Row> page = findPage(projectId, taskId, cursor.afterSeq(), limit + 1);
        boolean hasMore = page.size() > limit;
        if (hasMore) {
            page = page.subList(0, limit);
        }
        long last = cursor.afterSeq();
        if (!page.isEmpty()) {
            last = page.get(page.size() - 1).resultSeq();
        }

        Map<Long, List<ImpactCandidateResponse>> candidates = findCandidates(page);
        List<ImpactUpdateResponse> items = new ArrayList<>();
        for (Row row : page) {
            List<ImpactCandidateResponse> all = candidates.get(row.id());
            if (all == null) {
                all = List.of();
            }
            List<ImpactCandidateResponse> picked = pick(all, taskId);
            int truncated = 0;
            if (picked.size() > CANDIDATE_LIMIT) {
                truncated = picked.size() - CANDIDATE_LIMIT;
                picked = picked.subList(0, CANDIDATE_LIMIT);
            }
            Instant analyzedAt = null;
            if (row.finishedAt() != null) {
                analyzedAt = row.finishedAt().toInstant();
            }
            items.add(new ImpactUpdateResponse(row.resultSeq(),
                    new BaselineSummaryResponse(Ids.str(row.baselineId()), row.versionNo(), row.publishedAt().toInstant()),
                    Ids.str(row.proposalId()), row.status(), row.evaluationStatus(), row.errorCode(),
                    List.copyOf(picked), truncated, analyzedAt));
        }
        return new ImpactUpdates(items, hasMore, new ImpactUpdatesCursor(last, taskId).encode());
    }

    /**
     * 같은 출처·같은 대상은 하나로, task_id 범위면 그 작업 것만, 그리고 규칙 먼저·작업·항목 번호 순으로 줄 세운다.
     * LinkedHashSet 은 넣은 순서를 지키면서 같은 값은 한 번만 담는 집합이다 (record 는 모든 칸이 같으면 같은 값).
     */
    static List<ImpactCandidateResponse> pick(List<ImpactCandidateResponse> all, Long taskId) {
        Set<ImpactCandidateResponse> unique = new LinkedHashSet<>();
        for (ImpactCandidateResponse c : all) {
            if (taskId != null && !String.valueOf(taskId).equals(c.taskId())) {
                continue;
            }
            unique.add(c);
        }
        List<ImpactCandidateResponse> out = new ArrayList<>(unique);
        // Comparator.comparing(...).thenComparing(...) 는 "앞 기준이 같으면 다음 기준으로" 비교하는 정렬 규칙을 만든다.
        // sort 는 안정 정렬이라 기준이 모두 같으면 원래 순서(분석이 낸 순서)를 지킨다
        out.sort(Comparator.comparing(ImpactUpdateReader::originRank)
                .thenComparing(c -> numberOrMax(c.taskId()))
                .thenComparing(c -> numberOrMax(c.designItemId())));
        return out;
    }

    private static int originRank(ImpactCandidateResponse c) {
        if ("RULE".equals(c.origin())) {
            return 0;
        }
        return 1;
    }

    /** 번호 순으로 세우되 번호가 없으면(또는 숫자가 아니면) 맨 뒤 */
    private static long numberOrMax(String id) {
        if (id == null) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    // ---- 조회 ----

    private record Row(long id, long resultSeq, String status, String evaluationStatus, String errorCode,
                       Timestamp finishedAt, Long proposalId, long baselineId, int versionNo, Timestamp publishedAt) {
    }

    /** 순번 다음 결과들. task_id 범위는 페이지를 자르기 전에 건다 (공통 3절) */
    private List<Row> findPage(Long projectId, Long taskId, long afterSeq, int fetch) {
        StringBuilder sql = new StringBuilder("""
                select a.id, a.result_seq, a.status, a.evaluation_status, a.error_code, a.finished_at, a.change_proposal_id,
                       b.id as baseline_id, b.version_no, b.published_at
                  from published_change_analysis a
                  join baseline b on b.id = a.published_baseline_id and b.project_id = a.project_id
                 where a.project_id = :projectId
                   and a.result_seq > :after
                """);
        if (taskId != null) {
            sql.append(" and exists (select 1 from ").append(CANDIDATES_OF_A)
                    .append(" c where c.e ->> 'task_id' = :task) ");
        }
        sql.append(" order by a.result_seq limit :fetch ");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("projectId", projectId)
                .param("after", afterSeq)
                .param("fetch", fetch);
        if (taskId != null) {
            spec = spec.param("task", String.valueOf(taskId));
        }
        return spec.query((rs, n) -> new Row(rs.getLong("id"), rs.getLong("result_seq"), rs.getString("status"),
                rs.getString("evaluation_status"), rs.getString("error_code"), rs.getTimestamp("finished_at"),
                rs.getObject("change_proposal_id", Long.class), rs.getLong("baseline_id"), rs.getInt("version_no"),
                rs.getTimestamp("published_at"))).list();
    }

    /**
     * 페이지 안 결과들의 후보를 한 번에 읽는다 (결과마다 따로 조회하지 않는다). 순서: 결과 → 출처(규칙 먼저) → 원래 순서.
     * ->> 는 JSON 객체에서 칸 값을 글자로 꺼내는 Postgres 연산자다. 객체가 아닌 원소면 null 이다.
     * 미평가·실패 결과는 후보 칸이 비어 있어 줄이 없다.
     */
    private Map<Long, List<ImpactCandidateResponse>> findCandidates(List<Row> page) {
        Map<Long, List<ImpactCandidateResponse>> out = new HashMap<>();
        if (page.isEmpty()) {
            return out;
        }
        List<Long> ids = new ArrayList<>();
        for (Row row : page) {
            ids.add(row.id());
        }
        List<Map<String, Object>> rows = jdbc.sql("select a.id, c.origin, "
                        + "c.e ->> 'task_id' as task_id, c.e ->> 'design_item_id' as design_item_id, "
                        + "c.e ->> 'member_id' as member_id, c.e ->> 'validation_id' as validation_id, c.e ->> 'reason' as reason "
                        + "from published_change_analysis a cross join lateral " + CANDIDATES_OF_A + " c "
                        + "where a.id in (:ids) "
                        + "order by a.id, case when c.origin = 'RULE' then 0 else 1 end, c.ord")
                .param("ids", ids)
                .query()
                .listOfRows();
        for (Map<String, Object> r : rows) {
            Long id = ((Number) r.get("id")).longValue();
            String origin = (String) r.get("origin");
            String reason = null;
            if ("AI".equals(origin)) {
                reason = (String) r.get("reason");
            }
            if (!out.containsKey(id)) {
                out.put(id, new ArrayList<>());
            }
            out.get(id).add(new ImpactCandidateResponse(origin, (String) r.get("task_id"), (String) r.get("design_item_id"),
                    (String) r.get("member_id"), (String) r.get("validation_id"), reason));
        }
        return out;
    }
}
