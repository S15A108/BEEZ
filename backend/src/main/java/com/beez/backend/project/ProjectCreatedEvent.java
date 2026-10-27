package com.beez.backend.project;

/**
 * 프로젝트가 만들어졌다는 알림. 트랜잭션 커밋 뒤에만 전달된다 (ProjectCreatedListener).
 * 비비 초기화(PRJ-A01 처리 규칙, 박상은 S15P31A108-135)는 이 이벤트를 받아 붙인다. AI 가 실패해도 프로젝트 생성은 되돌리지 않는다.
 */
public record ProjectCreatedEvent(Long projectId, Long ownerAccountId) {
}
