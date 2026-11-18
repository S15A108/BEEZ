package com.beez.backend.common.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/**
 * 영속성 컨텍스트를 Spring 예외 변환을 거쳐 flush 한다 (S15P31A108-308).
 *
 * <p>{@code @Service} 에서 EntityManager 네이티브 쿼리를 실행하면 실행 전 auto-flush 에서 난 도메인 쪽 제약 위반·{@code @Version} 충돌이
 * Hibernate·JPA 예외 그대로 올라가 공용 처리기의 400·409 를 비켜 간다. 네이티브 쿼리 직전에 이걸 부르면 도메인 변경은 여기서
 * {@code DataIntegrityViolationException}·{@code ObjectOptimisticLockingFailureException} 으로 나가고, 뒤따르는 쿼리 자체의
 * 위반(서버 버그)은 원형 그대로 500 이 된다.
 */
@Repository
public class PersistenceContextFlusher {

    private final EntityManager em;

    public PersistenceContextFlusher(EntityManager em) {
        this.em = em;
    }

    public void flush() {
        em.flush();
    }
}
