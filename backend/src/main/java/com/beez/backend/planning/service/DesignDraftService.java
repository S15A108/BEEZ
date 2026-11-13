package com.beez.backend.planning.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.canonical;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.invalid;
import static com.beez.backend.designitem.support.DesignJson.time;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.DesignItemRepository;
import com.beez.backend.document.DocumentKindMapper;
import com.beez.backend.designitem.service.DesignCommandService;
import com.beez.backend.designitem.validation.DesignContentValidator;
import com.beez.backend.planning.dto.DraftDtos.AdoptRequest;
import com.beez.backend.planning.dto.DraftDtos.CreateRequest;
import com.beez.backend.planning.repository.DesignDraftRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLN-A05 답변을 반영한 설계 초안 생성(접수·조회)과 생성 후보의 채택·폐기.
 *
 * <p>접수는 AI 를 부르지 않는다. 문서 버전마다 {@code design_draft_job} 을 입력 스냅샷과 함께 만들고 202 로 답하면
 * {@link DesignDraftRunner} 가 AI 를 부르고 결과를 <b>생성 후보</b>로 보존한다. 후보는 설계 항목을 전혀 바꾸지 않는다 —
 * 그래서 PLN-A07~A09 로 사람이 만든 초안은 재생성으로 덮이지 않는다. 팀장이 후보를 채택할 때만 DRAFT 가 만들어지거나 갱신되고,
 * 그때 후보를 만든 당시의 기준 본문·답변·문서가 지금과 같은지 다시 확인한다(134 계약 §6).
 */
@Service
public class DesignDraftService {
    static final int MAX_VERSIONS = 10;
    static final int MAX_JOBS = 20;
    static final int MAX_QUESTIONS = 200;
    static final int MAX_ITEMS = 500;
    /**
     * 다른 곳의 답변(related_answers) 상한(134 계약 §2·§5). 질문 생성의 known_questions(PlanningQuestionGenerator)와 같은 개수·글자 수다.
     * 글자 수는 질문과 구조 답(선택지·기타 입력·본문)의 코드 포인트 합으로, AI 가 세는 방식과 같다. 넘기면 AI 가 요청 전체를 거절한다.
     */
    static final int MAX_RELATED_ANSWERS = 100;
    static final int MAX_RELATED_CHARS = 30_000;
    /** AI 질문 문구 상한(Text 2,000자). 넘는 예전 질문은 보낼 수 없어 뺀다. */
    static final int RELATED_QUESTION_CHARS = 2_000;
    /** related_answers 참조 키 접두어. AI 가 만드는 질문 키(q-…)와 겹치지 않는다. */
    static final String RELATED_PREFIX = "pq-";

    private final ProjectAccessChecker access;
    private final LeaderGuard leader;
    private final DesignDraftRepository drafts;
    private final DesignItemRepository items;
    private final DesignCommandService commands;
    private final DesignContentValidator content;
    private final int partMaxChars;
    private final int maxParts;
    private final int maxAttempts;

    public DesignDraftService(ProjectAccessChecker access, LeaderGuard leader, DesignDraftRepository drafts, DesignItemRepository items,
                              DesignCommandService commands, DesignContentValidator content,
                              @org.springframework.beans.factory.annotation.Value("${beez.planning.part-max-chars:6000}") int partMaxChars,
                              @org.springframework.beans.factory.annotation.Value("${beez.planning.max-parts:12}") int maxParts,
                              @org.springframework.beans.factory.annotation.Value("${beez.planning.max-draft-attempts:3}") int maxAttempts) {
        this.access = access; this.leader = leader; this.drafts = drafts; this.items = items; this.commands = commands; this.content = content;
        this.partMaxChars = Math.max(500, partMaxChars); this.maxParts = Math.max(1, maxParts); this.maxAttempts = Math.max(1, maxAttempts);
    }

