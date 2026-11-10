package com.beez.backend.bee.service;

import com.beez.backend.ai.AiCallException;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.bee.dto.BeeAskRequest;
import com.beez.backend.bee.port.BeeChatGateway;
import com.beez.backend.bee.repository.BeeRepository;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.service.DocumentIndexWorker;
import com.beez.backend.task.service.TaskQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * BEE-A01 비비에게 질문 (S15P31A108-344). 순서가 곧 권한 보장이다.
 * <ol>
 *   <li>멤버·사람 세션 확인, 입력 검증(질문 1~2000자, 범위 APPROVED 만).</li>
 *   <li>허용 문서 집합은 서버가 현재 baseline 에서 정한다(사용자 입력 없음). 그 안에서만 의미 검색한다.</li>
 *   <li>DB 트랜잭션·락 밖에서 AI({@code /v1/bee/chat})를 부른다.</li>
 *   <li>AI 응답을 보낸 집합과 대조한다. 어긋나면 일부만 지우지 않고 통째로 거부한다(1회 재시도 뒤 템플릿 안내).</li>
 *   <li>권한·baseline 을 다시 확인하고, 통과한 뒤에만 answer_id 를 발급한다.</li>
 * </ol>
 * 대화 본문은 저장하지 않는다(Q13). Idempotency-Key 는 받아도 쓰지 않는다 — 응답을 다시 줄 수 없어 같은 요청은 새 답변이 된다.
 * 로그에는 질문·근거 원문을 쓰지 않는다.
 */
@Service
public class BeeQuestionService {

    private static final Logger log = LoggerFactory.getLogger(BeeQuestionService.class);

    static final String FEATURE = "BEE_CHAT";
    static final String MODE = "APPROVED";
    static final int MAX_QUESTION = 2000;
    /** AI 에 보내는 검색 결과 수. 모델에는 그중 기본 12건만 보인다(계약 max_evidence) */
    static final int EVIDENCE_LIMIT = 20;
    static final int MAX_APPROVED_ITEMS = 500;
    static final int MAX_EVIDENCE_TEXT = 20_000;
    static final int MAX_TITLE = 500;
    static final int MAX_CLAIM = 1000;
    /** 거부·502 일 때 새 request key 로 다시 부르는 횟수. 시간 초과는 다시 부르지 않는다 */
    static final int RETRIES = 1;
    /**
     * 질문 하나를 처리하는 전체 시간의 목표 상한 기본값(`beez.bee.answer-budget`). 검색 임베딩(최대 20초) + AI 호출(읽기 제한 35초)이
     * 이 안에 들고, 지금까지 걸린 시간에 AI 읽기 제한을 더해도 넘지 않을 때만 재시도한다.
     * Nginx 기본 읽기 대기(60초)보다 짧아야 브라우저가 먼저 끊지 않는다 — 서버 Nginx 는 건드리지 않는다.
     */
    static final Duration DEFAULT_ANSWER_BUDGET = Duration.ofSeconds(55);
    /** AI 호출을 시작하려면 최소로 남아 있어야 하는 시간 */
    static final Duration MIN_CALL = Duration.ofSeconds(10);
    static final String TEMPLATE_TEXT = "지금은 비비가 답을 만들지 못했어요. 질문과 가까운 문서를 아래에 보여 드려요. 잠시 뒤 다시 질문해 주세요.";
    static final String TEMPLATE_TEXT_NO_SOURCE = "지금은 비비가 답을 만들지 못했어요. 승인된 문서에서 질문과 가까운 내용도 찾지 못했어요. 잠시 뒤 다시 질문해 주세요.";

    private final ProjectAccessChecker access;
    private final DocumentIndexWorker search;
    private final BeeRepository repository;
    private final BeeChatGateway gateway;
    private final AiUsageRecorder usage;
    private final TaskQueryService tasks;
    private final ObjectMapper json;
    private final Duration chatReadTimeout;
    private final Duration answerBudget;
    private final MeterRegistry meters;

    public BeeQuestionService(ProjectAccessChecker access, DocumentIndexWorker search, BeeRepository repository,
                              BeeChatGateway gateway, AiUsageRecorder usage, TaskQueryService tasks, ObjectMapper json,
                              MeterRegistry meters,
                              @Value("${beez.bee.chat-read-timeout:35s}") Duration chatReadTimeout,
                              @Value("${beez.bee.answer-budget:55s}") Duration answerBudget) {
        this.meters = meters;
        this.answerBudget = answerBudget;
        this.chatReadTimeout = chatReadTimeout;
        this.access = access;
        this.search = search;
        this.repository = repository;
        this.gateway = gateway;
        this.usage = usage;
        this.tasks = tasks;
        this.json = json;
    }

