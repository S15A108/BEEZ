package com.beez.backend.bee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beez.backend.ai.AiCallException;
import com.beez.backend.ai.AiClient;
import com.beez.backend.bee.port.BeeChatGateway;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BEE-A01 비비에게 질문 (S15P31A108-344). 실제 DB(pgvector)와 API 를 쓰고, AI 는 대역이다:
 * 임베딩은 질의 벡터만 돌려주는 가짜, 답변 생성은 {@link BeeChatGateway} 대역. 따라서 이 시험이 통과해도
 * 실제 AI 서버·모델 품질은 확인한 것이 아니다.
 */
@TestPropertySource(properties = {"beez.document.worker-enabled=false", "beez.document.embedding-profile=test-profile",
        "beez.document.embedding-model=test-model"})
class BeeQuestionIntegrationTest extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ORIGIN = "http://localhost:5173";
    static final String PROFILE = "test-profile";

    @Autowired
    JdbcTemplate db;
    @MockitoBean
    AiClient ai;
    @MockitoBean
    BeeChatGateway gateway;

    AccessFixtures.ProjectWithLeader project;
    ApiClient leader;
    ApiClient member;
    long memberAccount;
    int typeCursor;

    @BeforeEach
    void setUp() {
        reset(ai, gateway);
        project = fixtures().projectWithLeader();
        leader = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        memberAccount = fixtures().account(email);
        fixtures().member(project.projectId(), memberAccount, ProjectRole.MEMBER);
        member = client().login(email, AccessFixtures.PASSWORD);
        when(ai.documentEmbeddings(anyMap())).thenAnswer(invocation -> queryEmbedding(invocation.getArgument(0)));
    }

    // ---- 대역 --------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> queryEmbedding(Map<String, Object> input) {
        var vector = new ArrayList<Double>(Collections.nCopies(1024, 0.0));
        vector.set(0, 1.0);
        var texts = (List<Map<String, Object>>) input.get("texts");
        return Map.of("origin", "MODEL", "model", "test-model",
                "usage", List.of(Map.of("request_key", input.get("request_key") + "-provider-1", "feature", "EMBEDDING",
                        "model", "test-model", "input_tokens", 3, "output_tokens", 0, "latency_ms", 1, "tokens_known", true,
                        "failed", false, "estimated_cost_amount", "0.000001", "currency", "USD")),
                "result", Map.of("model", "test-model", "dimension", 1024, "embedding_profile_id", PROFILE,
                        "purpose", input.get("purpose"),
                        "vectors", texts.stream().map(t -> Map.of("key", t.get("key"), "embedding", vector)).toList()));
    }

    /** 요청의 첫 근거를 그대로 인용하는 정상 답변. mutate 로 한 군데씩 어긋나게 만든다 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> modelAnswer(Map<String, Object> request, Consumer<Map<String, Object>> mutate) {
        var evidence = (Map<String, Object>) ((List<?>) request.get("retrieved_evidence")).get(0);
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("n", 1);
        citation.put("evidence_id", evidence.get("evidence_id"));
        citation.put("document_id", evidence.get("document_id"));
        citation.put("document_version_id", evidence.get("document_version_id"));
        citation.put("version_no", evidence.get("version_no"));
        citation.put("chunk_key", evidence.get("chunk_key"));
        citation.put("locator", evidence.get("locator"));
        citation.put("quote", evidence.get("text"));
        citation.put("claim", "예약 충돌 방지 규칙");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer_status", "ANSWERED");
        result.put("grounded", true);
        result.put("text", "같은 시간대 예약은 거절해서 충돌을 막습니다 [1].");
        result.put("citations", new ArrayList<>(List.of(citation)));
        result.put("sources", new ArrayList<>(List.of(Map.of("document_id", evidence.get("document_id"),
                "document_version_id", evidence.get("document_version_id"), "version_no", evidence.get("version_no"),
                "location", "예약 명세 / L1-L1"))));
        result.put("design_refs", new ArrayList<>());
        result.put("task_refs", new ArrayList<>());
        result.put("unresolved", new ArrayList<>());
        result.put("next_actions", new ArrayList<>());
        result.put("notices", new ArrayList<>());
        result.put("scope", request.get("scope"));
        result.put("embedding_profile_id", request.get("embedding_profile_id"));
        result.put("verification", Map.of("level", "ENTAILMENT", "claims", 1, "model", "m"));
        mutate.accept(result);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("origin", "MODEL");
        response.put("model", "m");
        response.put("usage", List.of(usageLine("llm-bee-chat-"), usageLine("llm-bee-verify-")));
        return response;
    }

    static Map<String, Object> usageLine(String prefix) {
        return Map.of("request_key", prefix + UUID.randomUUID(), "feature", "BEE_CHAT", "model", "m", "latency_ms", 5,
                "failed", false, "tokens_known", true, "input_tokens", 100, "output_tokens", 20);
    }

    void gatewayAnswers(Consumer<Map<String, Object>> mutate) {
        when(gateway.chat(anyMap())).thenAnswer(invocation -> modelAnswer(invocation.getArgument(0), mutate));
    }

    // ---- 데이터 ------------------------------------------------------------------------------

    private String type() {
        return new String[] {"PLAN", "ERD", "REQ_SPEC", "API_SPEC"}[typeCursor++ % 4];
    }

    /** 문서 한 건 + 색인이 끝난 버전 + 청크 하나. 반환은 {document, version}. 벡터는 질의와 같은 방향(거리 0)에 둔다 */
    private long[] indexedDocument(long projectId, long accountId, String title, String chunkText) {
        long document = db.queryForObject("insert into document(project_id, type, title, created_by) values (?, ?, ?, ?) returning id",
                Long.class, projectId, type(), title, accountId);
        long version = db.queryForObject("""
                insert into document_version(document_id, version_no, status, storage_key, content_hash, content_text, created_by)
                values (?, 1, 'DRAFT', 'k', 'h' || ?, ?, ?) returning id
                """, Long.class, document, document, chunkText, accountId);
        db.update("""
                insert into document_chunk(document_version_id, chunk_no, chunk_key, text, locator, source_spans, context_refs, embedding)
                values (?, 0, ?, ?, '{"type":"text_lines","start_line":1,"end_line":1}'::jsonb,
                        '[{"block_id":"b","start_offset":0,"end_offset":1}]'::jsonb, '[]'::jsonb,
                        (ARRAY[1.0]::double precision[] || array_fill(0.0::double precision, ARRAY[1023]))::vector)
                """, version, "chk-" + version, chunkText);
        db.update("""
                insert into document_processing(document_version_id, attempt, status, source_status, index_status, text_hash)
                values (?, 1, 'COMPLETED', 'READY', 'READY', 'th')
                """, version);
        db.update("""
                insert into document_index_manifest(document_version_id, attempt, text_hash, embedding_profile_id,
                                                    chunking_profile_id, parser_version, chunk_count)
                values (?, 1, 'th', ?, 'c', 'p', 1)
                """, version, PROFILE);
        return new long[] {document, version};
    }

    /** 이 프로젝트의 현재 baseline 으로 만들고 문서를 담는다. 반환은 baseline ID */
    private long publish(long projectId, long accountId, int versionNo, long[]... documents) {
        long baseline = db.queryForObject("insert into baseline(project_id, version_no, published_by) values (?, ?, ?) returning id",
                Long.class, projectId, versionNo, accountId);
        for (long[] doc : documents) {
            db.update("insert into baseline_document(baseline_id, project_id, document_id, document_version_id) values (?, ?, ?, ?)",
                    baseline, projectId, doc[0], doc[1]);
        }
        db.update("update project set current_baseline_id = ? where id = ?", baseline, projectId);
        return baseline;
    }

    private long answerCount(long projectId) {
        return db.queryForObject("select count(*) from bee_answer where project_id = ?", Long.class, projectId);
    }

    private HttpResponse<String> ask(ApiClient who, long projectId, String json) {
        return who.post("/api/projects/" + projectId + "/bee/questions", json, "Origin", ORIGIN);
    }

    private JsonNode ok(HttpResponse<String> res) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    private static String error(HttpResponse<String> res) {
        return JSON.readTree(res.body()).path("error").path("code").asText();
    }

    private static final String QUESTION = "{\"question\":\"예약 충돌은 어떻게 막아?\"}";

    // ---- 정상 --------------------------------------------------------------------------------

    @Test
    void ask_answersFromApprovedDocuments_andIssuesAnswerIdThatFeedbackAccepts() {
        long[] doc = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        long baseline = publish(project.projectId(), project.leaderAccountId(), 1, doc);
        gatewayAnswers(r -> { });

        JsonNode body = ok(ask(member, project.projectId(), QUESTION));

        assertThat(body.get("origin").asText()).isEqualTo("MODEL");
        assertThat(body.get("text").asText()).contains("[1]");
        assertThat(body.get("grounded").asBoolean()).isTrue();
        assertThat(body.get("mode").asText()).isEqualTo("APPROVED");
        assertThat(body.get("baseline_id").asText()).isEqualTo(String.valueOf(baseline));
        assertThat(body.get("verification").get("level").asText()).isEqualTo("ENTAILMENT");
        JsonNode citation = body.get("citations").get(0);
        assertThat(citation.get("n").asInt()).isEqualTo(1);
        assertThat(citation.get("document_version_id").asText()).isEqualTo(String.valueOf(doc[1]));
        assertThat(citation.get("title").asText()).isEqualTo("예약 명세");   // 명세상 필수 — AI 가 안 줘도 BE 가 채운다
        assertThat(citation.get("locator").get("start_line").asInt()).isEqualTo(1);
        assertThat(citation.get("quote").asText()).isEqualTo("같은 시간대 예약은 거절한다");
        assertThat(body.get("sources").get(0).get("document_version_id").asText()).isEqualTo(String.valueOf(doc[1]));
        assertThat(body.get("usage").size()).isEqualTo(2);

        // answer_id 는 질문한 본인 것으로 발급되고, 같은 id 로 A03 피드백이 받아들여진다 (A01 → A03 연결)
        long answerId = Long.parseLong(body.get("answer_id").asText());
        assertThat(db.queryForMap("select account_id, mode, baseline_id, document_version_ids::text as ids from bee_answer where id = ?", answerId))
                .containsEntry("account_id", memberAccount).containsEntry("mode", "APPROVED")
                .containsEntry("baseline_id", baseline).containsEntry("ids", "[" + doc[1] + "]");
        HttpResponse<String> feedback = member.post("/api/projects/" + project.projectId() + "/bee/answers/" + answerId + "/feedback",
                "{\"rating\":\"HELPFUL\"}", "Origin", ORIGIN);
        assertThat(feedback.statusCode()).as(feedback.body()).isEqualTo(204);
        // 모델 호출 두 건이 BEE_CHAT 사용량으로 남는다
        assertThat(db.queryForObject("select count(*) from llm_usage where project_id = ? and feature = 'BEE_CHAT'",
                Long.class, project.projectId())).isEqualTo(2L);
    }

    @Test
    void ask_sendsOnlyCurrentBaselineChunks_neverOtherProjectOrNonBaselineVersions() {
        long[] mine = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "내 프로젝트 승인 문서");
        long[] notInBaseline = indexedDocument(project.projectId(), project.leaderAccountId(), "작성 중 문서", "아직 승인 안 된 문서");
        publish(project.projectId(), project.leaderAccountId(), 1, mine);
        var other = fixtures().projectWithLeader();
        long[] foreign = indexedDocument(other.projectId(), other.leaderAccountId(), "남의 프로젝트", "다른 프로젝트 비밀 문서");
        publish(other.projectId(), other.leaderAccountId(), 1, foreign);
        gatewayAnswers(r -> { });

        ok(ask(member, project.projectId(), QUESTION));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(gateway).chat(sent.capture());
        Map<String, Object> request = sent.getValue();
        @SuppressWarnings("unchecked")
        var evidence = (List<Map<String, Object>>) request.get("retrieved_evidence");
        assertThat(evidence).extracting(e -> e.get("document_version_id"))
                .containsExactly(String.valueOf(mine[1]))
                .doesNotContain(String.valueOf(foreign[1]), String.valueOf(notInBaseline[1]));
        @SuppressWarnings("unchecked")
        var scope = (Map<String, Object>) request.get("scope");
        assertThat(scope).containsEntry("mode", "APPROVED");
        assertThat(scope.get("document_version_ids")).isEqualTo(List.of(String.valueOf(mine[1])));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ask_buildsSourcesAndCitationFieldsFromWhatWasSent_notFromTheAiResponse() {
        long[] doc = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        publish(project.projectId(), project.leaderAccountId(), 1, doc);
        gatewayAnswers(r -> {
            // 식별 값은 검증기가 맞는지 확인하지만, 표시 값(제목·절 제목·출처 위치)은 AI 가 마음대로 지어낼 수 있는 자리다
            Map<String, Object> citation = (Map<String, Object>) ((List<?>) r.get("citations")).get(0);
            citation.put("title", "AI가 지어낸 제목");
            citation.put("section_title", "AI가 지어낸 절");
            Map<String, Object> source = new LinkedHashMap<>((Map<String, Object>) ((List<?>) r.get("sources")).get(0));
            source.put("location", "AI가 지어낸 위치 / L9-L9");
            r.put("sources", new ArrayList<>(List.of(source)));
        });

        JsonNode body = ok(ask(member, project.projectId(), QUESTION));

        JsonNode citation = body.get("citations").get(0);
        assertThat(citation.get("title").asText()).isEqualTo("예약 명세");
        assertThat(citation.has("section_title")).isFalse();
        assertThat(body.get("sources").size()).isEqualTo(1);
        JsonNode source = body.get("sources").get(0);
        assertThat(source.get("document_id").asText()).isEqualTo(String.valueOf(doc[0]));
        assertThat(source.get("document_version_id").asText()).isEqualTo(String.valueOf(doc[1]));
        assertThat(source.get("location").asText()).isEqualTo("예약 명세 / L1-L1");
    }

    @Test
    void ask_insufficientEvidenceIsAnOrdinaryAnswerNotAnError() {
        long[] doc = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        publish(project.projectId(), project.leaderAccountId(), 1, doc);
        gatewayAnswers(r -> {
            r.put("answer_status", "INSUFFICIENT_EVIDENCE");
            r.put("grounded", false);
            r.put("citations", new ArrayList<>());
            r.put("sources", new ArrayList<>());
            r.put("text", "찾지 못했어요. 지금 승인된 문서와 설계 항목에서는 이 질문에 답할 근거를 찾지 못했습니다. 짐작해서 답하지 않습니다.");
        });

        JsonNode body = ok(ask(member, project.projectId(), QUESTION));

        assertThat(body.get("grounded").asBoolean()).isFalse();
        assertThat(body.get("citations").size()).isZero();
        assertThat(body.get("origin").asText()).isEqualTo("MODEL");
        assertThat(body.get("text").asText()).startsWith("찾지 못했어요");
    }

    // ---- 권한 밖 근거 거부 (완료 조건 2) ------------------------------------------------------

    @Test
    void ask_rejectsAnswerCitingEvidenceThatWasNeverSent_retriesOnce_thenTemplateWithoutCitations() {
        long[] doc = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        publish(project.projectId(), project.leaderAccountId(), 1, doc);
        gatewayAnswers(r -> ((Map<String, Object>) ((List<?>) r.get("citations")).get(0)).put("evidence_id", "ev-99999999"));

        JsonNode body = ok(ask(member, project.projectId(), QUESTION));

        verify(gateway, times(2)).chat(anyMap());
        assertThat(body.get("origin").asText()).isEqualTo("TEMPLATE");
        assertThat(body.get("grounded").asBoolean()).isFalse();
        assertThat(body.get("citations").size()).isZero();
        assertThat(body.has("usage")).isFalse();
        // 거부한 모델 답의 본문은 어디에도 나가지 않는다
        assertThat(body.get("text").asText()).doesNotContain("[1]").doesNotContain("충돌을 막습니다");
    }

    @Test
    void ask_rejectsAnswerWhoseSourceIsAnotherProjectsDocument() {
        long[] mine = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        publish(project.projectId(), project.leaderAccountId(), 1, mine);
        var other = fixtures().projectWithLeader();
        long[] foreign = indexedDocument(other.projectId(), other.leaderAccountId(), "남의 프로젝트", "다른 프로젝트 비밀 문서");
        gatewayAnswers(r -> r.put("sources", new ArrayList<>(List.of(Map.of("document_id", String.valueOf(foreign[0]),
                "document_version_id", String.valueOf(foreign[1]), "version_no", 1, "location", "남의 프로젝트 / L1-L1")))));

        JsonNode body = ok(ask(member, project.projectId(), QUESTION));

        assertThat(body.get("origin").asText()).isEqualTo("TEMPLATE");
        assertThat(body.toString()).doesNotContain(String.valueOf(foreign[1])).doesNotContain("비밀");
    }

    // ---- AI 장애 -----------------------------------------------------------------------------

    @Test
    void ask_aiUnavailableTwice_fallsBackToTemplateWithoutFakeUsage() {
        long[] doc = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        publish(project.projectId(), project.leaderAccountId(), 1, doc);
        when(gateway.chat(anyMap())).thenThrow(new AiCallException(HttpStatus.BAD_GATEWAY, "ai_unavailable", "x", null));

        JsonNode body = ok(ask(member, project.projectId(), QUESTION));

        verify(gateway, times(2)).chat(anyMap());
        assertThat(body.get("origin").asText()).isEqualTo("TEMPLATE");
        assertThat(body.get("grounded").asBoolean()).isFalse();
        assertThat(body.get("sources").get(0).get("document_version_id").asText()).isEqualTo(String.valueOf(doc[1]));
        assertThat(body.get("answer_id").asText()).isNotBlank();
        // 실패한 호출도 "미확인" 사용량으로 남는다(토큰 수를 0 으로 단정하지 않는다)
        assertThat(db.queryForObject("select count(*) from llm_usage where project_id = ? and feature = 'BEE_CHAT' and failed and not tokens_known",
                Long.class, project.projectId())).isEqualTo(2L);
    }

    @Test
    void ask_aiTimeout_is504WithoutRetry() {
        long[] doc = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        publish(project.projectId(), project.leaderAccountId(), 1, doc);
        when(gateway.chat(anyMap())).thenThrow(new AiCallException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", "x", null));

        HttpResponse<String> res = ask(member, project.projectId(), QUESTION);

        assertThat(res.statusCode()).isEqualTo(504);
        assertThat(error(res)).isEqualTo("ai_timeout");
        verify(gateway, times(1)).chat(anyMap());
        assertThat(answerCount(project.projectId())).isZero();
    }

    // ---- 기준·색인 상태 ----------------------------------------------------------------------

    @Test
    void ask_withoutApprovedBaseline_is409IndexNotReady_andDoesNotCallAi() {
        HttpResponse<String> res = ask(member, project.projectId(), QUESTION);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(error(res)).isEqualTo("index_not_ready");
        verify(gateway, times(0)).chat(anyMap());
    }

    @Test
    void ask_baselineReplacedWhileAiRuns_is409BaselineChanged_andIssuesNoAnswer() {
        long[] doc = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        publish(project.projectId(), project.leaderAccountId(), 1, doc);
        when(gateway.chat(anyMap())).thenAnswer(invocation -> {
            publish(project.projectId(), project.leaderAccountId(), 2, doc);   // 호출 중 새 기준이 발행됨
            return modelAnswer(invocation.getArgument(0), r -> { });
        });

        HttpResponse<String> res = ask(member, project.projectId(), QUESTION);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(error(res)).isEqualTo("baseline_changed");
        assertThat(answerCount(project.projectId())).isZero();
    }

    // ---- 접근·입력 ---------------------------------------------------------------------------

    @Test
    void ask_nonMemberAndAnonymous_areRejectedBeforeAnyWork() {
        long[] doc = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        publish(project.projectId(), project.leaderAccountId(), 1, doc);
        String email = AccessFixtures.uniqueEmail();
        fixtures().account(email);
        ApiClient outsider = client().login(email, AccessFixtures.PASSWORD);

        assertThat(ask(outsider, project.projectId(), QUESTION).statusCode()).isEqualTo(404);
        assertThat(ask(client(), project.projectId(), QUESTION).statusCode()).isEqualTo(401);
        verify(gateway, times(0)).chat(anyMap());
        verify(ai, times(0)).documentEmbeddings(anyMap());
    }

    @Test
    void ask_untrustedOrigin_isRejected() {
        HttpResponse<String> res = member.post("/api/projects/" + project.projectId() + "/bee/questions", QUESTION,
                "Origin", "http://evil.example");

        assertThat(res.statusCode()).isGreaterThanOrEqualTo(400).isLessThan(500);
        verify(gateway, times(0)).chat(anyMap());
    }

    @Test
    void ask_invalidInput_is400() {
        assertThat(ask(member, project.projectId(), "{\"question\":\"  \"}").statusCode()).isEqualTo(400);
        assertThat(ask(member, project.projectId(), "{}").statusCode()).isEqualTo(400);
        assertThat(ask(member, project.projectId(), "{\"question\":\"" + "가".repeat(2001) + "\"}").statusCode()).isEqualTo(400);
        assertThat(ask(member, project.projectId(), "{\"question\":\"q\",\"scope\":\"INCLUDE_DRAFTS\"}").statusCode()).isEqualTo(400);
        assertThat(ask(member, project.projectId(), "{\"question\":\"q\",\"scope\":\"ALL\"}").statusCode()).isEqualTo(400);
        assertThat(ask(member, project.projectId(), "{\"question\":\"q\",\"task_id\":\"abc\"}").statusCode()).isEqualTo(400);
        verify(gateway, times(0)).chat(anyMap());
    }

    @Test
    void ask_unknownTask_is404() {
        long[] doc = indexedDocument(project.projectId(), project.leaderAccountId(), "예약 명세", "같은 시간대 예약은 거절한다");
        publish(project.projectId(), project.leaderAccountId(), 1, doc);

        HttpResponse<String> res = ask(member, project.projectId(), "{\"question\":\"q\",\"task_id\":\"999999999\"}");

        assertThat(res.statusCode()).isEqualTo(404);
        verify(gateway, times(0)).chat(anyMap());
    }
}