    // ---- PLN-A05 접수 ----

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(CallerContext caller, long project, CreateRequest input, String key) {
        if (input == null || input.documentVersionIds() == null || input.documentVersionIds().isEmpty()
                || input.documentVersionIds().size() > MAX_VERSIONS) {
            throw invalid("document_version_ids는 1~" + MAX_VERSIONS + "개여야 합니다.");
        }
        Set<Long> versions = new LinkedHashSet<>();
        for (String raw : input.documentVersionIds()) {
            long id;
            try { id = Long.parseLong(raw == null ? "" : raw.trim()); } catch (NumberFormatException e) { throw invalid("document_version_ids가 올바르지 않습니다."); }
            if (id < 1 || !versions.add(id)) throw invalid("document_version_ids에 중복이거나 잘못된 값이 있습니다.");
        }
        leader.lock(caller, project);
        var replay = commands.replay(project, caller.accountId(), "create-design-draft", key, input, Map.class);
        if (replay.isPresent()) return replay.get();
        if (drafts.hasBaseline(project)) throw conflict("baseline_exists", "이미 기준 설계가 발행되었습니다. 이후 변경은 변경 제안으로 요청해 주세요.");

        var jobs = new ArrayList<Map<String, Object>>();
        for (long version : versions) {
            var doc = drafts.documentVersion(project, version).orElseThrow(() -> AccessErrors.notFound("draft_document_version_missing"));
            if (!"COMPLETED".equals(doc.get("processing_status")) || !(doc.get("content_text") instanceof String text) || text.isBlank()) {
                throw conflict("analysis_not_completed", "문서 분석이 아직 끝나지 않았습니다. 분석 상태를 확인해 주세요.");
            }
            if (drafts.activeJobExists(version)) throw conflict("draft_in_progress", "이미 이 문서의 초안을 만들고 있습니다. 끝난 뒤 다시 요청해 주세요.");
            if (input.documentOnly()) {
                long job = requestDocumentOnly(project, doc, caller.accountId(), key);
                var entry = new LinkedHashMap<String, Object>();
                entry.put("id", Long.toString(job));
                entry.put("document_version_id", Long.toString(version));
                jobs.add(entry);
                continue;
            }
            var snapshot = snapshot(project, doc);
            long job = drafts.insertJob(project, version, caller.accountId(), key, snapshot, hash(snapshot));
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", Long.toString(job));
            entry.put("document_version_id", Long.toString(version));
            jobs.add(entry);
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "PENDING");
        response.put("document_version_ids", versions.stream().map(String::valueOf).toList());
        response.put("jobs", jobs);
        commands.save(project, caller.accountId(), "create-design-draft", key, input, response);
        return response;
    }

    /**
     * 팀장이 누른 "문서에서 다시 만들기"(S15P31A108-389). 같은 문서 버전에 문서 단독 작업이 없으면 새로 접수하고,
     * 실패한 작업이 있으면 실패한 부분만 다시 돌리게 대기시킨다(시도 횟수 상한). 이미 끝난 작업이면 다시 만들지 않는다.
     */
    private long requestDocumentOnly(long project, Map<String, Object> doc, long account, String key) {
        long version = ((Number) doc.get("document_version_id")).longValue();
        var last = drafts.latestDocumentOnlyJob(version);
        if (last.isEmpty()) return intakeDocumentOnly(project, doc, account, key, "MANUAL");
        var job = last.get();
        long id = ((Number) job.get("id")).longValue();
        if ("COMPLETED".equals(job.get("status"))) {
            throw conflict("draft_already_generated", "이 문서 버전은 이미 후보를 만들었습니다. 문서를 고쳐 새 버전을 승인하면 다시 만들 수 있습니다.");
        }
        if (((Number) job.get("attempt_no")).intValue() >= maxAttempts) {
            throw conflict("draft_retry_limit_exceeded", "다시 시도할 수 있는 횟수(" + maxAttempts + "회)를 다 썼습니다. 설계 항목을 직접 추가하거나 문서를 고쳐 새 버전을 승인해 주세요.");
        }
        drafts.retryFailedJob(id);
        return id;
    }

