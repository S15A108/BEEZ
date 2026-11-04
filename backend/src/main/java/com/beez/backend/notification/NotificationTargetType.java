package com.beez.backend.notification;

/** 알림을 누르면 갈 곳 (계약 3절 target_type, DB notification.ref_kind). 값의 철자가 그대로 저장된다. */
public enum NotificationTargetType {
    TASK,
    VALIDATION,
    CHANGE_PROPOSAL,
    BASELINE,
    MEETING,
    /** 이 값이면 {@link NotificationRequest#documentId()} 가 필수다 */
    DOCUMENT_VERSION
}
