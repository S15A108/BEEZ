package com.beez.backend.context.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.context.dto.DesignItemVersionResponse;
import com.beez.backend.context.dto.VersionRefDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 컨텍스트 서비스가 다른 영역 표(task·task_plan_revision·design_item·baseline)를 읽는 조회 모음, 그리고 *_ref 표 쓰기.
 * 다른 영역 표는 엔티티 없이 JdbcClient 로 필요한 칸만 읽는다 (AGENTS.md backend 규칙).
 * *_ref 표에는 project_id 칸이 없어 DB 묶음 FK 를 걸 수 없으므로, 설계 항목의 프로젝트 소속은 여기서 확인한다.
 */
@Component
public class ContextLookup {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ContextLookup(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 이 프로젝트 작업이 아니면 404 (상세 대상 — -172 합의 3) */
    public void requireTaskInProject(Long projectId, Long taskId) {
        Integer count = jdbc.sql("select count(*) from task where id = :t and project_id = :p")
                .param("t", taskId).param("p", projectId).query(Integer.class).single();
        if (count == 0) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
    }

    /** 계획 리비전이 그 작업 것인지. 아니면 400 (DDL FK (plan_revision_id, task_id) 와 같은 조건) */
    public void requirePlanRevisionOfTask(Long taskId, Long planRevisionId) {
        Integer count = jdbc.sql("select count(*) from task_plan_revision where id = :r and task_id = :t")
                .param("r", planRevisionId).param("t", taskId).query(Integer.class).single();
        if (count == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "plan_revision_id 가 task_id 의 계획이 아닙니다: " + planRevisionId);
        }
    }

    /**
     * 패킷 저장용: 프로젝트 행을 공유 잠금(FOR SHARE)으로 잡고 현재 기준을 읽는다 (리뷰 반영).
     * 발행(-194)은 같은 행을 FOR UPDATE 로 잠그므로, 패킷 저장이 끝날 때까지 새 발행이 끼어들지 못하고
     * 발행 중이면 끝날 때까지 기다린다. 그래서 패킷의 기준 ID 와 설계 버전 상태가 같은 시점 것이 된다.
     */
    public Optional<Long> lockCurrentBaselineId(Long projectId) {
        return jdbc.sql("select current_baseline_id from project where id = :p and current_baseline_id is not null for share")
                .param("p", projectId).query(Long.class).optional();
    }

    public Optional<Long> currentBaselineId(Long projectId) {
        return jdbc.sql("select current_baseline_id from project where id = :p and current_baseline_id is not null")
                .param("p", projectId).query(Long.class).optional();
    }

