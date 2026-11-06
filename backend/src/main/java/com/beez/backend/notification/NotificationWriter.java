package com.beez.backend.notification;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/**
 * 알림 저장 실행부. {@link NotificationRequester} 만 부른다 — 호출 규칙(MANDATORY·readOnly 거부)·수신자 거르기·길이 자르기는 입구가 맡는다.
 *
 * <p>{@code @Repository} 인 이유(S15P31A108-308): 네이티브 쿼리는 실행 전에 영속성 컨텍스트를 auto-flush 하므로 도메인 쪽 제약 위반·
 * {@code @Version} 충돌이 여기서 난다. {@code @Service} 에서 실행하면 Spring 예외 변환을 거치지 않아 Hibernate·JPA 예외 그대로 올라가
 * 공용 처리기의 {@code DataIntegrityViolationException}(400) 처리를 비켜 간다(500).
 */
@Repository
class NotificationWriter {

    private final EntityManager em;

    NotificationWriter(EntityManager em) {
        this.em = em;
    }

    /** 같은 키가 이미 있으면 조용히 건너뛴다 ({@code ON CONFLICT (dedupe_key) DO NOTHING}). 제목·본문은 자른 값을 받는다. */
    void insert(NotificationRequest r, String title, String body) {
        em.createNativeQuery("""
                        insert into notification (project_id, recipient_account_id, type, title, body, ref_kind, ref_id,
                                                  document_id, dedupe_key, created_at, updated_at)
                        values (:projectId, :recipient, :type, :title, :body, :refKind, :refId,
                                cast(:documentId as bigint), :key, clock_timestamp(), clock_timestamp())
                        on conflict (dedupe_key) do nothing
                        """)
                .setParameter("projectId", r.projectId())
                .setParameter("recipient", r.recipientAccountId())
                .setParameter("type", r.type())
                .setParameter("title", title)
                .setParameter("body", body)
                .setParameter("refKind", r.targetType().name())
                .setParameter("refId", r.targetId())
                .setParameter("documentId", r.documentId())
                .setParameter("key", r.notificationKey())
                .executeUpdate();
    }
}
