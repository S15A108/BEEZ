package com.beez.backend.activity;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/**
 * 활동 저장 실행부. {@link ActivityRecorder} 만 부른다 — 호출 규칙(MANDATORY·readOnly 거부)과 검증은 입구가 맡는다.
 *
 * <p>{@code @Repository} 인 이유(S15P31A108-308): 네이티브 쿼리는 실행 전에 영속성 컨텍스트를 auto-flush 하므로 도메인 쪽 제약 위반·
 * {@code @Version} 충돌이 여기서 난다. {@code @Service} 에서 실행하면 Spring 예외 변환을 거치지 않아 Hibernate·JPA 예외 그대로 올라가
 * 공용 처리기의 {@code DataIntegrityViolationException}(400) 처리를 비켜 간다(500).
 */
@Repository
class ActivityEventWriter {

    private final EntityManager em;

    ActivityEventWriter(EntityManager em) {
        this.em = em;
    }

    /** 같은 키가 이미 있으면 조용히 건너뛴다 ({@code ON CONFLICT (dedupe_key) DO NOTHING}). */
    void insert(String actorKind, Long actorAccountId, Long agentSessionId, ActivityEvent e) {
        em.createNativeQuery("""
                        insert into activity_event (project_id, actor_kind, actor_id, agent_session_id, event_type,
                                                    ref_kind, ref_id, task_id, change_proposal_id, baseline_id, result,
                                                    dedupe_key, occurred_at, created_at, updated_at)
                        values (:projectId, :actorKind, cast(:actorId as bigint), cast(:agentSessionId as bigint), :type,
                                :refKind, :refId, cast(:taskId as bigint), cast(:changeProposalId as bigint),
                                cast(:baselineId as bigint), :result,
                                :key, clock_timestamp(), clock_timestamp(), clock_timestamp())
                        on conflict (dedupe_key) do nothing
                        """)
                .setParameter("projectId", e.projectId())
                .setParameter("actorKind", actorKind)
                .setParameter("actorId", actorAccountId)
                .setParameter("agentSessionId", agentSessionId)
                .setParameter("type", e.type())
                .setParameter("refKind", e.targetType().name())
                .setParameter("refId", e.targetId())
                .setParameter("taskId", e.taskId())
                .setParameter("changeProposalId", e.changeProposalId())
                .setParameter("baselineId", e.baselineId())
                .setParameter("result", e.result().name())
                .setParameter("key", e.eventKey())
                .executeUpdate();
    }
}
