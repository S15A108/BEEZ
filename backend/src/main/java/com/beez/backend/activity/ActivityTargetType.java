package com.beez.backend.activity;

/**
 * 활동의 주 대상 종류 (계약 4절: 알림 대상 3절 값 + DOCUMENT·PROJECT·MEMBER·AGENT_SESSION). DB {@code activity_event.ref_kind}.
 *
 * <p>{@code notification.NotificationTargetType} 을 넓혀 쓰지 않는다 — 그 값은 "알림을 누르면 갈 화면"이라 문서·멤버·세션이 들어가면
 * 알림 쪽에서 갈 곳 없는 값이 허용된다.
 */
public enum ActivityTargetType {
    TASK,
    VALIDATION,
    CHANGE_PROPOSAL,
    BASELINE,
    MEETING,
    DOCUMENT_VERSION,
    DOCUMENT,
    PROJECT,
    MEMBER,
    AGENT_SESSION
}