    /**
     * 문서 단독 작업 접수(자동·수동 공통). 호출한 쪽이 프로젝트 잠금, 기준 설계 없음, 분석 완료를 확인했다.
     * 입력 스냅샷에는 기존 항목·질문을 넣지 않고, 본문을 줄 범위 부분으로 나눠 부분 행을 만든다. AI 는 부르지 않는다.
     */
    long intakeDocumentOnly(long project, Map<String, Object> doc, long account, String key, String trigger) {
        // 문서 단독 모드는 기존 항목·질문을 쓰지 않으므로 읽지도 않는다(기존 항목이 많아 draft_too_large 로 접수가 막히지 않게).
        var snapshot = snapshot(project, doc, false);
        long version = ((Number) doc.get("document_version_id")).longValue();
        long job = drafts.insertDocumentOnlyJob(project, version, account, key, snapshot, hash(snapshot), trigger);
        int no = 1;
        for (var part : DraftPartPlanner.plan(doc.get("content_text").toString(), partMaxChars, maxParts)) drafts.insertPart(job, no++, part.start(), part.end());
        return job;
    }

    /** AI 에 보낼 입력(요청 키 제외)을 접수 시점의 값으로 고정한다. */
    private Map<String, Object> snapshot(long project, Map<String, Object> doc) {
        return snapshot(project, doc, true);
    }

    private Map<String, Object> snapshot(long project, Map<String, Object> doc, boolean withContext) {
        long version = ((Number) doc.get("document_version_id")).longValue();
        boolean markdown = doc.get("storage_key").toString().endsWith(".md");
        var documentRef = new LinkedHashMap<String, Object>();
        documentRef.put("document_id", doc.get("document_id").toString());
        documentRef.put("document_version_id", Long.toString(version));
        documentRef.put("version_no", ((Number) doc.get("version_no")).intValue());
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("project_id", Long.toString(project));
        snapshot.put("document", documentRef);
        snapshot.put("kind", new DocumentKindMapper().convertToEntityAttribute(doc.get("type").toString()).name());
        snapshot.put("media_type", markdown ? "text/markdown" : "text/plain");
        snapshot.put("content", doc.get("content_text"));
        snapshot.put("content_hash", doc.get("content_hash"));
        snapshot.put("text_hash", doc.get("text_hash"));
        snapshot.put("normalization_version", "utf8-lf-v1");
        snapshot.put("parser_profile", markdown ? "markdown-v1" : "plain-v1");
        if (!withContext) {
            snapshot.put("existing_items", List.of());
            snapshot.put("questions", List.of());
            return snapshot;
        }
        var existing = new ArrayList<Map<String, Object>>();
        for (var row : drafts.existingItems(project, version)) existing.add(itemView(row));
        if (existing.size() > MAX_ITEMS) throw conflict("draft_too_large", "기존 설계 항목이 너무 많아 한 번에 처리할 수 없습니다.");
        snapshot.put("existing_items", existing);
        var questions = new ArrayList<Map<String, Object>>();
        for (var row : drafts.questions(project, version)) {
            var view = questionView(row);
            if (view != null) questions.add(view);
        }
        if (questions.size() > MAX_QUESTIONS) throw conflict("draft_too_large", "질문이 너무 많아 한 번에 처리할 수 없습니다.");
        snapshot.put("questions", questions);
        snapshot.put("related_answers", related(drafts.relatedAnswers(project, version, MAX_RELATED_ANSWERS)));
        return snapshot;
    }

    /**
     * 다른 문서·이전 버전의 답변(S15P31A108-414). 질문 생성이 이 답들 때문에 같은 결정을 다시 묻지 않으므로 초안에도 원래 답과
     * 출처를 넘긴다. 최근 것부터 담다가 상한(개수·글자 수)에 닿으면 멈춘다.
     */
    static List<Map<String, Object>> related(List<Map<String, Object>> rows) {
        var out = new ArrayList<Map<String, Object>>();
        int chars = 0;
        for (var row : rows) {
            if (out.size() == MAX_RELATED_ANSWERS) break;
            String question = row.get("question") instanceof String q ? q : "";
            int questionChars = question.codePointCount(0, question.length());
            if (question.isBlank() || questionChars > RELATED_QUESTION_CHARS || row.get("answer_payload") == null) continue;
            Map<String, Object> answer = json(row.get("answer_payload"));
            int size = questionChars + PlanningAnswers.chars(answer);
            if (chars + size > MAX_RELATED_CHARS) break;
            chars += size;
            var source = new LinkedHashMap<String, Object>();
            source.put("document_id", row.get("document_id").toString());
            source.put("document_version_id", row.get("document_version_id").toString());
            source.put("version_no", ((Number) row.get("version_no")).intValue());
            var view = new LinkedHashMap<String, Object>();
            view.put("ref_key", RELATED_PREFIX + row.get("id"));
            view.put("question", question);
            view.put("answer", answer);
            view.put("source", source);
            out.add(view);
        }
        return out;
    }

