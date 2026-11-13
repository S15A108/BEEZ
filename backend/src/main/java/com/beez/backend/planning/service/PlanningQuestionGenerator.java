package com.beez.backend.planning.service;

import static com.beez.backend.designitem.support.DesignJson.canonical;

import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.document.DocumentKindMapper;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.planning.port.AiFailure;
import com.beez.backend.planning.port.PlanningQuestionGateway;
import com.beez.backend.planning.repository.PlanningQuestionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 문서 분석(PLN-A01)이 끝난 문서 버전마다 요구사항 누락 후보·보완 질문(133)을 한 번 만들어 {@code planning_question} 에 저장한다.
 *
 * <p>순서(AI 호출 동안에는 트랜잭션·잠금을 잡지 않는다): ① 짧은 트랜잭션에서 차례인 버전을 잡고 실행 행을 RUNNING 으로, 같은 프로젝트의
 * 기존 질문(known_questions)도 이때 읽는다 → ② AI 호출 →
 * ③ 사용량 기록 → ④ 결과 검증 → ⑤ 짧은 트랜잭션에서 프로젝트 잠금 뒤 실행이 아직 유효한지(분석이 그대로이고 기준 설계가 없는지) 확인하고 저장.
 *
 * <p><b>실패해도 문서 분석 상태는 바꾸지 않는다.</b> 실패는 {@code planning_question_run} 에만 남고, 분석 재시도(PLN-A02)가 되돌려 다시 시도한다.
 * 재시도 가능한 실패는 FAILED 전에 자동으로 한 번 더 돈다. 모델이 답한 형식이 우연히 거절된 경우가 다시 돌리면 성공했고,
 * 그사이 이전 본문으로 만든 질문이 그대로 보였다(S15P31A108-396).
 * 같은 버전을 다시 분석하면 답변된·닫힌 질문은 그대로 두고 새 질문만 넣으며, 새 결과에 없는 미답변 질문은 SUPERSEDED 로 숨긴다(133 §8).
 */
@Service
public class PlanningQuestionGenerator {
    private static final Logger log = LoggerFactory.getLogger(PlanningQuestionGenerator.class);
    static final String FEATURE = "DOCUMENT_PARSE";
    static final int LEASE_SECONDS = 300;
    static final int MAX_QUESTIONS = 20;
    /** known_questions 상한(133 계약 §4.1). AI 스키마와 같은 값이다 — 넘으면 AI 가 요청 전체를 거절해 질문을 하나도 만들지 못한다. */
    static final int MAX_KNOWN_QUESTIONS = 100;
    static final int MAX_KNOWN_CHARS = 30_000;
    static final int KNOWN_QUESTION_CHARS = 2_000;
    static final int KNOWN_ANSWER_CHARS = 200;
    private static final Set<String> TYPES = Set.of("CHOICE", "SHORT_ANSWER", "ESSAY");
    private static final Set<String> CANDIDATE_KINDS = Set.of("MISSING_CANDIDATE", "CLARIFICATION", "CONFLICT");

    private final PlanningQuestionRepository questions;
    private final PlanningQuestionGateway gateway;
    private final AiUsageRecorder usage;
    private final DesignProjectRepository projects;
    private final TransactionTemplate tx;

    public PlanningQuestionGenerator(PlanningQuestionRepository questions, PlanningQuestionGateway gateway, AiUsageRecorder usage,
                                     DesignProjectRepository projects, PlatformTransactionManager manager) {
        this.questions = questions; this.gateway = gateway; this.usage = usage; this.projects = projects;
        this.tx = new TransactionTemplate(manager);
    }

