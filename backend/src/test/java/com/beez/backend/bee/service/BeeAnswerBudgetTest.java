package com.beez.backend.bee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beez.backend.ai.AiCallException;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.bee.dto.BeeAskRequest;
import com.beez.backend.bee.port.BeeChatGateway;
import com.beez.backend.bee.repository.BeeRepository;
import com.beez.backend.common.access.ActorType;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.document.service.DocumentIndexWorker;
import com.beez.backend.document.service.DocumentIndexWorker.ChunkEvidence;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.task.service.TaskQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * BEE-A01 이 Nginx 기본 읽기 대기(60초)보다 먼저 끝나게 하는 시간 예산 (S15P31A108-344).
 * 재시도뿐 아니라 첫 AI 호출도 남은 예산 안에서만 시작하고, 호출의 읽기 제한도 남은 시간으로 줄인다.
 */
class BeeAnswerBudgetTest {

    private static final Duration CHAT = Duration.ofSeconds(35);
    private static final Duration BUDGET = BeeQuestionService.DEFAULT_ANSWER_BUDGET;

    // ---- 순수 계산 -----------------------------------------------------------------------------

    @Test
    void retriesOnlyWhenTheFirstStepsWereFast() {
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(5).toNanos(), CHAT, BUDGET)).isTrue();
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(20).toNanos(), CHAT, BUDGET)).isTrue();
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(21).toNanos(), CHAT, BUDGET)).isFalse();
    }

    @Test
    void callLimitIsTheShorterOfRemainingBudgetAndConfiguredTimeout() {
        assertThat(BeeQuestionService.callLimit(Duration.ofSeconds(2).toNanos(), CHAT, BUDGET)).isEqualTo(CHAT);
        // 검색·DB 에 27초를 썼다면 첫 호출도 남은 28초까지만 기다린다(35초가 아니다)
        assertThat(BeeQuestionService.callLimit(Duration.ofSeconds(27).toNanos(), CHAT, BUDGET)).isEqualTo(Duration.ofSeconds(28));
    }

    @Test
    void callLimitIsZeroWhenTooLittleTimeIsLeftToGetAnAnswer() {
        assertThat(BeeQuestionService.callLimit(Duration.ofSeconds(45).toNanos(), CHAT, BUDGET)).isEqualTo(Duration.ofSeconds(10));
        assertThat(BeeQuestionService.callLimit(Duration.ofSeconds(46).toNanos(), CHAT, BUDGET)).isZero();
        assertThat(BeeQuestionService.callLimit(Duration.ofSeconds(80).toNanos(), CHAT, BUDGET)).isZero();
    }

    @Test
    void defaultBudgetStaysBelowNginxDefaultReadTimeout() {
        // 서버 Nginx 의 /api/ 는 proxy_read_timeout 이 없어 기본 60초다. 예산은 그보다 짧아야 브라우저가 먼저 끊지 않는다
        assertThat(BUDGET).isLessThan(Duration.ofSeconds(60));
    }

    @Test
    void serverEnvNamesBindToTheProperties() {
        // compose 가 넘기는 환경 변수 이름이 @Value 속성에 닿는지 (Spring 의 환경 변수 → 속성 이름 규칙)
        var env = new SystemEnvironmentPropertySource("test",
                Map.of("BEEZ_BEE_CHAT_READ_TIMEOUT", "25s", "BEEZ_BEE_ANSWER_BUDGET", "50s"));
        assertThat(env.getProperty("beez.bee.chat-read-timeout")).isEqualTo("25s");
        assertThat(env.getProperty("beez.bee.answer-budget")).isEqualTo("50s");
    }

    // ---- 서비스: 검색이 느린 경우 -----------------------------------------------------------------

    private ProjectAccessChecker access;
    private DocumentIndexWorker search;
    private BeeRepository repository;
    private BeeChatGateway gateway;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessChecker.class);
        search = mock(DocumentIndexWorker.class);
        repository = mock(BeeRepository.class);
        gateway = mock(BeeChatGateway.class);
        when(access.requireMember(any(), eq(1L))).thenReturn(new ProjectActor(5, 6, 1, ProjectRole.MEMBER, ActorType.HUMAN, null));
        when(search.embeddingProfile()).thenReturn("p");
        when(repository.approvedItems(anyLong(), anyLong(), anyInt())).thenReturn(List.of());
        when(repository.currentBaseline(1L)).thenReturn(Optional.of(7L));
        when(repository.issueAnswer(anyLong(), anyLong(), anyString(), any(), anyLong())).thenReturn(9L);
    }

    /** 검색이 delayMillis 걸린 뒤 근거 한 건을 돌려주는 서비스. 예산은 AI 호출 최소 시간(10초)보다 조금 큰 값으로 줄여서 시험한다 */
    private BeeQuestionService service(long searchDelayMillis, Duration budget) {
        when(search.retrieveEvidence(eq(1L), anyString(), anyInt())).thenAnswer(invocation -> {
            Thread.sleep(searchDelayMillis);
            ChunkEvidence chunk = new ChunkEvidence(1, 2, 3, 1, "c1", "본문", Map.of("start_line", 1, "end_line", 1), "명세");
            return new DocumentIndexWorker.Retrieval(7, List.of(3L), List.of(chunk));
        });
        return new BeeQuestionService(access, search, repository, gateway, mock(AiUsageRecorder.class), mock(TaskQueryService.class),
                JsonMapper.builder().build(), new SimpleMeterRegistry(), CHAT, budget);
    }

    @Test
    void slowSearchThatUsedTheBudgetEndsWithTemplateAndNeverCallsTheModel() {
        // 예산 10.3초 중 검색이 0.5초 → 남은 시간이 AI 호출 최소 시간(10초)보다 적다
        BeeQuestionService service = service(500, Duration.ofMillis(10_300));

        Map<String, Object> response = service.ask(null, 1L, new BeeAskRequest("질문", null, null));

        assertThat(response.get("origin")).isEqualTo("TEMPLATE");
        assertThat(response.get("answer_id")).isNotNull();
        verify(gateway, never()).chat(anyMap(), any());
    }

    @Test
    void firstCallIsLimitedByWhatIsLeftOfTheBudgetNotTheFullReadTimeout() {
        BeeQuestionService service = service(100, Duration.ofMillis(10_600));
        when(gateway.chat(anyMap(), any())).thenThrow(new AiCallException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", "x", null));

        assertThatThrownBy(() -> service.ask(null, 1L, new BeeAskRequest("질문", null, null))).isInstanceOf(AiCallException.class);

        ArgumentCaptor<Duration> limit = ArgumentCaptor.forClass(Duration.class);
        verify(gateway).chat(anyMap(), limit.capture());
        assertThat(limit.getValue()).isLessThan(CHAT).isLessThanOrEqualTo(Duration.ofMillis(10_500))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(10));
    }
}