    private static Map<String, Object> itemView(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("design_item_version_id", row.get("design_item_version_id").toString());
        view.put("design_item_id", row.get("design_item_id").toString());
        view.put("key", row.get("key"));
        view.put("kind", row.get("kind"));
        view.put("version_no", ((Number) row.get("version_no")).intValue());
        view.put("status", row.get("status"));
        view.put("schema_version", 1);
        view.put("content", json(row.get("content")));
        var source = new LinkedHashMap<String, Object>();
        source.put("document_id", row.get("source_document_id").toString());
        source.put("document_version_id", row.get("source_document_version_id").toString());
        source.put("version_no", ((Number) row.get("source_version_no")).intValue());
        source.put("location", location(row.get("source_locator")));
        view.put("source", source);
        return view;
    }

    /** 출처 위치 문자열: 저장된 location, 없으면 행 범위, 없으면 "원문 전체". */
    @SuppressWarnings("unchecked")
    static String location(Object locator) {
        if (locator == null) return "원문 전체";
        Map<String, Object> map = JSON.readValue(locator.toString(), Map.class);
        if (map.get("location") instanceof String s && !s.isBlank()) return s;
        if (map.get("start_line") instanceof Number a && map.get("end_line") instanceof Number b) return "L" + a.intValue() + "-L" + b.intValue();
        return "원문 전체";
    }