    /** 차례인 버전 하나를 처리한다. 처리할 것이 없으면 false. */
    public boolean runOnce() {
        Map<String, Object> job = tx.execute(status -> {
            var next = questions.nextRunnable();
            if (next.isEmpty()) return null;
            var row = new HashMap<>(next.get());
            String key = "planning-questions-" + row.get("document_version_id") + "-a" + row.get("attempt") + "-" + UUID.randomUUID();
            questions.startRun(num(row, "document_version_id"), ((Number) row.get("attempt")).intValue(), (String) row.get("text_hash"), key, LEASE_SECONDS);
            row.put("run_key", key);
            // 실행을 잡은 트랜잭션에서 읽는다 — 조회가 실패하면 실행 시작도 되돌아가 다음 차례에 다시 잡힌다(가짜 AI 실패를 남기지 않는다).
            row.put("known_questions", known(questions.knownQuestions(num(row, "project_id"), num(row, "document_version_id"),
                    num(row, "document_id"), MAX_KNOWN_QUESTIONS)));
            return row;
        });
        if (job == null) return false;
        long version = num(job, "document_version_id"), project = num(job, "project_id");
        String runKey = (String) job.get("run_key"), textHash = (String) job.get("text_hash");
        boolean autoRetry = Boolean.TRUE.equals(job.get("auto_retry"));

        long started = System.nanoTime();
        Map<String, Object> response;
        try {
            response = gateway.generate(payload(job, runKey));
        } catch (AiFailure e) {
            recordFailedCall(project, runKey, e, elapsed(started));
            fail(version, runKey, e.getCode(), e.retryable(), autoRetry);
            return true;
        } catch (RuntimeException e) {
            log.error("planning-questions call failed unexpectedly: {}", e.toString(), e);
            usage.recordFailure(FEATURE, project, null, runKey, elapsed(started));
            fail(version, runKey, "ai_unavailable", true, autoRetry);
            return true;
        }
        int recorded = response == null ? 0 : record(project, runKey, response, elapsed(started));
        List<Map<String, Object>> parsed;
        try {
            parsed = parse(response, job);
        } catch (IllegalArgumentException e) {
            log.warn("planning-questions output unusable for version {}: {}", version, e.getMessage());
            if (recorded == 0) usage.recordFailure(FEATURE, project, null, runKey, elapsed(started));
            fail(version, runKey, "invalid_ai_output", true, autoRetry);
            return true;
        }
        tx.executeWithoutResult(status -> save(project, version, runKey, textHash, parsed));
        return true;
    }

    /** 재시도 가능한 첫 실패는 PENDING 으로 남겨 한 번 더 돌리고, 자동 재시도도 실패했거나 재시도할 수 없으면 FAILED 로 끝낸다. */
    private void fail(long version, String runKey, String code, boolean retryable, boolean autoRetry) {
        boolean again = retryable && !autoRetry;
        if (again) log.warn("planning-questions failed for version {} ({}); retrying once", version, code);
        tx.executeWithoutResult(status -> questions.finishRun(version, runKey, again ? "PENDING" : "FAILED", code, retryable));
    }

    private void save(long project, long version, String runKey, String textHash, List<Map<String, Object>> parsed) {
        // 호출 동안 문서가 다시 분석됐거나 기준 설계가 발행됐을 수 있다 — 프로젝트 잠금을 잡고 확인한다.
        if (!projects.lockActiveProject(project) || !questions.runStillCurrent(version, runKey, textHash)) return;
        if (questions.hasBaseline(project)) {
            questions.finishRun(version, runKey, "FAILED", "baseline_exists", false);
            return;
        }
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for (var row : questions.liveQuestions(project, version)) {
            if (row.get("question_key") != null) byKey.putIfAbsent((String) row.get("question_key"), row);
        }
        Set<String> seen = new HashSet<>();
        // 목록 자리는 만들어진 순서(id)로 고정된다 — 처음 만들 때는 우선순위(rank) 순으로 넣어 그 순서가 곧 우선순위가 되게 한다.
        var ordered = new ArrayList<>(parsed);
        ordered.sort(java.util.Comparator.comparingInt(row -> ((Number) row.get("rank")).intValue()));
        for (var q : ordered) {
            q = new HashMap<>(q);
            q.put("project", project); q.put("version", version); q.put("run", runKey);
            String key = (String) q.get("key");
            seen.add(key);
            var existing = byKey.get(key);
            if (existing == null) questions.insertQuestion(q);
            else if ("OPEN".equals(existing.get("status"))) questions.refreshOpenQuestion(((Number) existing.get("id")).longValue(), q);
            // 답변된·닫힌 질문은 키가 같아도 문구·형식을 덮어쓰지 않는다.
        }
        List<Long> stale = new ArrayList<>();
        for (var row : byKey.values()) {
            if ("OPEN".equals(row.get("status")) && !seen.contains((String) row.get("question_key"))) stale.add(((Number) row.get("id")).longValue());
        }
        questions.supersedeOpen(stale);
        questions.finishRun(version, runKey, "COMPLETED", null, false);
    }

