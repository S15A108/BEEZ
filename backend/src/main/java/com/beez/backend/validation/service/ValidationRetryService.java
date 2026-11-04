package com.beez.backend.validation.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.repository.TaskRepository;
import com.beez.backend.task.service.ProjectDesignLookup;
import com.beez.backend.validation.config.ValidationProperties;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.repository.ValidationRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VAL-A04·MCP-14 검증 실행 재시도 (S15P31A108-161). ERROR 로 끝난 검증을 같은 입력으로 한 번 더 돌린다.
 * 서버도 AI 도 스스로 다시 돌리지 않는다 — 사람(또는 에이전트)이 요청할 때만 새 시도를 연다 (03-ai-internal 재시도 규칙).
 *
 * <p><b>중복 요청</b>: 요청의 expected_attempt 가 곧 이 요청의 식별값이다. 시도 N 을 다시 돌리면 시도 N+1 이 생기고, N 다음에 생길 수
 * 있는 시도는 N+1 하나뿐이다. 그래서 응답을 잃고 같은 요청을 다시 보내면(expected_attempt 가 현재 시도보다 작음) 새로 만들지 않고 그 요청이
 * 만든 시도가 있는 지금 검증을 그대로 돌려준다. 권한은 매번 다시 본다. 동시에 두 요청이 와도 프로젝트 잠금으로 한 줄로 서므로 시도는 하나만 생긴다.
 *
 * <p><b>받는 조건</b> (프로젝트 행 → 검증 순서로 잠근 뒤, 공통 6절 "검증 실행 오류 규약"):
 * <ol>
 *   <li>작업 담당자(TASK_OWNER). 아니면 403</li>
 *   <li>expected_attempt 가 현재 시도와 같음. 더 크면 409 revision_conflict</li>
 *   <li>검증이 ERROR 이고, 현재 시도의 실행 오류 항목이 하나 이상이며 모두 RETRY. 하나라도 HUMAN_REVIEW 면 거부 — 근거를 보완해 새 검증.
 *       작업이 끝났거나(완료·취소) 그 작업에 더 최근 검증이 있어도 거부. 모두 409 invalid_transition</li>
 *   <li>입력이 그대로: 계획 내용·관련 작업의 계획({@link AnalysisResultApplier#inputChange}), 참조한 설계 항목 버전이 지금 기준과 같음.
 *       바뀌었으면 409 revision_conflict — 새 검증을 요청한다 (STALE 이 될 입력을 재시도로 되살리지 않는다)</li>
 *   <li>횟수 제한: 같은 작업·같은 입력으로 돌린 분석이 정한 횟수에 닿았으면 409 validation_attempt_limit (새 검증 요청과 같은 수)</li>
 * </ol>
 * 받으면 시도 번호를 올려 RUNNING 으로 다시 열고, 새 시도의 실행(run)을 만들고, 큐 작업을 다시 대기로 돌린다. 지난 시도의 실행·오류 항목은
 * 이력으로 남고 응답의 발견 항목에서는 빠진다. 요약의 지난 시도 진단 값(오류 코드·부분 후보·모델·사용량)은 걷어 낸다 — 비용은 실행별로 남아 있다.
 * 워커가 검색할 때 쓰는 요청자는 다시 돌리는 사람으로 바꾼다 (처음 요청한 에이전트 토큰이 그 사이 폐기됐을 수 있다).
 */
@Service
public class ValidationRetryService {

    /** 지난 시도의 결과·진단으로 요약에 남는 키. 새 시도를 열 때 걷어 낸다 (AnalysisResultApplier 의 성공·실패 반영이 쓰는 키) */
    private static final List<String> ATTEMPT_KEYS = List.of(
            ValidationResponses.SUMMARY_ERROR_CODE, "analysis_status", "checked_keys", "unassessed", "partial_findings",
            "model", "usage", "usage_rejected", "analysis_baseline_id", "analysis_input_snapshot_hash");

    private final ProjectAccessChecker access;
    private final ProjectDesignLookup designLookup;
    private final TaskRepository tasks;
    private final ValidationRepository validations;
    private final ValidationResponses responses;
    private final AnalysisResultApplier applier;
    private final ValidationProperties properties;

    public ValidationRetryService(ProjectAccessChecker access, ProjectDesignLookup designLookup, TaskRepository tasks,
                                  ValidationRepository validations, ValidationResponses responses, AnalysisResultApplier applier,
                                  ValidationProperties properties) {
        this.access = access;
        this.designLookup = designLookup;
        this.tasks = tasks;
        this.validations = validations;
        this.responses = responses;
        this.applier = applier;
        this.properties = properties;
    }

    /**
     * @param expectedAttempt 요청한 사람이 조회한 현재 시도 번호 (1 이상)
     * @return 다시 연 검증 (RUNNING). 같은 요청을 다시 보낸 경우에는 그 요청이 만든 시도가 있는 지금 검증
     */
    @Transactional
    public ValidationResponse retry(CallerContext caller, Long projectId, Long validationId, Integer expectedAttempt) {
        ProjectActor actor = access.requireMember(caller, projectId);
        if (expectedAttempt == null || expectedAttempt < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "expected_attempt 는 1 이상의 정수입니다");
        }
        // 프로젝트 잠금: 계획 변경·상태 보고·검증 접수·결과 반영과 같은 project → (job →) validation 순서
        if (!designLookup.lockProject(projectId)) {
            throw AccessErrors.notFound("project_missing project=" + projectId);
        }
        if (validations.find(projectId, validationId).isEmpty()) {
            throw AccessErrors.notFound("validation_missing project=" + projectId + " validation=" + validationId);
        }
        ValidationRow v = validations.lock(validationId).orElseThrow();
        Task task = tasks.findByIdAndProjectId(v.taskId(), projectId)
                .orElseThrow(() -> AccessErrors.notFound("task_not_in_project task=" + v.taskId() + " project=" + projectId));
        actor.requireMemberId(task.getAssigneeMemberId(), "담당자만 검증을 다시 실행할 수 있습니다");

        if (expectedAttempt < v.currentAttempt()) {
            // 같은 요청의 재전송: 시도 expectedAttempt + 1 은 이미 그 요청이 만들었다
            return responses.toResponse(v);
        }
        if (expectedAttempt > v.currentAttempt()) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                    "시도 번호가 현재와 다릅니다 (현재 current_attempt=" + v.currentAttempt() + ")");
        }
        if (!"ERROR".equals(v.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_transition",
                    "실패한 검증만 다시 실행할 수 있습니다 (현재 " + v.status() + ")");
        }
        List<String> resolutions = validations.currentErrorResolutions(v.id());
        if (resolutions.isEmpty() || resolutions.stream().anyMatch(r -> !"RETRY".equals(r))) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_transition",
                    "이 실패는 다시 실행해도 해결되지 않습니다. 근거·지원 범위를 보완한 뒤 새 검증을 요청하세요");
        }
        if (task.getStatus().isClosed()) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_transition", "완료·취소된 작업의 검증은 다시 실행할 수 없습니다");
        }
        if (validations.hasNewerValidation(v.taskId(), v.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_transition",
                    "이 작업에 더 최근 검증이 있습니다. 가장 최근 검증을 확인하세요");
        }
        designLookup.requireCurrentBaseline(projectId);
        Optional<String> changed = applier.inputChange(v);
        if (changed.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                    "검증한 뒤 계획이 바뀌었습니다 (" + changed.get() + "). 새 검증을 요청하세요");
        }
        for (Map<String, Object> ref : validations.designRefs(v.id())) {
            Long item = ((Number) ref.get("design_item_id")).longValue();
            Long version = ((Number) ref.get("design_item_version_id")).longValue();
            Optional<Long> current = designLookup.currentVersionIdOf(projectId, item);
            if (current.isEmpty() || !current.get().equals(version)) {
                throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                        "참조한 설계 항목이 바뀌었습니다 (design_item_id=" + item + "). 새 검증을 요청하세요");
            }
        }
        ValidationAcceptService.requireAttemptsLeft(validations, properties, v.taskId(), v.inputSnapshotHash());

        int nextAttempt = v.currentAttempt() + 1;
        Map<String, Object> summary = responses.summary(v.summary());
        for (String key : ATTEMPT_KEYS) {
            summary.remove(key);
        }
        summary.put("attempt", nextAttempt);
        summary.put("request_actor", ValidationAcceptService.requestActor(caller));
        if (validations.reopenForRetry(v.id(), v.currentAttempt(), summary) != 1) {
            // 잠근 뒤 확인했으므로 오지 않는다. 오면 다른 경로가 잠금 없이 바꾼 것이니 아무것도 남기지 않는다
            throw new IllegalStateException("validation " + v.id() + " changed while locked");
        }
        // 새 시도의 실행을 원문·검색 조회 전에 확보한다 (공통 "검증 실행 오류 규약"). 리스는 워커가 꺼낼 때 적는다
        validations.insertRun(v.id(), nextAttempt, null, "RUNNING");
        if (validations.requeueJob(v.id()) == 0) {
            validations.insertJob(v.id());
        }
        return responses.toResponse(validations.find(projectId, v.id()).orElseThrow());
    }
}
