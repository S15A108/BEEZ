package com.beez.backend.task.dto;

import com.beez.backend.task.entity.TaskEvent;
import com.beez.backend.task.entity.WaitingReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * 명세 00-common TaskStateInput / TSK-A07 요청. ID 는 십진 문자열 (00-common 3절).
 *
 * @param event              START | RESUME | WAIT | COMPLETED | CANCEL
 * @param planRevisionId     현재 계획. 현재 리비전과 다르면 409 revision_conflict
 * @param expectedLockVersion 조회한 lock_version
 * @param validationId       START/RESUME 필수
 * @param waitingReason      WAIT 필수. INPUT|APPROVAL|DEPENDENCY 만. REVALIDATION 은 400
 * @param summary            진행/완료 요약
 * @param delta              완료 시 계획 대비 변경. 비어 있지 않으면 WAITING(REVALIDATION)
 * @param completionChecks   완료 기준 확인 [{criterion, met}]
 * @param changedDesignItemIds COMPLETED 에서만. 실제로 바꾼 설계 항목. 계획과 대조해 범위 이탈 후보를 보여 준다 (완료는 막지 않음).
 *                           보내지 않으면 대조하지 않는다. 다른 이벤트에 보내면 400
 */
public record TaskStateInput(
        @NotNull TaskEvent event,
        @NotNull String planRevisionId,
        @NotNull Integer expectedLockVersion,
        String validationId,
        WaitingReason waitingReason,
        String summary,
        List<@NotNull Map<String, Object>> delta,
        List<@NotNull @Valid CompletionCheck> completionChecks,
        @Size(max = 200) List<@NotNull String> changedDesignItemIds
) {
}