    /** 질문을 AI 입력(AnsweredQuestion) 모양으로. AI 가 요구하는 유형·키가 없는 예전 질문이나 구조 답변이 없는 답변은 보내지 않는다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> questionView(Map<String, Object> row) {
        if (row.get("question_type") == null || row.get("answer_format") == null) return null;
        String status = row.get("status").toString();
        if ("ANSWERED".equals(status) && row.get("answer_payload") == null) return null;
        String key = row.get("question_key").toString();
        var question = new LinkedHashMap<String, Object>();
        question.put("question_key", key);
        question.put("candidate_key", key.startsWith("q-") ? key.substring(2) : key);
        question.put("rank", row.get("rank") == null ? 1 : ((Number) row.get("rank")).intValue());
        question.put("question_type", row.get("question_type"));
        question.put("text", row.get("question"));
        question.put("answer_format", json(row.get("answer_format")));
        question.put("origin", "model".equals(row.get("origin")) ? "MODEL" : "TEMPLATE");
        question.put("basis", row.get("evidence_status") == null ? "REVIEW_CANDIDATE" : row.get("evidence_status"));
        var view = new LinkedHashMap<String, Object>();
        view.put("question", question);
        view.put("evidence", JSON.readValue(row.get("evidence").toString(), List.class));
        view.put("status", status);
        if ("ANSWERED".equals(status)) {
            var answer = new LinkedHashMap<String, Object>();
            answer.put("question_key", key);
            answer.put("answer", json(row.get("answer_payload")));
            if (row.get("answered_by") != null) answer.put("answered_by", row.get("answered_by").toString());
            if (row.get("answered_at") != null) answer.put("answered_at", time(row.get("answered_at")));
            view.put("answer", answer);
        }
        return view;
    }

    // ---- 조회 ----

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> list(CallerContext caller, long project, Long version) {
        access.requireMember(caller, project);
        var result = new ArrayList<Map<String, Object>>();
        for (var job : drafts.jobs(project, version, MAX_JOBS)) result.add(jobView(job));
        return Map.of("jobs", result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jobView(Map<String, Object> job) {
        var view = new LinkedHashMap<String, Object>();
        long id = ((Number) job.get("id")).longValue();
        view.put("id", Long.toString(id));
        view.put("document_version_id", job.get("document_version_id").toString());
        view.put("status", job.get("status"));
        if (job.get("error_code") != null) {
            view.put("error_code", job.get("error_code"));
            view.put("retryable", job.get("retryable"));
        }
        view.put("created_at", time(job.get("created_at")));
        view.put("updated_at", time(job.get("updated_at")));
        boolean documentOnly = "DOCUMENT_ONLY".equals(job.get("mode"));
        view.put("mode", job.get("mode"));
        view.put("trigger", job.get("trigger_kind"));
        view.put("attempt_no", job.get("attempt_no"));
        if (documentOnly) {
            view.put("max_attempts", maxAttempts);
            var parts = drafts.parts(id);
            var summary = new LinkedHashMap<String, Object>();
            summary.put("total", parts.size());
            summary.put("completed", parts.stream().filter(p -> "COMPLETED".equals(p.get("status"))).count());
            summary.put("failed", parts.stream().filter(p -> "FAILED".equals(p.get("status"))).count());
            summary.put("failed_error_codes", parts.stream().filter(p -> "FAILED".equals(p.get("status"))).map(p -> p.get("error_code")).distinct().toList());
            view.put("parts", summary);
        }
        if (job.get("result_meta") != null) {
            Map<String, Object> meta = JSON.readValue(job.get("result_meta").toString(), Map.class);
            for (String field : List.of("unresolved", "unanswered_question_keys", "untouched_existing_ids", "omitted_items", "origin")) {
                if (meta.get(field) != null) view.put(field, meta.get(field));
            }
        }
        var candidates = new ArrayList<Map<String, Object>>();
        // 문서 단독 작업은 부분마다 후보를 저장하므로 일부 부분이 실패해도 만들어진 후보는 보여 준다.
        if ("COMPLETED".equals(job.get("status")) || documentOnly) for (var c : drafts.candidates(id)) candidates.add(candidateView(c));
        view.put("candidates", candidates);
        return view;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> candidateView(Map<String, Object> c) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", c.get("id").toString());
        view.put("job_id", c.get("job_id").toString());
        view.put("candidate_key", c.get("candidate_key"));
        view.put("change", c.get("change_kind"));
        view.put("kind", c.get("kind"));
        view.put("status", c.get("status"));
        if (c.get("base_design_item_version_id") != null) {
            view.put("base_design_item_version_id", c.get("base_design_item_version_id").toString());
            view.put("base_status", c.get("base_status"));
            view.put("before", json(c.get("before_content")));
        }
        view.put("after", json(c.get("proposed_after")));
        view.put("changed_fields", JSON.readValue(c.get("changed_fields").toString(), List.class));
        view.put("conflicting_fields", JSON.readValue(c.get("conflicting_fields").toString(), List.class));
        view.put("sources", JSON.readValue(c.get("sources").toString(), List.class));
        view.put("answer_refs", JSON.readValue(c.get("answer_refs").toString(), List.class));
        view.put("support", c.get("support"));
        if (c.get("overlap_kind") != null) {
            view.put("overlap", Map.of("kind", c.get("overlap_kind"), "ref_id", c.get("overlap_ref").toString()));
        }
        if (c.get("rationale") != null) view.put("rationale", c.get("rationale"));
        if (c.get("adopted_item_version_id") != null) view.put("adopted_design_item_version_id", c.get("adopted_item_version_id").toString());
        return view;
    }

    // ---- 채택·폐기 ----

    /**
     * 후보를 설계 항목 DRAFT 로 반영한다. 같은 후보를 다시 채택하면 처음 결과를 그대로 돌려준다(후보 ID 기준 멱등).
     * 반영 직전에 후보를 만든 당시와 지금이 같은지 확인한다: 기준 설계가 아직 없는지, 원문(분석)이 그대로인지, 쓰인 답변이 그대로인지,
     * UPDATE 후보면 대상 DRAFT 의 본문이 후보의 {@code before} 와 같은지. 하나라도 다르면 이 후보만 409 candidate_stale 로 막는다.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> adopt(CallerContext caller, long project, long candidateId, AdoptRequest input) {
        leader.lock(caller, project);
        var c = drafts.candidateForUpdate(project, candidateId).orElseThrow(() -> AccessErrors.notFound("draft_candidate_missing"));
        if ("ADOPTED".equals(c.get("status"))) return adoptedView(c);
        if ("DISCARDED".equals(c.get("status"))) throw conflict("candidate_discarded", "이미 폐기한 후보입니다.");
        if (drafts.hasBaseline(project)) throw conflict("baseline_exists", "이미 기준 설계가 발행되었습니다. 이후 변경은 변경 제안으로 요청해 주세요.");

        if ("NONE".equals(c.get("support")) && !(input != null && Boolean.TRUE.equals(input.confirmNoEvidence()))) {
            throw invalid("확인할 수 있는 근거가 없는 후보입니다. 알고 채택하려면 confirm_no_evidence=true로 보내 주세요.");
        }
        List<String> conflicting = JSON.readValue(c.get("conflicting_fields").toString(), List.class);
        if (!conflicting.isEmpty()) {
            Set<String> resolved = input == null || input.resolvedFields() == null ? Set.of() : Set.copyOf(input.resolvedFields());
            if (!resolved.containsAll(conflicting)) throw invalid("충돌한 필드를 모두 확정해 주세요: " + String.join(", ", conflicting));
        }

        var job = drafts.job(((Number) c.get("job_id")).longValue()).orElseThrow();
        Map<String, Object> snapshot = JSON.readValue(job.get("input_snapshot").toString(), Map.class);
        long documentVersion = ((Number) job.get("document_version_id")).longValue();
        requireSourceUnchanged(project, documentVersion, snapshot, (List<String>) JSON.readValue(c.get("answer_refs").toString(), List.class));

        String kind = c.get("kind").toString();
        Map<String, Object> after = json(c.get("proposed_after"));
        // 문서에서 뽑은 후보는 그 문서에 더 새 버전이 승인되면 낡은 것이다 — 새 버전의 후보를 다시 만들게 한다.
        if ("DOCUMENT_ONLY".equals(job.get("mode")) && drafts.isSupersededVersion(documentVersion)) {
            throw stale();
        }
        // 문서끼리 같은 후보를 한꺼번에 채택해도 같은 설계 항목이 두 번 생기지 않게, 채택 직전에 지금 있는 항목과 지문을 다시 비교한다.
        // 지문이 같아도 내용이 다를 수 있어(예: 데이터 계약은 엔티티 이름만 비교) confirm_duplicate=true 로 알고 채택할 수 있다.
        if ("CREATE".equals(c.get("change_kind")) && c.get("fingerprint") != null
                && !(input != null && Boolean.TRUE.equals(input.confirmDuplicate()))
                && DraftOverlap.liveFingerprints(drafts, project).containsKey(c.get("fingerprint").toString())) {
            throw conflict("candidate_duplicate", "이미 같은 설계 항목이 있어요. 내용이 다르면 확인하고 채택하거나, 아니면 이 후보를 폐기해 주세요.");
        }
        String title = content.validate(kind, 1, after);
        String contentJson = canonical(after);
        long itemVersion;
        if ("CREATE".equals(c.get("change_kind"))) {
            String prefix = prefix(kind);
            long sequence = items.nextKeySequence(project, prefix);
            long item = items.insertItem(project, prefix + "-" + String.format(Locale.ROOT, "%03d", sequence), kind);
            itemVersion = items.insertDraft(item, title, contentJson, hash(after), documentVersion, locator(c), caller.accountId(), "AI_PROPOSED");
        } else {
            long base = ((Number) c.get("base_design_item_version_id")).longValue();
            var current = drafts.itemVersion(project, base).orElseThrow(() -> stale());
            if (!"DRAFT".equals(current.get("status")) || current.get("retired_at") != null
                    || !c.get("before_hash").equals(current.get("content_hash"))) {
                throw stale();
            }
            long item = ((Number) current.get("design_item_id")).longValue();
            if (items.updateDraft(item, base, title, contentJson, hash(after), c.get("before_hash").toString()) != 1) throw stale();
            itemVersion = base;
        }
        drafts.markAdopted(candidateId, itemVersion, caller.accountId());
        return adoptedView(drafts.candidateForUpdate(project, candidateId).orElseThrow());
    }

    @Transactional
    public Map<String, Object> discard(CallerContext caller, long project, long candidateId) {
        leader.lock(caller, project);
        var c = drafts.candidateForUpdate(project, candidateId).orElseThrow(() -> AccessErrors.notFound("draft_candidate_missing"));
        if ("ADOPTED".equals(c.get("status"))) throw conflict("candidate_adopted", "이미 채택한 후보는 폐기할 수 없습니다. 설계 항목을 직접 수정해 주세요.");
        if ("PENDING".equals(c.get("status"))) drafts.markDiscarded(candidateId, caller.accountId());
        return candidateView(drafts.candidateForUpdate(project, candidateId).orElseThrow());
    }

    private Map<String, Object> adoptedView(Map<String, Object> c) {
        var view = candidateView(c);
        long itemVersion = ((Number) c.get("adopted_item_version_id")).longValue();
        view.put("design_item_version_id", Long.toString(itemVersion));
        return view;
    }

    /** 후보를 만든 뒤 원문이 다시 분석됐거나 쓰인 답변이 바뀌었으면 후보는 더 이상 믿을 수 없다. */
    @SuppressWarnings("unchecked")
    private void requireSourceUnchanged(long project, long version, Map<String, Object> snapshot, List<String> answerRefs) {
        if (!drafts.processingTextHash(version).map(snapshot.get("text_hash")::equals).orElse(false)) throw stale();
        Map<String, Map<String, Object>> answered = new LinkedHashMap<>();
        for (Object value : (List<Object>) snapshot.get("questions")) {
            var q = (Map<String, Object>) value;
            if (q.get("answer") instanceof Map<?, ?> a) answered.put(((Map<String, Object>) q.get("question")).get("question_key").toString(), (Map<String, Object>) a);
        }
        for (String ref : answerRefs) {
            var then = answered.get(ref);
            if (then == null) continue;
            var now = drafts.currentAnswer(project, version, ref);
            if (now.isEmpty() || !"ANSWERED".equals(now.get().get("status")) || now.get().get("answer_payload") == null
                    || !canonical(then.get("answer")).equals(canonical(json(now.get().get("answer_payload"))))) {
                throw stale();
            }
        }
        // 다른 문서·이전 버전의 답(S15P31A108-414). 예전 작업의 스냅샷에는 이 필드가 없다.
        Map<String, Object> related = new LinkedHashMap<>();
        if (snapshot.get("related_answers") instanceof List<?> list) {
            for (Object value : list) {
                var r = (Map<String, Object>) value;
                related.put(r.get("ref_key").toString(), r.get("answer"));
            }
        }
        for (String ref : answerRefs) {
            if (!related.containsKey(ref)) continue;
            var now = drafts.currentAnswerById(project, Long.parseLong(ref.substring(RELATED_PREFIX.length())));
            if (now.isEmpty() || !"ANSWERED".equals(now.get().get("status")) || now.get().get("answer_payload") == null
                    || !canonical(related.get(ref)).equals(canonical(json(now.get().get("answer_payload"))))) {
                throw stale();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String locator(Map<String, Object> c) {
        List<Object> sources = JSON.readValue(c.get("sources").toString(), List.class);
        if (sources.isEmpty() || !(sources.get(0) instanceof Map<?, ?> first)) return null;
        var locator = new LinkedHashMap<String, Object>();
        if (first.get("locator") instanceof Map<?, ?> m) m.forEach((k, v) -> locator.put(k.toString(), v));
        if (first.get("location") instanceof String s && !s.isBlank()) locator.put("location", s);
        return locator.isEmpty() ? null : canonical(locator);
    }

    private static String prefix(String kind) {
        return switch (kind) {
            case "REQUIREMENT" -> "REQ";
            case "API_CONTRACT" -> "API";
            case "DATA_CONTRACT" -> "ENT";
            case "POLICY" -> "POL";
            default -> "CON";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Object value) { return JSON.readValue(value.toString(), Map.class); }

    private static ApiException stale() {
        return conflict("candidate_stale", "후보를 만든 뒤 문서, 답변 또는 설계 항목이 바뀌었어요. 초안을 다시 만들어 주세요.");
    }

    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
