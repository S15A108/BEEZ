package com.beez.backend.usage.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.usage.dto.UsageResponses.UsageBucket;
import com.beez.backend.usage.dto.UsageResponses.UsageSummary;
import com.beez.backend.usage.repository.UsageRepository;
import com.beez.backend.usage.repository.UsageRepository.Aggregate;
import com.beez.backend.usage.repository.UsageRepository.Axis;
import com.beez.backend.usage.repository.UsageRepository.Filter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트 AI 사용량·추정 비용 조회 (S15P31A108-169, MON-A02 / MON-05). 공통 사용량 표(llm_usage)를 읽기만 한다.
 * <ul>
 *   <li>전체·기능별·날짜별 합계에는 작업에 연결되지 않은 호출(문서 분석·검토 등)도 들어간다. 작업별·검증별에는 연결된 호출만</li>
 *   <li>같은 모델 호출은 request_key 로 이미 한 줄이다. 실패·재시도 호출도 실제 호출 수만큼 센다</li>
 *   <li>추정 비용은 단가가 있는 호출만 더한다. 비용을 모르는 호출을 0원으로 치지 않고, 합계에 못 들어간 호출 수를
 *       cost_unknown_call_count 로 따로 준다. 비용 있는 호출이 하나도 없으면 비용·통화를 생략한다. 통화가 여러 개면 가장 많은 통화로만
 *       더하고 나머지는 비용 모름으로 센다 (환산하지 않음). 토큰도 같은 방식(tokens_unknown_call_count) — 입력·출력이 둘 다 있는 호출만 더하고,
 *       하나라도 모르면 그 호출의 토큰은 합계에 넣지 않는다</li>
 *   <li>모델을 부르는 기능은 모두 llm_usage 에 적는다: 검증(-168), 문서 분석·검토, 변경 영향 분석(승인 전·발행 뒤, LlmUsageRecorder). 외부 에이전트가
 *       자기 모델을 쓴 사용량은 BE 를 거치지 않아 들어가지 않는다</li>
 *   <li>모든 축이 같은 조건·같은 기준 통화로 계산되므로 기능별 합 = 날짜별 합 = 전체 합이다. 같은 스냅샷으로 읽도록 REPEATABLE_READ</li>
 *   <li>by_agent 는 비어 있다 — llm_usage 에 호출자(에이전트 세션·웹) 칸이 아직 없어 나눌 수 없다. 칸을 추가하고 각 기능이 적는 것은 후속</li>
 * </ul>
 */
