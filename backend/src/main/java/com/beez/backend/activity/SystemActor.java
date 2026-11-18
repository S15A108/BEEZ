package com.beez.backend.activity;

/**
 * 사람·에이전트가 아닌 행위자 (계약 10-notification-activity-contract.md 2.1절). DB {@code activity_event.actor_kind}.
 *
 * <p>{@code common.access.ActorType}(HUMAN·AGENT)은 "요청을 보낸 주체"라는 뜻이라 여기에 BEE·SYSTEM 을 넣어 넓히지 않는다.
 * 사람·에이전트 행위는 {@code CallerContext} 로 {@link ActivityRecorder#record} 를 부른다.
 */
public enum SystemActor {
    /** 비비가 한 일 (회의 열기·문서 검토 등) */
    BEE,
    /** 사람·에이전트 행위가 아니라 시스템 판단으로 생긴 변화 (검증 결과에 따른 전이 등) */
    SYSTEM
}