    /** 한 번의 질문에서 단계별로 걸린 시간. dev 에서 실제 소요 시간을 재려고 로그·지표로 남긴다(질문·근거 원문은 담지 않는다) */
    private static final class Timing {
        long retrieveMillis;
        /** 검증을 통과한 모델 답변의 answer_status(ANSWERED·INSUFFICIENT_EVIDENCE·OUT_OF_SCOPE). 응답 본문에는 이 키가 없어서 여기 따로 담는다 */
        String answerStatus;
        int evidence;
        int approvedItems;
        final List<String> attempts = new ArrayList<>();
    }

    public Map<String, Object> ask(CallerContext caller, long projectId, BeeAskRequest body) {
        long started = System.nanoTime();
        Timing timing = new Timing();
        String outcome = "ERROR";
        try {
            Map<String, Object> response = doAsk(caller, projectId, body, timing, started);
            outcome = "TEMPLATE".equals(response.get("origin")) ? "TEMPLATE" : (timing.answerStatus != null ? timing.answerStatus : "UNKNOWN");
            return response;
        } catch (ApiException e) {
            outcome = "ERROR_" + e.getClass().getSimpleName();
            throw e;
        } finally {
            long total = elapsedMillis(started);
            time("beez.bee.ask", "outcome", outcome, total);
            // 한 줄 요약: 단계별 소요 시간. attempts 는 "결과:밀리초" 목록이다
            log.info("BEE-A01 timing project={} outcome={} total_ms={} retrieve_ms={} evidence={} approved_items={} attempts={}",
                    projectId, outcome, total, timing.retrieveMillis, timing.evidence, timing.approvedItems, timing.attempts);
        }
    }

    private Map<String, Object> doAsk(CallerContext caller, long projectId, BeeAskRequest body, Timing timing, long askStarted) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        String question = question(body);
        Long taskId = body.taskId() == null ? null : Ids.parse(body.taskId(), "task_id");

        // 관련 작업은 프로젝트 소속·접근을 같은 규칙으로 확인한다(다른 프로젝트·없는 작업은 404)
        List<Map<String, Object>> relatedTasks = new ArrayList<>();
        if (taskId != null) {
            relatedTasks.add(json.convertValue(tasks.getTask(caller, projectId, taskId), new TypeReference<Map<String, Object>>() { }));
        }

        long retrieveStarted = System.nanoTime();
        DocumentIndexWorker.Retrieval retrieval = retrieve(projectId, question);
        List<Map<String, Object>> approved = repository.approvedItems(projectId, retrieval.baselineId(), MAX_APPROVED_ITEMS);
        Sent sent = Sent.of(retrieval, approved, relatedTasks, search.embeddingProfile());
        timing.retrieveMillis = elapsedMillis(retrieveStarted);
        timing.evidence = sent.evidence().size();
        timing.approvedItems = approved.size();
        time("beez.bee.retrieve", "outcome", "ok", timing.retrieveMillis);