@Service
public class UsageQueryService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** by_day 를 0 으로 채우는 범위의 상한 (일). 넘으면 400 */
    static final int MAX_DAYS = 366;

    /** llm_usage.feature CHECK 와 같은 순서. 기능별은 호출이 없어도 한 줄씩 0 으로 준다 */
    static final List<String> FEATURES = List.of("DOCUMENT_PARSE", "TASK_VALIDATION", "CHANGE_IMPACT", "EMBEDDING", "BEE_CHAT",
            "BEE_DRAFT", "TASK_ASSIGNMENT", "MEETING_ANALYSIS");

    private final ProjectAccessChecker access;
    private final UsageRepository repository;

    public UsageQueryService(ProjectAccessChecker access, UsageRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsageSummary summary(CallerContext caller, long projectId, Instant from, Instant to, Long taskId, Long validationId,
                                String feature) {
        access.requireMember(caller, projectId);
        if (from == null || to == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "from 과 to 는 필수입니다");
        }
        if (!from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "from 은 to 보다 앞이어야 합니다");
        }
        List<LocalDate> days = days(from, to);
        if (feature != null && !FEATURES.contains(feature)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "알 수 없는 feature 입니다: " + feature);
        }
        // 다른 프로젝트의 작업·검증은 없는 것과 같은 응답 (명세 오류표 404)
        if (taskId != null && !repository.taskInProject(projectId, taskId)) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
        if (validationId != null && !repository.validationInProject(projectId, validationId)) {
            throw AccessErrors.notFound("validation_not_in_project validation=" + validationId + " project=" + projectId);
        }

        Filter filter = new Filter(projectId, from, to, taskId, validationId, feature);
        Optional<String> found = repository.dominantCurrency(filter);
        String currency = null;
        if (found.isPresent()) {
            currency = found.get();
        }

        List<Aggregate> totals = repository.aggregate(filter, Axis.TOTAL, currency);
        Aggregate total = new Aggregate("TOTAL", 0, 0, 0, null, 0, 0);
        if (!totals.isEmpty()) {
            total = totals.get(0);
        }

        List<UsageBucket> byFeature = zeroFilled(FEATURES, repository.aggregate(filter, Axis.FEATURE, currency), currency);
        List<String> dayKeys = new ArrayList<>();
        for (LocalDate day : days) {
            dayKeys.add(day.toString());
        }
        List<UsageBucket> byDay = zeroFilled(dayKeys, repository.aggregate(filter, Axis.DAY, currency), currency);

        List<Aggregate> taskRows = repository.aggregate(filter, Axis.TASK, currency);
        List<Long> taskIds = new ArrayList<>();
        for (Aggregate a : taskRows) {
            taskIds.add(Long.parseLong(a.key()));
        }
        Map<Long, String> taskKeys = repository.taskKeys(projectId, taskIds);
        List<UsageBucket> byTask = new ArrayList<>();
        for (Aggregate a : taskRows) {
            byTask.add(bucket(a, taskKeys.get(Long.parseLong(a.key())), currency));
        }
        List<UsageBucket> byValidation = new ArrayList<>();
        for (Aggregate a : repository.aggregate(filter, Axis.VALIDATION, currency)) {
            byValidation.add(bucket(a, null, currency));
        }

        return new UsageSummary(from, to, total.inputTokens(), total.outputTokens(), total.callCount(), cost(total, currency),
                currency, total.tokensUnknownCallCount(), total.costUnknownCallCount(), byFeature, byTask, byValidation,
                List.of(), byDay);
    }

    /** [from, to) 가 걸치는 한국 날짜들. 상한을 넘으면 400 */
    static List<LocalDate> days(Instant from, Instant to) {
        LocalDate first = from.atZone(KST).toLocalDate();
        LocalDate last = to.minusNanos(1).atZone(KST).toLocalDate();
        long count = ChronoUnit.DAYS.between(first, last) + 1;
        if (count > MAX_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "조회 기간은 " + MAX_DAYS + "일 이하여야 합니다");
        }
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }

    /** keys 순서대로, 호출이 없는 키는 0 으로 */
    private static List<UsageBucket> zeroFilled(List<String> keys, List<Aggregate> rows, String currency) {
        Map<String, Aggregate> byKey = new LinkedHashMap<>();
        for (Aggregate a : rows) {
            byKey.put(a.key(), a);
        }
        List<UsageBucket> out = new ArrayList<>();
        for (String key : keys) {
            if (byKey.containsKey(key)) {
                out.add(bucket(byKey.get(key), null, currency));
            } else {
                out.add(bucket(new Aggregate(key, 0, 0, 0, null, 0, 0), null, currency));
            }
        }
        return out;
    }

    private static UsageBucket bucket(Aggregate a, String label, String currency) {
        return new UsageBucket(a.key(), label, a.inputTokens(), a.outputTokens(), a.callCount(), cost(a, currency),
                a.tokensUnknownCallCount(), a.costUnknownCallCount());
    }

    /**
     * 추정 비용 글자. 기준 통화가 없으면(비용 있는 호출이 없음) 생략. 기준 통화가 있으면 그 통화 호출이 없는 묶음은 "0" — 비용을 모르는
     * 호출이 섞였는지는 cost_unknown_call_count 로 본다. 뒤의 0 은 지운다 (0.004200 → 0.0042)
     */
    static String cost(Aggregate a, String currency) {
        if (currency == null) {
            return null;
        }
        BigDecimal amount = BigDecimal.ZERO;
        if (a.costAmount() != null) {
            amount = a.costAmount();
        }
        if (amount.signum() == 0) {
            return "0";
        }
        return amount.stripTrailingZeros().toPlainString();
    }
}
