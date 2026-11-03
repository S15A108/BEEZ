package com.beez.backend.changeproposal.service;

import static com.beez.backend.changeproposal.validation.ProposalValidator.requiredId;
import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.id;
import static com.beez.backend.designitem.support.DesignJson.invalid;
import static com.beez.backend.designitem.support.DesignJson.time;

import com.beez.backend.changeproposal.dto.DraftDtos.Draft;
import com.beez.backend.changeproposal.dto.DraftDtos.Generation;
import com.beez.backend.changeproposal.dto.DraftDtos.Request;
import com.beez.backend.changeproposal.port.ChangeDraftGateway;
import com.beez.backend.changeproposal.repository.DraftContextRepository;
import com.beez.backend.changeproposal.repository.ProposalRepository;
import com.beez.backend.changeproposal.validation.ProposalValidator;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.service.DesignCommandService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CHG-A07 문서 수정 초안 생성 (S15P31A108-233). 현재 기준의 승인된 문서를 AI-A06 에 보내 미승인 수정안을 받는다.
 *
 * <p><b>읽기·생성 전용</b>이다. 문서 버전·기준·제안을 만들거나 바꾸지 않고, 쓰는 것은 사용량({@code llm_usage})과 멱등 기록뿐이다.
 * 사람이 확인한 뒤 CHG-A01 로 따로 제출해야 제안이 된다.
 *
 * <p>순서(AI 호출 동안에는 트랜잭션·잠금을 잡지 않는다): ① 짧은 읽기 트랜잭션에서 입력 형식·권한·기준 확인과 멱등 재생 확인
 * → ② AI 호출 → ③ 사용량 기록(트랜잭션 밖) → ④ 결과 검증 → ⑤ 짧은 트랜잭션에서 프로젝트 잠금 뒤 다시 멱등 재생과
 * 기준 확인(호출 동안 다른 제안이 발행됐을 수 있다)을 하고 응답을 저장한다.
 */
@Service
public class ChangeDraftService {
    private static final Logger log = LoggerFactory.getLogger(ChangeDraftService.class);
    static final int MAX_ITEMS = 50;
    static final int MAX_INSTRUCTION = 2000;

    private final ProjectAccessChecker access;
    private final DesignProjectRepository projects;
    private final ProposalRepository proposals;
    private final DraftContextRepository context;
    private final ChangeDraftGateway gateway;
    private final DraftUsageRecorder usage;
    private final DesignCommandService idempotency;
    private final TransactionTemplate transaction;

    public ChangeDraftService(ProjectAccessChecker access, DesignProjectRepository projects, ProposalRepository proposals,
            DraftContextRepository context, ChangeDraftGateway gateway, DraftUsageRecorder usage, DesignCommandService idempotency,
            PlatformTransactionManager manager) {
        this.access = access; this.projects = projects; this.proposals = proposals; this.context = context;
        this.gateway = gateway; this.usage = usage; this.idempotency = idempotency;
        this.transaction = new TransactionTemplate(manager);
    }

    /** AI 에 보내기 전에 확인한 입력과, 호출 뒤 결과를 검증할 때 쓰는 값. */
    private record Prepared(long document, long version, Long task, Map<String, Object> documentVersion,
                            List<Map<String, Object>> items, int totalItems) { }
    private record Step(Draft replayed, Prepared prepared) { }

    public Draft generate(CallerContext caller, long project, Request input, String key) {
        validate(project, input);
        access.requireMember(caller, project);
        String operation = "change-draft:generate:" + (caller.isHuman() ? "human" : "agent:" + caller.agentTokenId());
        Step step = transaction.execute(status -> {
            var replay = idempotency.replay(project, caller.accountId(), operation, key, input, Draft.class);
            return replay.isPresent() ? new Step(replay.get(), null) : new Step(null, prepare(project, input));
        });
        if (step.replayed() != null) return step.replayed();
        Prepared prepared = step.prepared();

        // 호출마다 새 key: 같은 요청을 다시 보내도 실제로 일어난 AI 호출은 모두 사용량에 센다.
        String requestKey = "chg-a07:" + project + ":" + caller.accountId() + ":" + UUID.randomUUID();
        long started = System.nanoTime();
        Map<String, Object> response;
        try {
            response = gateway.draft(payload(project, requestKey, input.instruction().strip(), prepared));
        } catch (ApiException e) {
            usage.recordFailure(project, prepared.task(), requestKey, elapsedMillis(started));
            throw e;
        } catch (RuntimeException e) {
            log.error("AI-A06 call failed unexpectedly: {}", e.toString(), e);
            usage.recordFailure(project, prepared.task(), requestKey, elapsedMillis(started));
            throw unavailable();
        }
        long elapsed = elapsedMillis(started);
        int recorded = response == null ? 0 : usage.record(project, prepared.task(), requestKey, response, elapsed);
        Draft draft;
        try {
            draft = toDraft(response, prepared, input);
        } catch (ApiException e) {
            if (recorded == 0) usage.recordFailure(project, prepared.task(), requestKey, elapsed);
            throw e;
        }
        return transaction.execute(status -> {
            if (!projects.lockActiveProject(project)) throw AccessErrors.notFound("draft_project_missing");
            proposals.lockCaller(project, caller);
            access.requireMember(caller, project);
            var replay = idempotency.replay(project, caller.accountId(), operation, key, input, Draft.class);
            if (replay.isPresent()) return replay.get();
            requireStillCurrent(project, prepared);
            idempotency.save(project, caller.accountId(), operation, key, input, draft);
            return draft;
        });
    }