        String requestKey = "bee-ask-" + UUID.randomUUID();
        Map<String, Object> result = null;
        Map<String, Object> response = null;
        AiCallException failure = null;
        for (int attempt = 1; attempt <= 1 + RETRIES; attempt++) {
            // 다시 부르면 최악의 경우 AI 읽기 제한만큼 더 걸린다. 앞 단계(임베딩·첫 호출)가 오래 걸렸으면 전체 예산을 넘기지 않게
            // 재시도를 건너뛰고 템플릿으로 끝낸다. 예산은 Nginx 기본 읽기 대기(60초)보다 짧다.
            if (attempt > 1 && !retryFits(System.nanoTime() - askStarted, chatReadTimeout, answerBudget)) {
                log.warn("BEE-A01 retry skipped: time budget (project {})", projectId);
                timing.attempts.add("retry_skipped:0");
                break;
            }
            // 첫 호출도 남은 예산 안에서만 시작하고, 호출 자체도 남은 시간을 넘지 않게 읽기 제한을 줄인다.
            // 검색·DB 가 오래 걸렸으면 AI 를 부르지 않고 템플릿으로 끝낸다(프록시가 먼저 끊지 않게).
            Duration limit = callLimit(System.nanoTime() - askStarted, chatReadTimeout, answerBudget);
            if (limit.isZero()) {
                log.warn("BEE-A01 AI call skipped: time budget used up before the call (project {}, attempt {})", projectId, attempt);
                timing.attempts.add("budget_exhausted:0");
                failure = new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", BeeChatAiAdapter.UNAVAILABLE, null, false);
                break;
            }
            String key = requestKey + "-a" + attempt;
            long started = System.nanoTime();
            String label = "ok";
            try {
                response = gateway.chat(payload(projectId, key, question, retrieval, sent, approved, relatedTasks), limit);
                recordUsage(projectId, taskId, key, response, elapsedMillis(started));
                result = BeeAnswerValidator.validate(response, sent.validatorView(retrieval, search.embeddingProfile()));
                failure = null;
                break;
            } catch (AiCallException e) {
                label = e.getCode();
                recordFailedUsage(projectId, taskId, key, e, elapsedMillis(started));
                failure = e;
                if (!"ai_unavailable".equals(e.getCode()) || !e.retryable()) break;
            } catch (BeeAnswerValidator.Rejected e) {
                // 응답은 왔지만 허용 집합과 어긋남 — 사용량은 이미 기록했다. 사유 코드만 남기고 내용은 남기지 않는다.
                label = "rejected";
                log.warn("BEE-A01 answer rejected (project {}, attempt {}): {}", projectId, attempt, e.getMessage());
                failure = new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", BeeChatAiAdapter.UNAVAILABLE, null);
            } finally {
                long millis = elapsedMillis(started);
                timing.attempts.add(label + ":" + millis);
                time("beez.bee.chat", "result", String.valueOf(label), millis);
            }
        }
        if (result != null) timing.answerStatus = String.valueOf(result.get("answer_status"));
        if (failure != null && !"ai_unavailable".equals(failure.getCode())) throw failure;

