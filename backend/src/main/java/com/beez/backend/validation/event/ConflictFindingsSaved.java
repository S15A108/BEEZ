package com.beez.backend.validation.event;

import java.util.List;
import java.util.Map;

/**
 * 검증 결과가 저장된 뒤 발행한다: kind=CONFLICT, severity=BLOCKING, related_task_id 가 있는 발견 항목이 있다는 알림
 * (S15P31A108-248). 담당자·상태 판정은 받는 쪽(회의)이 한다. relatedTaskIds 는 중복 없이 오름차순.
 * analyzedRelatedPlans 는 의미 분석 요청이 비교한 작업별 계획 리비전이다. 규칙 검사는 검증 접수 기록을 사용한다.
 */
public record ConflictFindingsSaved(long projectId, long validationId, long taskId, List<Long> relatedTaskIds,
                                    Map<Long, Long> analyzedRelatedPlans) {
    public ConflictFindingsSaved(long projectId, long validationId, long taskId, List<Long> relatedTaskIds) {
        this(projectId, validationId, taskId, relatedTaskIds, Map.of());
    }

    public ConflictFindingsSaved {
        relatedTaskIds = List.copyOf(relatedTaskIds);
        analyzedRelatedPlans = Map.copyOf(analyzedRelatedPlans);
    }
}
