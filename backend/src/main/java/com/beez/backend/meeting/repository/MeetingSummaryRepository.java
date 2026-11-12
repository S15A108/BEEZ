package com.beez.backend.meeting.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * meeting_summary SQL 과 정리 입력용 읽기 (S15P31A108-235). 정리는 고치거나 지우지 않는다.
 * 권한과 업무 조건은 서비스가 본다.
 */
@Repository
public class MeetingSummaryRepository {

    /** result·generation 은 JSON 문자열 */
    public record SummaryRow(long id, long meetingId, long projectId, long entryId, long throughEntryId,
                             Long previousSummaryId, String feedback, String result, String generation, long createdBy,
                             Instant createdAt) {
    }

    /** SUMMARY 기록 응답에 싣는 값 */
    public record EntrySummaryMeta(long entryId, long summaryId, long throughEntryId, String generation) {
    }

    private static final String COLUMNS = "id, meeting_id, project_id, entry_id, through_entry_id, previous_summary_id,"
            + " feedback, result::text as result, generation::text as generation, created_by, created_at";

    private final JdbcClient jdbc;

    public MeetingSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long meetingId, long projectId, long entryId, long throughEntryId, Long previousSummaryId,
                       String feedback, String resultJson, String generationJson, long createdBy) {
        return jdbc.sql("""
                insert into meeting_summary (meeting_id, project_id, entry_id, through_entry_id, previous_summary_id,
                                             feedback, result, generation, created_by)
                values (:m, :p, :entry, :through, :previous, :feedback, cast(:result as jsonb), cast(:generation as jsonb), :by)
                returning id
                """).param("m", meetingId).param("p", projectId).param("entry", entryId).param("through", throughEntryId)
                .param("previous", previousSummaryId).param("feedback", feedback).param("result", resultJson)
                .param("generation", generationJson).param("by", createdBy).query(Long.class).single();
    }

    /** 회의의 최신 정리(가장 큰 id) */
    public Optional<SummaryRow> findLatest(long meetingId) {
        return jdbc.sql("select " + COLUMNS + " from meeting_summary where meeting_id = :m order by id desc limit 1")
                .param("m", meetingId).query(MeetingSummaryRepository::map).optional();
    }

    public Optional<SummaryRow> find(long meetingId, long summaryId) {
        return jdbc.sql("select " + COLUMNS + " from meeting_summary where id = :id and meeting_id = :m")
                .param("id", summaryId).param("m", meetingId).query(MeetingSummaryRepository::map).optional();
    }

    /** SUMMARY 기록마다 정리 ID, 정리에 쓴 마지막 기록과 생성 근거 */
    public Map<Long, EntrySummaryMeta> metaByEntry(Collection<Long> entryIds) {
        Map<Long, EntrySummaryMeta> out = new HashMap<>();
        if (entryIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select id, entry_id, through_entry_id, generation::text as generation from meeting_summary where entry_id in (:ids)")
                .param("ids", entryIds)
                .query((rs, n) -> out.put(rs.getLong("entry_id"), new EntrySummaryMeta(rs.getLong("entry_id"),
                        rs.getLong("id"), rs.getLong("through_entry_id"), rs.getString("generation")))).list();
        return out;
    }

    // ---- 정리 입력: 지금 기준의 승인 설계·문서 ----

    public Long currentBaseline(long projectId) {
        return jdbc.sql("select current_baseline_id from project where id = :p")
                .param("p", projectId).query(Long.class).optional().orElse(null);
    }

    /**
     * 지금 기준의 설계 항목(퇴역 제외)의 승인 버전. 항목 키 순서로 고정해 같은 입력이면 같은 목록이 나가게 한다.
     * 출처 문서가 있으면 document_id·문서 버전 번호도 함께 읽는다.
     */
    public List<Map<String, Object>> baselineItems(long baselineId, Collection<Long> designItemIds, int limit) {
        if (designItemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select v.id, i.id as design_item_id, i.key, i.kind, v.version_no, v.status, v.schema_version,
                       v.content::text as content, es.source_document_version_id, es.source_locator::text as source_locator,
                       es.source_status, dv.document_id as source_document_id, dv.version_no as source_version_no
                  from baseline_item bi
                  join design_item i on i.id = bi.design_item_id and i.project_id = bi.project_id
                  join design_item_version v on v.id = bi.design_item_version_id
                  join baseline_item_effective_source es on es.baseline_id = bi.baseline_id and es.design_item_id = bi.design_item_id
                  left join document_version dv on dv.id = es.source_document_version_id
                 where bi.baseline_id = :baseline and i.retired_at is null and i.id in (:ids)
                 order by i.key limit :limit
                """).param("baseline", baselineId).param("ids", designItemIds).param("limit", limit).query().listOfRows();
    }

    /** 지금 기준에 들어 있는 문서의 승인본(문서 ID 오름차순). 기준에 없는 문서는 나오지 않는다 */
    public List<Map<String, Object>> baselineDocuments(long baselineId, Collection<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select v.id, v.document_id, v.version_no, v.content_text
                  from baseline_document bd
                  join document_version v on v.id = bd.document_version_id and v.document_id = bd.document_id
                 where bd.baseline_id = :baseline and bd.document_id in (:ids)
                 order by v.document_id
                """).param("baseline", baselineId).param("ids", documentIds).query().listOfRows();
    }

    /** 지금 기준에서 그 문서의 승인본 버전 ID. 기준에 없으면 empty */
    public Optional<Long> baselineDocumentVersion(long projectId, long documentId) {
        return jdbc.sql("""
                select bd.document_version_id
                  from project p
                  join baseline_document bd on bd.baseline_id = p.current_baseline_id and bd.document_id = :doc
                 where p.id = :p
                """).param("p", projectId).param("doc", documentId).query(Long.class).optional();
    }

    private static SummaryRow map(ResultSet rs, int rowNum) throws SQLException {
        long previous = rs.getLong("previous_summary_id");
        Long previousId = rs.wasNull() ? null : previous;
        return new SummaryRow(rs.getLong("id"), rs.getLong("meeting_id"), rs.getLong("project_id"), rs.getLong("entry_id"),
                rs.getLong("through_entry_id"), previousId, rs.getString("feedback"), rs.getString("result"),
                rs.getString("generation"), rs.getLong("created_by"), rs.getTimestamp("created_at").toInstant());
    }
}