    private Map<String, Object> payload(Map<String, Object> job, String runKey) {
        var document = new LinkedHashMap<String, Object>();
        document.put("document_id", job.get("document_id").toString());
        document.put("document_version_id", job.get("document_version_id").toString());
        document.put("version_no", ((Number) job.get("version_no")).intValue());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("project_id", job.get("project_id").toString());
        payload.put("request_key", runKey);
        payload.put("document", document);
        payload.put("kind", new DocumentKindMapper().convertToEntityAttribute(job.get("type").toString()).name());
        payload.put("media_type", job.get("storage_key").toString().endsWith(".md") ? "text/markdown" : "text/plain");
        payload.put("content", job.get("content_text"));
        payload.put("content_hash", AiHashes.wire(job.get("content_hash")));
        payload.put("text_hash", AiHashes.wire(job.get("text_hash")));
        payload.put("normalization_version", "utf8-lf-v1");
        payload.put("parser_profile", job.get("storage_key").toString().endsWith(".md") ? "markdown-v1" : "plain-v1");
        payload.put("mode", "MODEL");
        payload.put("max_questions", MAX_QUESTIONS);
        payload.put("known_questions", job.get("known_questions"));
        return payload;
    }

    /**
     * 같은 프로젝트에서 이미 묻거나 답하거나 닫은 질문(S15P31A108-414). 모델이 같은 결정을 다른 문서에서 다시 묻지 않게 넘긴다.
     * {@code rows} 는 최근 것부터다. 질문·답 요약의 글자 수(코드 포인트, AI 가 세는 단위) 합이 상한을 넘으면 거기서 끊어
     * 최근 것만 남긴다. 답은 ANSWERED 일 때만 앞 {@value #KNOWN_ANSWER_CHARS}자를 보낸다.
     */
    static List<Map<String, Object>> known(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        int chars = 0;
        for (var row : rows) {
            if (out.size() == MAX_KNOWN_QUESTIONS) break;
            String status = (String) row.get("status");
            String question = head((String) row.get("question"), KNOWN_QUESTION_CHARS);
            String answer = "ANSWERED".equals(status) && row.get("answer") instanceof String a && !a.isBlank()
                    ? head(a, KNOWN_ANSWER_CHARS) : null;
            if (question == null || question.isBlank()) continue;
            int size = question.codePointCount(0, question.length()) + (answer == null ? 0 : answer.codePointCount(0, answer.length()));
            if (chars + size > MAX_KNOWN_CHARS) break;
            chars += size;
            var item = new LinkedHashMap<String, Object>();
            item.put("question", question);
            item.put("status", status);
            item.put("answer", answer);
            out.add(item);
        }
        return out;
    }

