package com.beez.backend.validation.event;

import java.util.List;

/**
 * 검증 결과가 저장된 뒤 발행한다: kind=CONFLICT, severity=BLOCKING, related_task_id 가 있는 발견 항목이 있다는 알림
 * (S15P31A108-248). 담당자·상태 판정은 받는 쪽(회의)이 한다. relatedTaskIds 는 중복 없이 오름차순.
 */
public record ConflictFindingsSaved(long projectId, long validationId, long taskId, List<Long> relatedTaskIds) {
}
