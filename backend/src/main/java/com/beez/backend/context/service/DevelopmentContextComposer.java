package com.beez.backend.context.service;

import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.context.dto.RelatedTaskDto;
import com.beez.backend.context.dto.SavePacketArgs;
import com.beez.backend.context.dto.SourceDto;
import com.beez.backend.document.service.DocumentIndexWorker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP-01 패킷 내용 구성 (S15P31A108-399). 현재 기준의 승인 설계·원문 근거·명시 의존·작업 계획 참조를 모아
 * AI 컨텍스트 선택(-131·-132, {@code POST /v1/development-context-selections})에 넘기고, 돌아온 패킷을 저장 입력으로 바꾼다.
 * 계약은 docs/design/development-context-packet-contract-S15P31A108-132.md 2~4절.
 *
 * <p>권한·인수 확인은 부르는 쪽(ContextMcpService)이 먼저 하고, 저장·재검증은 {@link ContextPacketService#save} 가 한다.
 * 여기서는 트랜잭션을 열지 않는다 — AI 호출 동안 DB 연결·잠금을 잡지 않는다. 기준이 사이에 바뀌면 save 가 409 로 막는다.
 *
 * <p>실패 구분(MCP-01 오류표): 색인 미준비·AI 실패·시간 초과·입력 상한 초과는 503 context_not_ready 로 끝내고 저장하지 않는다.
 * 검색을 마쳤는데 근거가 0건이면 AI 가 unresolved 에 NO_MATCH 를 넣은 정상 패킷이다.
 */
@Service
public class DevelopmentContextComposer {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentContextComposer.class);

    /** 질의와 가까운 원문 청크 후보 수. AI 가 이 안에서 CONTEXT_SELECTION_MAX_EVIDENCE 만큼 고른다 */
    static final int EVIDENCE_LIMIT = 20;
    /** AI 입력 상한(context_selection_api.py·context_selection.py). 넘으면 AI 가 400·413 으로 거부하므로 보내기 전에 막는다 */
    static final int MAX_CANDIDATES_PER_KIND = 200;
    static final int MAX_RELATIONS = 1000;
    static final int MAX_DOCUMENT_VERSIONS = 1000;
    static final int MAX_BODY_BYTES = 262_144;
    static final int MAX_CANDIDATE_TEXT = 100_000;
    static final int MAX_LOCATION = 1000;
    /** 사용량 표 feature. 선택 호출은 임베딩 호출이다. request_key 앞부분(dev-context-)으로 문서 색인과 구분한다 */
    static final String USAGE_FEATURE = "EMBEDDING";
    static final String NOT_READY = "context_not_ready";

    private final DocumentIndexWorker search;
    private final AiClient ai;
    private final AiUsageRecorder usage;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DevelopmentContextComposer(DocumentIndexWorker search, AiClient ai, AiUsageRecorder usage, JdbcClient jdbc,
                                      ObjectMapper json) {
        this.search = search;
        this.ai = ai;
        this.usage = usage;
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 구성 결과를 저장 입력으로 돌려준다. projectId·taskId·baselineId(지금 기준)는 부르는 쪽이 이미 확인했다.
     * 비용이 드는 질의 임베딩 전에 DB 만으로 알 수 있는 상한부터 본다.
     */
    public SavePacketArgs compose(long projectId, long baselineId, Long taskId, String request) {
        String project = Ids.str(projectId);
        String baseline = Ids.str(baselineId);

        List<Map<String, Object>> itemRows = approvedItems(projectId, baselineId);
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> itemMeta = new ArrayList<>();
        Map<String, String> versionByItem = new HashMap<>();
        for (Map<String, Object> row : itemRows) {
            String itemId = row.get("design_item_id").toString();
            String versionId = row.get("design_item_version_id").toString();
            versionByItem.put(itemId, versionId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("design_item_id", itemId);
            item.put("design_item_version_id", versionId);
            item.put("project_id", project);
            item.put("baseline_id", baseline);
            item.put("status", "APPROVED");
            item.put("text", candidateText(row));
            items.add(item);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("design_item_version_id", versionId);
            meta.put("design_item_id", itemId);
            meta.put("key", row.get("key"));
            meta.put("kind", row.get("kind"));
            meta.put("version_no", ((Number) row.get("version_no")).intValue());
            String description = description(row.get("content"));
            if (description != null) {
                meta.put("description", description);
            }
            itemMeta.add(meta);
        }

        List<Map<String, Object>> relations = relations(projectId, versionByItem);
        TaskSnapshot task = taskId == null ? null : task(taskId, versionByItem);
        if (items.size() > MAX_CANDIDATES_PER_KIND || relations.size() > MAX_RELATIONS) {
            log.warn("MCP-01 snapshot over AI limit project={} items={} relations={}", projectId, items.size(), relations.size());
            throw tooLarge();
        }

        DocumentIndexWorker.Retrieval retrieval = retrieve(projectId, request);
        if (retrieval.baselineId() != baselineId) {
            // 설계를 읽은 뒤 검색 전에 새 기준이 발행됐다. 섞인 스냅샷을 보내지 않는다
            throw baselineConflict();
        }
        if (retrieval.documentVersionIds().size() > MAX_DOCUMENT_VERSIONS) {
            log.warn("MCP-01 snapshot over AI limit project={} documents={}", projectId, retrieval.documentVersionIds().size());
            throw tooLarge();
        }

        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> evidenceMeta = new ArrayList<>();
        for (DocumentIndexWorker.ChunkEvidence chunk : retrieval.evidence()) {
            // AI 는 빈 후보·10만 자 초과 후보를 입력 오류로 거부한다. 그런 청크는 근거로 쓸 수 없어 뺀다
            if (chunk.text().isBlank() || chunk.text().length() > MAX_CANDIDATE_TEXT) {
                continue;
            }
            String evidenceId = Ids.str(chunk.chunkId());
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("evidence_id", evidenceId);
            e.put("document_version_id", Ids.str(chunk.documentVersionId()));
            e.put("project_id", project);
            e.put("baseline_id", baseline);
            e.put("text", chunk.text());
            evidence.add(e);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("evidence_id", evidenceId);
            meta.put("document_id", Ids.str(chunk.documentId()));
            meta.put("document_version_id", Ids.str(chunk.documentVersionId()));
            meta.put("version_no", chunk.versionNo());
            meta.put("location", location(chunk.locator()));
            evidenceMeta.add(meta);
        }

        String requestKey = "dev-context-" + UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_key", requestKey);
        body.put("project_id", project);
        body.put("baseline_id", baseline);
        body.put("request", request);
        body.put("task_id", Ids.str(taskId));
        body.put("document_version_ids", retrieval.documentVersionIds().stream().map(Ids::str).toList());
        body.put("items", items);
        body.put("evidence", evidence);
        body.put("relations", relations);
        body.put("task_design_refs", task == null ? List.of() : task.refs());
        // 검색을 실제로 마쳤을 때만 여기까지 온다. 미준비·실패는 retrieve 가 503 으로 끝낸다
        body.put("search_status", "COMPLETE");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("design_items", itemMeta);
        metadata.put("evidence", evidenceMeta);
        metadata.put("task", task == null ? null : task.meta());
        body.put("metadata", metadata);

        byte[] bytes = json.writeValueAsBytes(body);
        if (bytes.length > MAX_BODY_BYTES) {
            log.warn("MCP-01 selection body over AI limit project={} bytes={}", projectId, bytes.length);
            throw tooLarge();
        }

        Map<String, Object> response = select(projectId, taskId, requestKey, bytes);
        return toSaveArgs(projectId, taskId, baseline, request, response, versionByItem, evidenceMeta);
    }

    // ---- 근거 검색 ----

    private DocumentIndexWorker.Retrieval retrieve(long projectId, String request) {
        try {
            return search.retrieveEvidence(projectId, request, EVIDENCE_LIMIT);
        } catch (ApiException e) {
            if ("baseline_changed".equals(e.getCode())) {
                // 검색 도중 새 기준이 발행됐다. 저장 때의 기준 충돌과 같은 뜻이다 (MCP-01 409)
                throw baselineConflict();
            }
            log.warn("MCP-01 evidence search not ready project={} code={}", projectId, e.getCode());
            if ("index_not_ready".equals(e.getCode())) {
                // 재시도 여부는 도구 설명(GetDevelopmentContextTool)이 정한다. 메시지는 상태만 적는다
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_READY, "승인 문서 색인이 아직 준비되지 않았습니다.");
            }
            throw unavailable();
        } catch (RestClientException e) {
            log.warn("MCP-01 query embedding failed project={} error={}", projectId, e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    // ---- 스냅샷 ----

    /** 현재 기준의 승인 설계. 키 순으로 보내 같은 기준이면 같은 순서다 */
    private List<Map<String, Object>> approvedItems(long projectId, long baselineId) {
        return jdbc.sql("""
                        select b.design_item_id, b.design_item_version_id, di.key, di.kind, v.version_no, v.title,
                               v.content::text as content
                          from baseline_item b
                          join design_item di on di.id = b.design_item_id and di.project_id = b.project_id
                          join design_item_version v on v.id = b.design_item_version_id and v.design_item_id = di.id
                         where b.baseline_id = :b and b.project_id = :p and v.status = 'APPROVED'
                         order by di.key, di.id
                        """)
                .param("b", baselineId).param("p", projectId).query().listOfRows();
    }

    /** 의미 비교에 쓰는 후보 텍스트: 키·제목과 승인 본문 JSON. AI 상한은 코드 포인트라 서로게이트 쌍을 끊지 않게 자른다 */
    static String candidateText(Map<String, Object> row) {
        String text = row.get("key") + " " + Objects.toString(row.get("title"), "") + "\n" + row.get("content");
        if (text.codePointCount(0, text.length()) <= MAX_CANDIDATE_TEXT) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_CANDIDATE_TEXT));
    }

    /** content.description. POLICY·CONSTRAINT 는 이 값이 패킷 constraints 가 된다(-132 3절). 설계 저장 검증이 공백을 막는다 */
    private String description(Object content) {
        Object value = json.readValue(content.toString(), Map.class).get("description");
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** 근거 위치. 문서 검색 결과와 같은 표기(DocumentIndexWorker.chunkLocation)를 쓴다 */
    private static String location(Map<String, Object> locator) {
        String text = DocumentIndexWorker.chunkLocation(locator);
        return text.length() > MAX_LOCATION ? text.substring(0, MAX_LOCATION) : text;
    }

    /**
     * 팀장이 정한 명시 의존(EXPLICIT)의 현재 상태. 쌍마다 한 줄(PK)이다. 버전은 마지막 확정(CONFIRM·KEEP) 이력의 버전이고,
     * AI 는 그 버전이 지금 기준 버전과 다르면 의존을 펼치지 않고 DEPENDENCY_UNVERIFIED 로 표시한다.
     * 이력 버전이 없으면 두 항목의 현재 기준 버전을 쓰고, 그것도 없으면(기준 밖 항목) 보내지 않는다.
     * 시작 항목이 지금 기준에 없는 관계는 보내지 않는다 — AI 는 기준 항목에서만 의존을 펼치므로 쓰이지 않고, 상한(1,000)만 채운다.
     * 대상 항목이 기준 밖인 관계는 보낸다(AI 가 DEPENDENCY_UNVERIFIED 로 알린다).
     */
    private List<Map<String, Object>> relations(long projectId, Map<String, String> versionByItem) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        select d.from_item_id, d.to_item_id, d.origin, d.status, h.from_version_id, h.to_version_id
                          from design_item_dependency d
                          left join lateral (
                                select from_version_id, to_version_id from design_item_dependency_history h
                                 where h.project_id = d.project_id and h.from_item_id = d.from_item_id
                                   and h.to_item_id = d.to_item_id and h.action in ('CONFIRM','KEEP')
                                 order by h.id desc limit 1) h on true
                         where d.project_id = :p and d.origin = 'EXPLICIT' and d.status in ('CONFIRMED','NEEDS_RECHECK')
                         order by d.from_item_id, d.to_item_id
                        """)
                .param("p", projectId).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String from = row.get("from_item_id").toString();
            String to = row.get("to_item_id").toString();
            if (!versionByItem.containsKey(from)) {
                continue;
            }
            String fromVersion = row.get("from_version_id") != null ? row.get("from_version_id").toString() : versionByItem.get(from);
            String toVersion = row.get("to_version_id") != null ? row.get("to_version_id").toString() : versionByItem.get(to);
            if (fromVersion == null || toVersion == null) {
                continue;
            }
            Map<String, Object> relation = new LinkedHashMap<>();
            relation.put("from_design_item_id", from);
            relation.put("to_design_item_id", to);
            relation.put("from_design_item_version_id", fromVersion);
            relation.put("to_design_item_version_id", toVersion);
            relation.put("relation", "DEPENDS_ON");
            relation.put("origin", row.get("origin"));
            relation.put("status", row.get("status"));
            out.add(relation);
        }
        return out;
    }

    record TaskSnapshot(List<Map<String, Object>> refs, Map<String, Object> meta) {
    }

    /**
     * 작업의 현재 계획 참조와 상태. 참조는 지금 기준 버전과 같은 것만 보낸다 — AI 는 스냅샷 밖 버전 참조를 입력 오류로 거부하고,
     * 지난 버전 참조는 "현재 계획 참조"가 아니다(바뀐 설계는 MCP-03·12 가 알린다). 계획이 없거나 DRAFT 면 관련 작업으로 넣지 않는다(-132 2절).
     * 저장 때 save 가 상태·리비전을 다시 확인한다.
     */
    private TaskSnapshot task(long taskId, Map<String, String> versionByItem) {
        Map<String, Object> row = jdbc.sql("select status, current_plan_revision_id from task where id = :t")
                .param("t", taskId).query().singleRow();
        Object revision = row.get("current_plan_revision_id");
        if (revision == null) {
            return new TaskSnapshot(List.of(), null);
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Map<String, Object> ref : jdbc.sql("""
                        select design_item_id, design_item_version_id from task_plan_design_ref
                         where plan_revision_id = :r order by design_item_id
                        """).param("r", revision).query().listOfRows()) {
            String itemId = ref.get("design_item_id").toString();
            String versionId = ref.get("design_item_version_id").toString();
            if (versionId.equals(versionByItem.get(itemId))) {
                refs.add(Map.of("design_item_id", itemId, "design_item_version_id", versionId));
            }
        }
        String status = row.get("status").toString();
        Map<String, Object> meta = "DRAFT".equals(status) ? null
                : Map.of("task_id", Ids.str(taskId), "plan_revision_id", revision.toString(), "status", status);
        return new TaskSnapshot(refs, meta);
    }

    // ---- AI 호출 ----

    private Map<String, Object> select(long projectId, Long taskId, String requestKey, byte[] body) {
        long start = System.nanoTime();
        try {
            Map<String, Object> response = ai.developmentContextSelection(body);
            recordUsage(projectId, taskId, requestKey, response, start);
            return response;
        } catch (RestClientResponseException e) {
            Map<String, Object> error = errorBody(e);
            recordUsage(projectId, taskId, requestKey, error, start);
            if (e.getStatusCode().is4xxClientError()) {
                // 401 내부 토큰 불일치·400 스냅샷 계약 위반 등은 기다려도 풀리지 않는 설정·코드 결함이다. 일시 장애와 구분해 남긴다
                log.error("MCP-01 context selection rejected the request project={} status={} code={}", projectId,
                        e.getStatusCode().value(), errorCode(error));
            } else {
                log.warn("MCP-01 context selection failed project={} status={} code={}", projectId, e.getStatusCode().value(),
                        errorCode(error));
            }
            throw unavailable();
        } catch (RestClientException e) {
            // 시간 초과·연결 끊김·응답 해석 실패: 모델 호출이 났는지(비용) 알 수 없어 "미확인" 한 줄을 남긴다
            usage.recordFailure(USAGE_FEATURE, projectId, taskId, requestKey, elapsed(start));
            log.warn("MCP-01 context selection unreachable project={} error={}", projectId, e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private void recordUsage(long projectId, Long taskId, String requestKey, Map<String, Object> response, long start) {
        if (response != null && response.get("usage") instanceof List<?> lines) {
            usage.recordLines(USAGE_FEATURE, projectId, taskId, requestKey, lines, false, null, elapsed(start));
        }
    }

    /** 오류 본문(공통 봉투 + 공급자 호출이 있었으면 usage). JSON 이 아니면 빈 값 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> errorBody(RestClientResponseException e) {
        try {
            Map<String, Object> body = json.readValue(e.getResponseBodyAsByteArray(), Map.class);
            return body == null ? Map.of() : body;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static Object errorCode(Map<String, Object> body) {
        return body.get("error") instanceof Map<?, ?> error ? error.get("code") : null;
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    // ---- 응답 → 저장 입력 ----

    /**
     * AI packet → SavePacketArgs (-132 4절, to_save_packet_args 와 같은 매핑). 보낸 스냅샷 밖 ID 가 오면 AI 쪽 결함이라
     * 호출자 입력 오류(400)로 보이지 않게 503 으로 끝낸다. 기준 소속·작업 현재성은 save 가 다시 확인한다.
     */
    private SavePacketArgs toSaveArgs(long projectId, Long taskId, String baseline, String request,
                                      Map<String, Object> response, Map<String, String> versionByItem,
                                      List<Map<String, Object>> evidenceMeta) {
        try {
            Map<?, ?> packet = (Map<?, ?>) Objects.requireNonNull(response.get("packet"), "packet");
            require(Ids.str(projectId).equals(packet.get("project_id")), "project_id");
            require(baseline.equals(packet.get("baseline_id")), "baseline_id");
            require(Objects.equals(Ids.str(taskId), packet.get("task_id")), "task_id");
            require(request.equals(packet.get("request")), "request");
            Set<String> sentVersions = new HashSet<>(versionByItem.values());
            List<String> versions = strings(packet.get("design_item_version_ids"));
            require(sentVersions.containsAll(versions), "design_item_version_ids");
            Set<List<Object>> sentSources = new HashSet<>();
            for (Map<String, Object> e : evidenceMeta) {
                sentSources.add(List.of(e.get("document_id"), e.get("document_version_id"), e.get("version_no"), e.get("location")));
            }
            List<SourceDto> sources = new ArrayList<>();
            for (Object o : (List<?>) packet.get("sources")) {
                Map<?, ?> s = (Map<?, ?>) o;
                SourceDto source = new SourceDto((String) s.get("document_id"), (String) s.get("document_version_id"),
                        ((Number) s.get("version_no")).intValue(), (String) s.get("location"));
                require(sentSources.contains(List.of(source.documentId(), source.documentVersionId(), source.versionNo(),
                        source.location())), "sources");
                sources.add(source);
            }
            List<RelatedTaskDto> related = new ArrayList<>();
            for (Object o : (List<?>) packet.get("related_tasks")) {
                Map<?, ?> t = (Map<?, ?>) o;
                related.add(new RelatedTaskDto((String) t.get("task_id"), (String) t.get("plan_revision_id"),
                        (String) t.get("status")));
            }
            return new SavePacketArgs(request, Ids.str(projectId), Ids.str(taskId), baseline, versions,
                    strings(packet.get("constraints")), strings(packet.get("unresolved")), related, sources);
        } catch (ClassCastException | NullPointerException | IllegalStateException e) {
            log.warn("MCP-01 context selection returned an unusable packet project={} reason={}", projectId, e.getMessage());
            throw unavailable();
        }
    }

    private static void require(boolean ok, String field) {
        if (!ok) {
            throw new IllegalStateException("mismatch " + field);
        }
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) value) {
            out.add((String) Objects.requireNonNull(o));
        }
        return out;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_READY, "개발 컨텍스트 구성 AI 를 지금 쓸 수 없습니다.");
    }

    private static ApiException baselineConflict() {
        return new ApiException(HttpStatus.CONFLICT, "baseline_conflict", "기준 설계가 변경되었습니다. 최신 기준으로 다시 조회해 주세요.");
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_READY,
                "기준 설계·근거가 구성 상한을 넘어 지금은 개발 컨텍스트를 만들 수 없습니다.");
    }
}