    private static void validate(long project, Request input) {
        if (project < 1) throw invalid("프로젝트 ID는 양수여야 합니다.");
        if (input == null) throw invalid("요청 본문이 필요합니다.");
        requiredId(input.documentId(), "document_id");
        requiredId(input.baseDocumentVersionId(), "base_document_version_id");
        if (input.taskId() != null) requiredId(input.taskId(), "task_id");
        if (input.instruction() == null || input.instruction().isBlank() || input.instruction().length() > MAX_INSTRUCTION)
            throw invalid("수정 요청은 1~" + MAX_INSTRUCTION + "자여야 합니다.");
    }

    /** 문서가 이 프로젝트의 것이고, 요청한 버전이 지금 기준의 그 문서 승인본인지 확인하고 AI 입력을 모은다. */
    private Prepared prepare(long project, Request input) {
        long document = requiredId(input.documentId(), "document_id");
        long version = requiredId(input.baseDocumentVersionId(), "base_document_version_id");
        Long task = input.taskId() == null ? null : requiredId(input.taskId(), "task_id");
        if (!context.projectActive(project)) throw AccessErrors.notFound("draft_project_missing");
        if (!context.documentInProject(project, document)) throw AccessErrors.notFound("draft_document_missing");
        if (task != null && !context.taskInProject(project, task)) throw AccessErrors.notFound("draft_task_missing");
        Long baseline = context.currentBaseline(project);
        if (baseline == null)
            throw new ApiException(HttpStatus.CONFLICT, "baseline_missing", "기준 v1이 만들어지기 전에는 문서 수정 초안을 만들 수 없어요.");
        var row = context.baselineVersion(baseline, document).orElseThrow(ChangeDraftService::versionConflict);
        if (id(row, "id") != version) throw versionConflict();
        if (!(row.get("content_text") instanceof String text) || text.isBlank())
            throw new ApiException(HttpStatus.CONFLICT, "analysis_not_completed", "문서 원문이 아직 준비되지 않았어요. 잠시 뒤 다시 시도해 주세요.");
        int total = context.relatedItemCount(baseline, version);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var item : context.relatedItems(baseline, version, MAX_ITEMS)) items.add(itemView(item, document, version, row));
        return new Prepared(document, version, task, documentVersionView(row, text), items, total);
    }

    /** 호출 동안 다른 제안이 발행돼 이 문서의 승인본이 바뀌었으면 결과를 버린다. 같은 승인본이 새 기준에 그대로 있으면 유효하다. */
    private void requireStillCurrent(long project, Prepared prepared) {
        Long baseline = context.currentBaseline(project);
        if (baseline == null) throw versionConflict();
        var row = context.baselineVersion(baseline, prepared.document()).orElseThrow(ChangeDraftService::versionConflict);
        if (id(row, "id") != prepared.version()) throw versionConflict();
    }

    private static Map<String, Object> payload(long project, String requestKey, String instruction, Prepared prepared) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("project_id", Long.toString(project));
        payload.put("request_key", requestKey);
        payload.put("instruction", instruction);
        payload.put("document_version", prepared.documentVersion());
        payload.put("approved_items", prepared.items());
        return payload;
    }

    /** AI 응답을 검증해 응답 모양으로 바꾼다. 쓸 수 없으면 502 ai_unavailable 이다(템플릿으로 대신하지 않는다). */
    private Draft toDraft(Map<String, Object> response, Prepared prepared, Request input) {
        if (response == null || !(response.get("result") instanceof Map<?, ?> result)) throw invalidOutput("result 가 없습니다");
        String after = result.get("after_content") instanceof String s ? s : null;
        try {
            ProposalValidator.documentText(after);   // 제안으로 제출할 수 있는 본문이어야 한다(CHG-A01 과 같은 규칙)
        } catch (ApiException e) {
            throw invalidOutput("after_content 가 제출 가능한 본문이 아닙니다");
        }
        if (!(result.get("reason") instanceof String reason) || reason.isBlank()) throw invalidOutput("reason 이 없습니다");
        String origin = response.get("origin") instanceof String o ? o : null;
        if (!"MODEL".equals(origin) && !"TEMPLATE".equals(origin)) throw invalidOutput("origin 이 MODEL/TEMPLATE 이 아닙니다");

        // 근거는 이 요청에 실제로 실은 문서 버전만 남긴다. 낯선 버전을 가리키는 근거를 그대로 보여 주면 사용자를 속인다.
        Set<String> allowed = Set.of(Long.toString(prepared.version()));
        List<Map<String, Object>> sources = new ArrayList<>();
        int rawSources = 0;
        if (result.get("sources") instanceof List<?> list) {
            for (Object value : list) {
                rawSources++;
                if (value instanceof Map<?, ?> m && m.get("document_version_id") != null
                        && allowed.contains(String.valueOf(m.get("document_version_id")))) {
                    @SuppressWarnings("unchecked") Map<String, Object> source = (Map<String, Object>) m;
                    sources.add(source);
                }
            }
        }
        if (rawSources > 0 && sources.isEmpty()) throw invalidOutput("근거가 모두 이 요청에 없는 문서 버전을 가리킵니다");
        List<String> unresolved = new ArrayList<>();
        if (result.get("unresolved") instanceof List<?> list) for (Object value : list) if (value instanceof String s) unresolved.add(s);
        if (rawSources > sources.size()) unresolved.add("근거로 적힌 문서 버전 " + (rawSources - sources.size()) + "건이 이 요청에 없어 제외했어요.");
        if (prepared.totalItems() > prepared.items().size())
            unresolved.add("관련 설계 항목 " + prepared.totalItems() + "개 중 " + prepared.items().size() + "개만 전달했어요.");
        List<Map<String, Object>> usageLines = null;
        if (response.get("usage") instanceof List<?> list) {
            usageLines = new ArrayList<>();
            for (Object value : list) {
                if (value instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked") Map<String, Object> line = (Map<String, Object>) m;
                    usageLines.add(line);
                }
            }
        }
        String model = response.get("model") instanceof String m && !m.isBlank() ? m : null;
        return new Draft(input.documentId(), input.baseDocumentVersionId(), after,
                new Generation(origin, model, reason, sources, unresolved, usageLines));
    }

    private static Map<String, Object> documentVersionView(Map<String, Object> row, String text) {
        String extension = String.valueOf(row.get("storage_key")).endsWith(".md") ? "md" : "txt";
        var view = new LinkedHashMap<String, Object>();
        view.put("id", row.get("id").toString());
        view.put("document_id", row.get("document_id").toString());
        view.put("version_no", row.get("version_no"));
        view.put("status", row.get("status"));
        view.put("file_name", "document-" + row.get("document_id") + "-v" + row.get("version_no") + "." + extension);
        view.put("media_type", extension.equals("md") ? "text/markdown" : "text/plain");
        view.put("content_hash", row.get("content_hash"));
        view.put("content", text);
        if (row.get("created_by") != null) view.put("created_by", row.get("created_by").toString());
        view.put("is_current_baseline", true);
        view.put("created_at", time(row.get("created_at")));
        return view;
    }

    private static Map<String, Object> itemView(Map<String, Object> item, long document, long version, Map<String, Object> versionRow) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", item.get("id").toString());
        view.put("design_item_id", item.get("design_item_id").toString());
        view.put("key", item.get("key"));
        view.put("kind", item.get("kind"));
        view.put("version_no", item.get("version_no"));
        view.put("status", item.get("status"));
        view.put("schema_version", item.get("schema_version"));
        view.put("content", JSON.readValue(item.get("content").toString(), Object.class));
        var source = new LinkedHashMap<String, Object>();
        source.put("document_id", Long.toString(document));
        source.put("document_version_id", Long.toString(version));
        source.put("version_no", versionRow.get("version_no"));
        source.put("location", location(item.get("source_locator")));
        view.put("source", source);
        return view;
    }

    /** 출처 위치를 사람이 읽는 문자열로. 행 범위가 있으면 "L3-L8", 없으면 "원문 전체". */
    private static String location(Object locator) {
        if (locator == null) return "원문 전체";
        try {
            var node = JSON.readTree(locator.toString());
            if (node.hasNonNull("start_line") && node.hasNonNull("end_line"))
                return "L" + node.get("start_line").asInt() + "-L" + node.get("end_line").asInt();
        } catch (RuntimeException e) {
            log.debug("source_locator not readable: {}", e.getMessage());
        }
        return "원문 전체";
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static ApiException versionConflict() {
        return new ApiException(HttpStatus.CONFLICT, "version_conflict", "기준이 바뀌었어요. 최신 승인본을 확인한 뒤 다시 요청해 주세요.");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", AiChangeDraftAdapter.UNAVAILABLE);
    }

    private static ApiException invalidOutput(String reason) {
        log.warn("AI-A06 output unusable: {}", reason);
        return unavailable();
    }
}
