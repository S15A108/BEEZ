package com.beez.backend.ai;

import com.beez.backend.config.BeezProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * FastAPI AI 서버 호출 전용 클라이언트.
 * 원칙: ai 는 텍스트·임베딩·실행 증거만 돌려준다. 판정·상태 변경은 절대 이 응답으로 결정하지 않는다.
 * 모델 호출이 실패하면 호출한 쪽이 템플릿으로 폴백하고 origin 을 정직하게 기록한다 (프로토타입 llm.ts 와 동일).
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    /** 질문 생성(30초)·초안 생성(45초) 모델 제한보다 길어야 정상 처리 중에 먼저 끊지 않는다. */
    static final Duration DEFAULT_QUESTION_READ_TIMEOUT = Duration.ofSeconds(40);
    static final Duration DEFAULT_DRAFT_READ_TIMEOUT = Duration.ofSeconds(55);

    /** 비비 대화(답변 모델 30초 + 문장 판정 모델 30초)보다 길어야 정상 처리 중에 먼저 끊지 않는다. */
    static final Duration DEFAULT_CHAT_READ_TIMEOUT = Duration.ofSeconds(70);

    private final RestClient rest;
    private final RestClient questionRest;
    private final RestClient draftRest;
    private final RestClient chatRest;

    public AiClient(BeezProperties props) {
        this(props, DEFAULT_QUESTION_READ_TIMEOUT, DEFAULT_DRAFT_READ_TIMEOUT, DEFAULT_CHAT_READ_TIMEOUT);
    }

    public AiClient(BeezProperties props, Duration questionReadTimeout, Duration draftReadTimeout) {
        this(props, questionReadTimeout, draftReadTimeout, DEFAULT_CHAT_READ_TIMEOUT);
    }

    @Autowired
    public AiClient(BeezProperties props,
                    @Value("${beez.planning.question-read-timeout:40s}") Duration questionReadTimeout,
                    @Value("${beez.planning.draft-read-timeout:55s}") Duration draftReadTimeout,
                    @Value("${beez.bee.chat-read-timeout:70s}") Duration chatReadTimeout) {
        BeezProperties.Ai ai = props.ai();
        this.rest = client(ai, ai.readTimeout());
        // 기본 읽기 제한보다 짧게 설정되어도 공통 호출보다 먼저 끊기지 않게 한다.
        this.questionRest = client(ai, max(ai.readTimeout(), questionReadTimeout));
        this.draftRest = client(ai, max(ai.readTimeout(), draftReadTimeout));
        this.chatRest = client(ai, max(ai.readTimeout(), chatReadTimeout));
    }

    private static Duration max(Duration a, Duration b) { return a.compareTo(b) >= 0 ? a : b; }

    private static RestClient client(BeezProperties.Ai ai, Duration readTimeout) {
        // Uvicorn uses HTTP/1.1; avoid h2c upgrades on streamed request bodies.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(ai.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(ai.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ai.internalToken())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    /** ai 서버 생존 확인. 실패해도 예외를 던지지 않는다 (ai 가 꺼져 있어도 backend 는 동작해야 한다). */
    public boolean ping() {
        try {
            rest.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.debug("ai server unreachable: {}", e.toString());
            return false;
        }
    }

    /**
     * AI-A02 작업 계획 의미 분석 ({@code POST /v1/validate}). 요청·응답 모양은 명세 그대로 Map 으로 주고받는다.
     * 호출 실패·HTTP 오류는 그대로 던진다 — 분류는 부르는 쪽(validation 워커)이 한다.
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> validate(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/validate").body(input).retrieve().body(java.util.Map.class);
    }

    /** 이후 lead/review/embeddings 호출이 공통으로 쓸 RestClient. */
    protected RestClient rest() {
        return rest;
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> documentChunks(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/document-chunks").body(input).retrieve().body(java.util.Map.class);
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> documentEmbeddings(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/embeddings").body(input).retrieve().body(java.util.Map.class);
    }

    /** Analyze a proposal; callers must retain the usage envelope and handle failures explicitly. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> changeImpact(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/impact").body(input).retrieve().body(java.util.Map.class);
    }

    /** BE adapter contract; AI feature implementation belongs to the AI owner. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> documentReview(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/document-reviews").body(input).retrieve().body(java.util.Map.class);
    }

    /** 기획 보완 질문 생성(AI 모델 제한 30초). 공통 읽기 제한(20초)보다 긴 전용 제한을 쓴다. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> planningQuestions(java.util.Map<String, Object> input) {
        return questionRest.post().uri("/v1/planning-questions").body(input).retrieve().body(java.util.Map.class);
    }

    /** 답변 반영 설계 초안 ({@code POST /v1/design-drafts}, S15P31A108-134 계약). 실패 분류는 부르는 쪽이 한다. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> designDrafts(java.util.Map<String, Object> input) {
        return draftRest.post().uri("/v1/design-drafts").body(input).retrieve().body(java.util.Map.class);
    }

    /**
     * AI-A06 기획·문서 수정 초안 생성 ({@code POST /v1/bee/draft}). 호출 실패·HTTP 오류는 그대로 던진다 — 분류는 부르는 쪽이 한다.
     * 읽기 시간 제한은 다른 호출과 같은 {@code ai.read-timeout} 이다. 문서 한 건을 다시 쓰는 호출은 더 걸릴 수 있어
     * 기능별 시간 제한이 필요한지 137 실측 뒤에 정한다 (S15P31A108-233 계획 §0.5 A~C).
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> beeDraft(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/bee/draft").body(input).retrieve().body(java.util.Map.class);
    }

    /** AI-A07: 추천만 반환한다. AI의 15초 제한보다 긴 전용 읽기 제한을 사용한다. */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> assignmentRecommendations(java.util.Map<String, Object> input) {
        return questionRest.post().uri("/v1/bee/assignments").body(input).retrieve().body(java.util.Map.class);
    }

    /**
     * AI-A05 비비 질문 답변 ({@code POST /v1/bee/chat}, S15P31A108-135 계약). 답변 모델과 문장 판정 모델을 차례로 부르므로
     * 전용 읽기 제한({@code beez.bee.chat-read-timeout})을 쓴다. 실패 분류는 부르는 쪽이 한다.
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> beeChat(java.util.Map<String, Object> input) {
        return chatRest.post().uri("/v1/bee/chat").body(input).retrieve().body(java.util.Map.class);
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> revisionBlocks(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/document-revision-blocks").body(input).retrieve().body(java.util.Map.class);
    }

    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> documentRevision(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/document-revisions").body(input).retrieve().body(java.util.Map.class);
    }

    /**
     * AI-A08 회의 분석 ({@code POST /v1/bee/meeting-analysis}). BE 는 SUMMARY 모드(회의 정리)로 부른다.
     * 호출 실패·HTTP 오류는 그대로 던진다 — 분류는 부르는 쪽이 한다. AI 서버는 2026-11-05 기준 SUMMARY 를
     * 아직 받지 않는다(S15P31A108-235). 읽기 시간 제한은 다른 호출과 같은 {@code ai.read-timeout} 이다.
     */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> meetingAnalysis(java.util.Map<String, Object> input) {
        return rest.post().uri("/v1/bee/meeting-analysis").body(input).retrieve().body(java.util.Map.class);
    }
}