    /**
     * 기록의 참조 버전 검사: 각 버전이 그 항목의 버전이고, 항목이 이 프로젝트 것인지. 같은 항목을 두 번 넣으면 400 (PK).
     * 승인 여부는 보지 않는다 — "당시 참조 버전"을 그대로 남기는 기록이다.
     *
     * @return 항목 번호 → 버전 번호 (입력 순서 유지)
     */
    public Map<Long, Long> requireVersionRefs(Long projectId, List<VersionRefDto> refs) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (VersionRefDto ref : refs) {
            Long itemId = Ids.parse(ref.designItemId(), "design_refs.design_item_id");
            Long versionId = Ids.parse(ref.designItemVersionId(), "design_refs.design_item_version_id");
            if (out.containsKey(itemId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "같은 설계 항목이 두 번 있습니다: " + itemId);
            }
            out.put(itemId, versionId);
        }
        if (out.isEmpty()) {
            return out;
        }
        // 참조는 몇 개뿐이라 하나씩 확인한다 (배열을 IN 목록으로 펼치는 바인딩 동작에 기대지 않으려고)
        for (Map.Entry<Long, Long> e : out.entrySet()) {
            Integer found = jdbc.sql("""
                            select count(*)
                              from design_item_version v
                              join design_item di on di.id = v.design_item_id
                             where v.id = :v and v.design_item_id = :i and di.project_id = :p
                            """)
                    .param("v", e.getValue()).param("i", e.getKey()).param("p", projectId)
                    .query(Integer.class).single();
            if (found == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "design_refs 에 이 프로젝트의 항목 버전이 아닌 것이 있습니다: " + e.getKey() + "/" + e.getValue());
            }
        }
        return out;
    }

    /** 패킷의 승인 설계: 모든 버전이 현재 기준 설계(baseline_item)에 들어 있어야 한다 (MCP-01 "현재 승인 설계"). 아니면 400 */
    public void requireVersionsInBaseline(Long projectId, Long baselineId, List<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return;
        }
        Set<Long> distinct = new HashSet<>(versionIds);
        if (distinct.size() != versionIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "design_item_version_ids 에 중복이 있습니다");
        }
        Integer found = jdbc.sql("""
                        select count(*) from baseline_item
                         where baseline_id = :b and project_id = :p and design_item_version_id in (:v)
                        """)
                .param("b", baselineId).param("p", projectId).param("v", versionIds).query(Integer.class).single();
        if (found != versionIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "현재 기준 설계에 없는 항목 버전이 있습니다");
        }
    }

    public void insertRecordRefs(Long recordId, Map<Long, Long> versionByItem) {
        for (Map.Entry<Long, Long> e : versionByItem.entrySet()) {
            jdbc.sql("insert into context_record_ref (record_id, design_item_id, design_item_version_id) values (:r, :i, :v)")
                    .param("r", recordId).param("i", e.getKey()).param("v", e.getValue()).update();
        }
    }

    public void insertPacketRefs(Long packetId, List<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        insert into dev_context_packet_ref (packet_id, design_item_id, design_item_version_id)
                        select :k, v.design_item_id, v.id from design_item_version v where v.id in (:v)
                        """)
                .param("k", packetId).param("v", versionIds).update();
    }

    public List<VersionRefDto> recordRefs(Long recordId) {
        return jdbc.sql("""
                        select design_item_id, design_item_version_id from context_record_ref
                         where record_id = :r order by design_item_id
                        """)
                .param("r", recordId)
                .query((rs, n) -> new VersionRefDto(Ids.str(rs.getLong(1)), Ids.str(rs.getLong(2))))
                .list();
    }

    /**
     * 보는 사람에게 <b>보이는</b> 후속 판이 있는 기록 번호들 (= 최신이 아님).
     * 남의 PRIVATE 후속 판은 세지 않는다 — 세면 "볼 수 없는 판이 있다"는 사실이 드러난다 (MCP-05 "타인의 PRIVATE 기록은
     * 요약·건수에도 드러내지 않는다", 리뷰 반영).
     */
    public Set<Long> superseded(List<Long> recordIds, Long viewerAccountId) {
        Set<Long> out = new HashSet<>();
        if (recordIds.isEmpty()) {
            return out;
        }
        out.addAll(jdbc.sql("""
                        select previous_id from context_record
                         where previous_id in (:ids)
                           and (visibility = 'SHARED' or author_account_id = :me)
                        """)
                .param("ids", recordIds).param("me", viewerAccountId).query(Long.class).list());
        return out;
    }

    /**
     * 참조 설계가 그 기준 설계에서도 현재인가: 기록이 참조한 모든 (항목, 버전)이 baselineId 의 버전과 같으면 현재.
     * 참조가 없으면 현재. 기준이 없는데(null) 참조가 있으면 현재가 아니다.
     * 기준을 호출자가 한 번 읽어 넘긴다 — 같은 응답·조회 이력이 서로 다른 기준을 보지 않게 (리뷰 반영).
     */
    public Set<Long> notCurrent(Long baselineId, List<Long> recordIds) {
        Set<Long> out = new HashSet<>();
        if (recordIds.isEmpty()) {
            return out;
        }
        out.addAll(jdbc.sql("""
                        select distinct r.record_id
                          from context_record_ref r
                          left join baseline_item bi on bi.baseline_id = cast(:b as bigint)
                                                    and bi.design_item_id = r.design_item_id
                         where r.record_id in (:ids)
                           and (bi.design_item_version_id is null or bi.design_item_version_id <> r.design_item_version_id)
                        """)
                .param("b", baselineId).param("ids", recordIds).query(Long.class).list());
        return out;
    }

    /**
     * 패킷 근거(Source) 확인: 그 문서 버전이 패킷의 기준 설계(baseline_document)에 들어 있는 버전이어야 한다. 아니면 400.
     * 같은 프로젝트라도 미승인 버전이나 지난 기준의 버전이 "현재 승인 근거"처럼 불변 패킷에 남지 않게 한다 (리뷰 반영).
     * baseline_document 는 기준 설계마다 문서당 승인 버전 하나를 적어 둔 표다. 버전 번호도 그 버전의 실제 번호와 같아야 한다.
     */
    public void requireSourceInBaseline(Long projectId, Long baselineId, Long documentId, Long documentVersionId,
                                        Integer versionNo) {
        Integer found = jdbc.sql("""
                        select count(*)
                          from baseline_document b
                          join document_version v on v.id = b.document_version_id and v.document_id = b.document_id
                         where b.baseline_id = :b and b.project_id = :p
                           and b.document_id = :d and b.document_version_id = :v and v.version_no = :n
                        """)
                .param("b", baselineId).param("p", projectId).param("d", documentId).param("v", documentVersionId)
                .param("n", versionNo)
                .query(Integer.class).single();
        if (found == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "sources 에 현재 승인 기준의 문서 버전이 아닌 것이 있습니다: " + documentId + "/" + documentVersionId);
        }
    }

    /**
     * 패킷 관련 작업(RelatedTask) 확인: 작업이 이 프로젝트 것이고(아니면 404), 넘어온 계획 리비전·상태가 그 작업의
     * <b>지금</b> 계획 리비전·상태와 같아야 한다(아니면 400). 지난 계획이나 바뀐 상태가 현재 관련 작업처럼 저장되지 않게 한다.
     * 작업 행을 공유 잠금(FOR SHARE)으로 읽어, 패킷 저장이 끝날 때까지 그 작업의 상태·계획이 바뀌지 않게 한다.
     */
    public void requireRelatedTaskCurrent(Long projectId, Long taskId, Long planRevisionId, String status) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        select status, current_plan_revision_id from task where id = :t and project_id = :p for share
                        """)
                .param("t", taskId).param("p", projectId).query().listOfRows();
        if (rows.isEmpty()) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
        Object currentStatus = rows.get(0).get("status");
        Object currentRevision = rows.get(0).get("current_plan_revision_id");
        boolean sameRevision = currentRevision != null && ((Number) currentRevision).longValue() == planRevisionId;
        if (!status.equals(currentStatus) || !sameRevision) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "related_tasks 의 계획 리비전·상태가 작업의 현재 값과 다릅니다: task " + taskId);
        }
    }

    /** 버전 번호 목록 → 명세 DesignItemVersion (입력 순서 유지) */
    public List<DesignItemVersionResponse> designItemVersions(List<Long> versionIds) {
        List<DesignItemVersionResponse> out = new ArrayList<>();
        if (versionIds.isEmpty()) {
            return out;
        }
        Map<Long, DesignItemVersionResponse> byId = new HashMap<>();
        jdbc.sql("""
                        select v.id, v.design_item_id, di.key, di.kind, v.version_no, v.status, v.schema_version,
                               v.content::text as content
                          from design_item_version v join design_item di on di.id = v.design_item_id
                         where v.id in (:v)
                        """)
                .param("v", versionIds)
                .query((rs, n) -> new DesignItemVersionResponse(Ids.str(rs.getLong("id")), Ids.str(rs.getLong("design_item_id")),
                        rs.getString("key"), rs.getString("kind"), rs.getInt("version_no"), rs.getString("status"),
                        rs.getInt("schema_version"), parseObject(rs.getString("content"))))
                .list()
                .forEach(d -> byId.put(Long.parseLong(d.id()), d));
        for (Long id : versionIds) {
            if (byId.containsKey(id)) {
                out.add(byId.get(id));
            }
        }
        return out;
    }

    /** JSON 문자열 → Map. ObjectMapper(Jackson) 의 readValue 가 문자열을 Map 으로 바꾼다 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(String text) {
        return json.readValue(text, Map.class);
    }
}
