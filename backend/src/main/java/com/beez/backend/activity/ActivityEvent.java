package com.beez.backend.activity;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 활동 이벤트 한 건 (계약 docs/design/10-notification-activity-contract.md 4절). 행위자는 여기 넣지 않는다 —
 * {@link ActivityRecorder} 가 {@code CallerContext} 또는 {@link SystemActor} 로 채운다.
 *
 * <p>형식 검사는 여기서 한다. 사용자 입력이 아니라 호출 코드 버그라 도메인 처리도 함께 롤백된다(계약 2.2).
 * 필수 값이 null 이면 {@link NullPointerException}, 키·유형 형식이나 보조 참조가 어긋나면 {@link IllegalArgumentException} 이다.
 *
 * <p><b>보조 참조.</b> 주 대상이 TASK·CHANGE_PROPOSAL·BASELINE 이면 같은 보조 참조(taskId 등)를 비워 두어도 targetId 로 채운다
 * (계약은 "주 대상과 같아도 넣는다" — 빠뜨리면 그 활동이 필터에서 조용히 빠지므로 여기서 채운다). 값을 넣었는데 targetId 와 다르면 예외다.
 *
 * <pre>{@code
 * ActivityEvent.of("validation:88:finished:2", projectId, "VALIDATION_FINISHED", ActivityTargetType.VALIDATION, 88L)
 *         .withTaskId(501L)
 *         .withResult(ActivityResult.FAILURE);
 * }</pre>
 *
 * @param eventKey         {@code {대상}:{ID}:{동작}[:구분]}. 도메인 사실만으로 정해지게(난수·현재 시각 금지). DB dedupe_key
 * @param type             {@code TASK_STARTED} 같은 유형(계약 6.2). 상수는 소유 도메인 코드에 둔다. DB event_type
 * @param targetType       주 대상 종류. DB ref_kind
 * @param targetId         주 대상 ID. DB ref_id
 * @param taskId           보조 참조(작업별 필터). 같은 프로젝트의 작업이어야 한다(DB FK)
 * @param changeProposalId 보조 참조(MON-A04 {@code change_proposal_id} 필터)
 * @param baselineId       보조 참조
 * @param result           null 이면 {@link ActivityResult#SUCCESS}
 */
public record ActivityEvent(String eventKey, Long projectId, String type, ActivityTargetType targetType, Long targetId,
                            Long taskId, Long changeProposalId, Long baselineId, ActivityResult result) {

    /** 계약 5절. 알림 키({@code NotificationRequest})와 같은 형식이다. */
    static final Pattern KEY_FORMAT = Pattern.compile("^[a-z][a-z0-9-]*(:[A-Za-z0-9_-]+)+$");
    static final int KEY_MAX_LENGTH = 200;
    /** 계약 6절. {대상}_{과거형 동작} 대문자 스네이크 */
    static final Pattern TYPE_FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    public ActivityEvent {
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        if (eventKey.length() > KEY_MAX_LENGTH || !KEY_FORMAT.matcher(eventKey).matches()) {
            throw new IllegalArgumentException("activity event key does not match the contract format: " + eventKey);
        }
        if (!TYPE_FORMAT.matcher(type).matches()) {
            throw new IllegalArgumentException("activity type does not match the contract format: " + type);
        }
        taskId = sameAsTarget(ActivityTargetType.TASK, targetType, targetId, taskId, "taskId", eventKey);
        changeProposalId = sameAsTarget(ActivityTargetType.CHANGE_PROPOSAL, targetType, targetId, changeProposalId,
                "changeProposalId", eventKey);
        baselineId = sameAsTarget(ActivityTargetType.BASELINE, targetType, targetId, baselineId, "baselineId", eventKey);
        if (result == null) {
            result = ActivityResult.SUCCESS;
        }
    }

    /** 보조 참조 없이 성공 활동 하나. 필요한 보조 참조·결과는 {@code with…} 로 더한다. */
    public static ActivityEvent of(String eventKey, Long projectId, String type, ActivityTargetType targetType, Long targetId) {
        return new ActivityEvent(eventKey, projectId, type, targetType, targetId, null, null, null, null);
    }

    public ActivityEvent withTaskId(Long taskId) {
        return new ActivityEvent(eventKey, projectId, type, targetType, targetId, taskId, changeProposalId, baselineId, result);
    }

    public ActivityEvent withChangeProposalId(Long changeProposalId) {
        return new ActivityEvent(eventKey, projectId, type, targetType, targetId, taskId, changeProposalId, baselineId, result);
    }

    public ActivityEvent withBaselineId(Long baselineId) {
        return new ActivityEvent(eventKey, projectId, type, targetType, targetId, taskId, changeProposalId, baselineId, result);
    }

    public ActivityEvent withResult(ActivityResult result) {
        return new ActivityEvent(eventKey, projectId, type, targetType, targetId, taskId, changeProposalId, baselineId, result);
    }

    private static Long sameAsTarget(ActivityTargetType refType, ActivityTargetType targetType, Long targetId, Long ref,
                                     String name, String eventKey) {
        if (targetType != refType) {
            return ref;
        }
        if (ref != null && !ref.equals(targetId)) {
            throw new IllegalArgumentException(name + " must equal targetId when targetType is " + refType + ": " + eventKey);
        }
        return targetId;
    }
}