    /** 앞 {@code max} 코드 포인트. 서로게이트 쌍을 가르지 않는다. */
    private static String head(String text, int max) {
        if (text == null || text.codePointCount(0, text.length()) <= max) return text;
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    /**
     * AI 응답을 검증해 저장할 질문 행으로 바꾼다. 요청한 문서 버전·텍스트 해시와 다르거나 구조가 틀리면 전체를 거절한다(일부만 저장하지 않는다).
     * 인용·후보 상태의 사실 검증은 AI 가 이미 했고, BE 는 저장 계약(키·유형·형식)이 맞는지만 본다.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parse(Map<String, Object> response, Map<String, Object> job) {
        if (response == null || !(response.get("result") instanceof Map<?, ?> raw)) throw new IllegalArgumentException("result 없음");
        var result = (Map<String, Object>) raw;
        String origin = response.get("origin") instanceof String o ? o : "";
        if (!origin.equals("MODEL") && !origin.equals("TEMPLATE")) throw new IllegalArgumentException("origin");
        if (!(result.get("document") instanceof Map<?, ?> doc)
                || !job.get("document_id").toString().equals(String.valueOf(doc.get("document_id")))
                || !job.get("document_version_id").toString().equals(String.valueOf(doc.get("document_version_id")))
                || !AiHashes.same(job.get("text_hash"), result.get("text_hash"))) {
            throw new IllegalArgumentException("다른 문서 버전의 결과");
        }
        if (!(result.get("candidates") instanceof List<?> candidateList) || !(result.get("questions") instanceof List<?> questionList)) {
            throw new IllegalArgumentException("candidates/questions 없음");
        }
        Map<String, Map<String, Object>> candidates = new HashMap<>();
        for (Object value : candidateList) {
            if (!(value instanceof Map<?, ?> c) || !(c.get("candidate_key") instanceof String key) || !CANDIDATE_KINDS.contains(c.get("kind"))) {
                throw new IllegalArgumentException("후보 형식");
            }
            candidates.put(key, (Map<String, Object>) c);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Object value : questionList) {
            if (!(value instanceof Map<?, ?> raw2)) throw new IllegalArgumentException("질문 형식");
            var q = (Map<String, Object>) raw2;
            String key = text(q.get("question_key")), text = text(q.get("text")), type = text(q.get("question_type"));
            var candidate = candidates.get(text(q.get("candidate_key")));
            if (candidate == null || !TYPES.contains(type) || !keys.add(key)) throw new IllegalArgumentException("질문 키/유형");
            if (!(q.get("answer_format") instanceof Map<?, ?> format) || !type.equals(format.get("type"))) throw new IllegalArgumentException("answer_format");
            String qOrigin = text(q.get("origin"));
            if (!qOrigin.equals("MODEL") && !qOrigin.equals("TEMPLATE")) throw new IllegalArgumentException("질문 origin");
            String basis = text(q.get("basis"));
            if (!basis.equals("REVIEW_CANDIDATE") && !basis.equals("UNVERIFIED")) throw new IllegalArgumentException("basis");
            if (!(q.get("rank") instanceof Number rank) || rank.intValue() < 1) throw new IllegalArgumentException("rank");
            var row = new HashMap<String, Object>();
            row.put("kind", candidate.get("kind"));
            row.put("question", text);
            row.put("origin", qOrigin.equals("MODEL") ? "model" : "template");
            row.put("key", key);
            row.put("type", type);
            row.put("format", canonical(format));
            row.put("basis", basis);
            row.put("summary", candidate.get("summary") instanceof String s ? s : null);
            row.put("signal", candidate.get("signal") instanceof String s ? s : null);
            row.put("reason", candidate.get("unverified_reason") instanceof String s ? s : null);
            row.put("evidence", canonical(candidate.get("evidence") instanceof List<?> list ? list : List.of()));
            row.put("rank", rank.intValue());
            rows.add(row);
        }
        return rows;
    }

    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("필수 문자열 없음");
        return s;
    }

    private int record(long project, String runKey, Map<String, Object> response, long elapsed) {
        if (!(response.get("usage") instanceof List<?> list)) return 0;
        return usage.recordLines(FEATURE, project, null, runKey, list, "TEMPLATE".equals(response.get("origin")), response.get("model"), elapsed);
    }

    /** 모델 호출 뒤 실패면 오류 본문의 실제 usage 를 남기고, 본문을 받지 못했거나 usage 가 없으면 "토큰 수 미확인" 한 줄을 남긴다. */
    private void recordFailedCall(long project, String runKey, AiFailure error, long elapsed) {
        int recorded = 0;
        if (error.usage() != null) recorded = usage.recordLines(FEATURE, project, null, runKey, error.usage(), false, null, elapsed);
        if (recorded == 0) usage.recordFailure(FEATURE, project, null, runKey, elapsed);
    }

    private static long num(Map<String, Object> row, String name) { return ((Number) row.get(name)).longValue(); }

    private static long elapsed(long startedNanos) { return (System.nanoTime() - startedNanos) / 1_000_000; }
}