        // 호출 중 권한이 바뀌었는지, baseline 이 교체되었는지 마지막으로 확인한다. 예전 승인본으로 대신 답하지 않는다.
        access.requireMember(caller, projectId);
        if (!repository.currentBaseline(projectId).map(id -> id == retrieval.baselineId()).orElse(false)) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_changed", "기준 설계가 변경되었습니다. 최신 기준으로 다시 질문해 주세요.");
        }
        return failure == null ? answer(actor, projectId, retrieval, sent, response, result) : template(actor, projectId, retrieval);
    }

    /** 소요 시간 지표. 분포(p95 등)를 볼 수 있게 히스토그램을 함께 낸다. 태그는 몇 가지 고정 값뿐이다 */
    private void time(String name, String tagKey, String tagValue, long millis) {
        Timer.builder(name).tag(tagKey, tagValue).publishPercentileHistogram().register(meters).record(Duration.ofMillis(millis));
    }

    /**
     * 이 AI 호출에 줄 읽기 제한: 남은 예산과 설정한 읽기 제한 중 짧은 쪽. 남은 시간이 {@link #MIN_CALL} 보다 적으면 0 이고,
     * 그때는 호출하지 않는다(모델이 답을 만들기에 너무 짧아 시간 초과만 나기 때문).
     */
    static Duration callLimit(long elapsedNanos, Duration chatReadTimeout, Duration budget) {
        Duration remaining = budget.minusNanos(elapsedNanos);
        if (remaining.compareTo(MIN_CALL) < 0) return Duration.ZERO;
        return remaining.compareTo(chatReadTimeout) < 0 ? remaining : chatReadTimeout;
    }

    /** 지금까지 걸린 시간에 AI 읽기 제한을 더해도 전체 예산 안이면 재시도할 수 있다 */
    static boolean retryFits(long elapsedNanos, Duration chatReadTimeout, Duration budget) {
        return elapsedNanos + chatReadTimeout.toNanos() <= budget.toNanos();
    }

    // ---- 입력 --------------------------------------------------------------------------------

    private static String question(BeeAskRequest body) {
        if (body == null || body.question() == null || body.question().isBlank()) throw invalid("question 은 필수입니다");
        String question = body.question().strip();
        if (question.length() > MAX_QUESTION) throw invalid("question 은 " + MAX_QUESTION + "자 이하여야 합니다");
        if (body.scope() != null && !"APPROVED".equals(body.scope())) {
            throw invalid("scope 는 APPROVED 만 지원합니다(초안 포함 검색은 아직 지원하지 않습니다)");
        }
        return question;
    }

    // ---- 검색 --------------------------------------------------------------------------------

    private DocumentIndexWorker.Retrieval retrieve(long projectId, String question) {
        try {
            return search.retrieveEvidence(projectId, question, EVIDENCE_LIMIT);
        } catch (ResourceAccessException e) {
            log.warn("BEE-A01 query embedding timed out or unreachable (project {})", projectId);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", BeeChatAiAdapter.UNAVAILABLE);
        } catch (RestClientResponseException e) {
            log.warn("BEE-A01 query embedding failed (project {}): status={}", projectId, e.getStatusCode().value());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", BeeChatAiAdapter.UNAVAILABLE);
        }
    }

    /** AI 에 보낸 집합. 응답 대조와 템플릿·변환에 같은 값을 쓴다 */
    record Sent(List<Map<String, Object>> evidence, Map<String, BeeAnswerValidator.SentEvidence> byId,
                Map<String, BeeAnswerValidator.SentSource> versionSources,
                Map<String, String> approvedVersions, Map<String, Map<String, Object>> approvedSources, Set<String> taskIds) {

        static Sent of(DocumentIndexWorker.Retrieval retrieval, List<Map<String, Object>> approved,
                       List<Map<String, Object>> tasks, String profile) {
            List<Map<String, Object>> evidence = new ArrayList<>();
            Map<String, BeeAnswerValidator.SentEvidence> byId = new LinkedHashMap<>();
            Set<String> chunks = new HashSet<>();
            Map<String, BeeAnswerValidator.SentSource> versionSources = new LinkedHashMap<>();
            for (DocumentIndexWorker.ChunkEvidence chunk : retrieval.evidence()) {
                // 계약: 같은 (문서 버전, chunk_key) 는 한 번만, 본문은 비어 있지 않고 20,000자 이하
                if (chunk.text().isBlank() || chunk.text().length() > MAX_EVIDENCE_TEXT
                        || !chunks.add(chunk.documentVersionId() + ":" + chunk.chunkKey())) continue;
                String id = "ev-" + chunk.chunkId();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("evidence_id", id);
                item.put("document_id", Ids.str(chunk.documentId()));
                item.put("document_version_id", Ids.str(chunk.documentVersionId()));
                item.put("version_no", chunk.versionNo());
                item.put("chunk_key", chunk.chunkKey());
                item.put("text", chunk.text());
                item.put("locator", chunk.locator());
                item.put("embedding_profile_id", profile);
                String title = chunk.title() == null ? "" : chunk.title().strip();
                if (!title.isEmpty()) item.put("title", title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE) : title);
                evidence.add(item);
                byId.put(id, new BeeAnswerValidator.SentEvidence(item.get("document_id").toString(),
                        item.get("document_version_id").toString(), chunk.versionNo(), chunk.chunkKey(), chunk.locator(), title,
                        chunk.text()));
                versionSources.putIfAbsent(item.get("document_version_id").toString(),
                        new BeeAnswerValidator.SentSource(item.get("document_id").toString(), chunk.versionNo()));
            }
            Map<String, String> approvedVersions = new LinkedHashMap<>();
            Map<String, Map<String, Object>> approvedSources = new LinkedHashMap<>();
            for (Map<String, Object> item : approved) {
                approvedVersions.put(item.get("id").toString(), item.get("design_item_id").toString());
                // 승인 항목의 저장 출처도 우리가 보낸 값이다. 근거 청크가 없는 문서 버전의 출처는 여기서 확인하고,
                // 응답의 sources 는 AI 가 준 값 대신 이 값으로 다시 만든다
                if (item.get("source") instanceof Map<?, ?> source && source.get("version_no") instanceof Number no) {
                    versionSources.putIfAbsent(String.valueOf(source.get("document_version_id")),
                            new BeeAnswerValidator.SentSource(String.valueOf(source.get("document_id")), no.intValue()));
                    Map<String, Object> copy = new LinkedHashMap<>();
                    copy.put("document_id", String.valueOf(source.get("document_id")));
                    copy.put("document_version_id", String.valueOf(source.get("document_version_id")));
                    copy.put("version_no", no.intValue());
                    copy.put("location", String.valueOf(source.get("location")));
                    approvedSources.put(item.get("id").toString(), copy);
                }
            }
            Set<String> taskIds = new LinkedHashSet<>();
            for (Map<String, Object> task : tasks) taskIds.add(task.get("id").toString());
            return new Sent(evidence, byId, versionSources, approvedVersions, approvedSources, taskIds);
        }

        BeeAnswerValidator.Sent validatorView(DocumentIndexWorker.Retrieval retrieval, String profile) {
            Set<String> versions = new LinkedHashSet<>();
            for (Long id : retrieval.documentVersionIds()) versions.add(Ids.str(id));
            return new BeeAnswerValidator.Sent(Ids.str(retrieval.baselineId()), profile, versions, byId, versionSources, approvedVersions, taskIds);
        }
    }

    // ---- AI 요청·응답 ------------------------------------------------------------------------

    private Map<String, Object> payload(long projectId, String requestKey, String question, DocumentIndexWorker.Retrieval retrieval,
                                        Sent sent, List<Map<String, Object>> approved, List<Map<String, Object>> relatedTasks) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("mode", MODE);
        scope.put("document_version_ids", retrieval.documentVersionIds().stream().map(Ids::str).toList());
        scope.put("baseline_id", Ids.str(retrieval.baselineId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_id", Ids.str(projectId));
        payload.put("request_key", requestKey);
        payload.put("question", question);
        payload.put("scope", scope);
        payload.put("embedding_profile_id", search.embeddingProfile());
        payload.put("retrieved_evidence", sent.evidence());
        payload.put("approved_items", approved);
        payload.put("related_tasks", relatedTasks);
        return payload;
    }

    /** 성공 응답의 모델 사용량. 사용량 줄이 하나도 없으면 호출은 일어났으므로 "미확인" 한 줄을 남긴다 */
    private void recordUsage(long projectId, Long taskId, String key, Map<String, Object> response, long elapsed) {
        int recorded = response == null ? 0 : usage.record(FEATURE, projectId, taskId, key, response, elapsed);
        if (recorded == 0) usage.recordFailure(FEATURE, projectId, taskId, key, elapsed);
    }

    /** 실패한 호출. AI 가 오류 본문에 실은 실제 사용량이 있으면 그것을, 없을 때만 토큰 수를 모르는 실패 한 줄을 남긴다 */
    private void recordFailedUsage(long projectId, Long taskId, String key, AiCallException error, long elapsed) {
        int recorded = 0;
        if (error.usage() != null) recorded = usage.recordLines(FEATURE, projectId, taskId, key, error.usage(), false, null, elapsed);
        if (recorded == 0) usage.recordFailure(FEATURE, projectId, taskId, key, elapsed);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    // ---- 응답 --------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> answer(ProjectActor actor, long projectId, DocumentIndexWorker.Retrieval retrieval, Sent sent,
                                       Map<String, Object> response, Map<String, Object> result) {
        List<Map<String, Object>> citations = new ArrayList<>();
        Set<Long> citedVersions = new LinkedHashSet<>();
        for (Object value : (List<?>) result.get("citations")) {
            Map<String, Object> citation = (Map<String, Object>) value;
            BeeAnswerValidator.SentEvidence known = sent.byId().get(citation.get("evidence_id").toString());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("n", citation.get("n"));
            out.put("document_id", known.documentId());
            out.put("document_version_id", known.documentVersionId());
            out.put("version_no", known.versionNo());
            // 식별·위치 값은 AI 가 돌려준 값이 아니라 BE 가 보낸 근거 값으로 채운다(검증기가 같음을 이미 확인했다).
            // 명세상 title 은 필수라 보낸 문서 제목을 쓴다. section_title 은 BE 가 보내지 않은 값이라 내보내지 않는다.
            out.put("title", known.title());
            out.put("locator", known.locator());
            // 인용문은 검증기가 보낸 청크 원문의 부분 문자열임을 확인했다. 앞뒤 공백만 다듬어 내보낸다.
            out.put("quote", ((String) citation.get("quote")).strip());
            if (citation.get("claim") instanceof String claim && !claim.isBlank()) {
                out.put("claim", claim.length() > MAX_CLAIM ? claim.substring(0, MAX_CLAIM) : claim);
            }
            citations.add(out);
            citedVersions.add(Long.parseLong(known.documentVersionId()));
        }
        List<Map<String, Object>> sources = sources(retrieval, sent, result);
        long answerId = repository.issueAnswer(projectId, actor.accountId(), MODE, List.copyOf(citedVersions), retrieval.baselineId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("origin", "MODEL");
        if (response.get("model") != null) out.put("model", response.get("model"));
        out.put("text", result.get("text"));
        out.put("sources", sources);
        out.put("unresolved", result.getOrDefault("unresolved", List.of()));
        if (response.get("usage") instanceof List<?> lines && !lines.isEmpty()) out.put("usage", lines);
        out.put("answer_id", Ids.str(answerId));
        out.put("citations", citations);
        out.put("grounded", result.get("grounded"));
        out.put("mode", MODE);
        out.put("baseline_id", Ids.str(retrieval.baselineId()));
        out.put("document_version_ids", retrieval.documentVersionIds().stream().map(Ids::str).toList());
        if (result.get("verification") != null) out.put("verification", result.get("verification"));
        if (result.get("notices") instanceof List<?> notices && !notices.isEmpty()) out.put("notices", notices);
        return out;
    }

    /**
     * 응답의 출처(sources)는 AI 가 준 목록이 아니라 보낸 값으로 다시 만든다: 인용한 근거마다 한 건(문서·버전·위치),
     * 이어서 참조한 승인 항목의 저장 출처 중 현재 범위 안의 문서 버전. AI 계약(#135)과 같은 규칙이며 중복은 합친다.
     */
    private static List<Map<String, Object>> sources(DocumentIndexWorker.Retrieval retrieval, Sent sent, Map<String, Object> result) {
        Set<String> inScope = new HashSet<>();
        for (Long id : retrieval.documentVersionIds()) inScope.add(Ids.str(id));
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Object value : (List<?>) result.get("citations")) {
            BeeAnswerValidator.SentEvidence known = sent.byId().get(((Map<?, ?>) value).get("evidence_id").toString());
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("document_id", known.documentId());
            source.put("document_version_id", known.documentVersionId());
            source.put("version_no", known.versionNo());
            source.put("location", location(known.title(), known.locator()));
            if (!sources.contains(source)) sources.add(source);
        }
        for (Object value : (List<?>) result.get("design_refs")) {
            Map<String, Object> source = sent.approvedSources().get(((Map<?, ?>) value).get("design_item_version_id").toString());
            if (source != null && inScope.contains(source.get("document_version_id")) && !sources.contains(source)) sources.add(source);
        }
        return sources;
    }

    /** 출처 위치 표기: "문서 제목 / L시작-L끝" (제목이 없으면 행 범위만). 템플릿 응답과 같은 형식이다 */
    static String location(String title, Map<String, Object> locator) {
        String span = "L" + locator.get("start_line") + "-L" + locator.get("end_line");
        return title == null || title.isBlank() ? span : title + " / " + span;
    }

    /**
     * 모델 답을 쓸 수 없을 때의 템플릿 안내(origin=TEMPLATE). 모델을 쓴 척하지 않는다: 인용·근거 판정이 없고 grounded=false,
     * 사용량 없음. 출처는 허용 집합에서 검색된 문서 위치만 보여 준다.
     */
    private Map<String, Object> template(ProjectActor actor, long projectId, DocumentIndexWorker.Retrieval retrieval) {
        List<Map<String, Object>> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DocumentIndexWorker.ChunkEvidence chunk : retrieval.evidence()) {
            String location = location(chunk.title(), chunk.locator());
            if (!seen.add(chunk.documentVersionId() + ":" + location)) continue;
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("document_id", Ids.str(chunk.documentId()));
            source.put("document_version_id", Ids.str(chunk.documentVersionId()));
            source.put("version_no", chunk.versionNo());
            source.put("location", location);
            sources.add(source);
            if (sources.size() >= 5) break;
        }
        long answerId = repository.issueAnswer(projectId, actor.accountId(), MODE, List.of(), retrieval.baselineId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("origin", "TEMPLATE");
        out.put("text", sources.isEmpty() ? TEMPLATE_TEXT_NO_SOURCE : TEMPLATE_TEXT);
        out.put("sources", sources);
        out.put("unresolved", List.of());
        out.put("answer_id", Ids.str(answerId));
        out.put("citations", List.of());
        out.put("grounded", false);
        out.put("mode", MODE);
        out.put("baseline_id", Ids.str(retrieval.baselineId()));
        out.put("document_version_ids", retrieval.documentVersionIds().stream().map(Ids::str).toList());
        return out;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }
}
