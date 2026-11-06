package com.beez.backend.activity;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.auth.CallerKind;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 활동(타임라인) 입구 (S15P31A108-182, 계약 docs/design/10-notification-activity-contract.md 2·4절).
 *
 * <p><b>호출 규칙.</b> 알림 입구({@code NotificationRequester})와 같다. 도메인 트랜잭션 <b>안에서</b> 부른다(Propagation.MANDATORY —
 * 밖에서 부르면 예외). 트랜잭션이 없는 곳은 {@code TransactionTemplate} 으로 짧은 쓰기 트랜잭션을 열고 부른다.
 * {@code readOnly} 트랜잭션 안, {@code @TransactionalEventListener(AFTER_COMMIT)} 에서는 부르지 않는다.
 *
 * <ul>
 *   <li>사람·에이전트 행위: {@link #record(CallerContext, ActivityEvent)} — 서비스가 받은 {@code CallerContext} 를 그대로 넘긴다</li>
 *   <li>비비·시스템 행위: {@link #recordBy(SystemActor, ActivityEvent)}</li>
 * </ul>
 *
 * <p><b>저장 규칙.</b> 같은 키가 이미 있으면 조용히 건너뛴다(처음 것 유지, 내용이 달라도). {@code ON CONFLICT (dedupe_key) DO NOTHING}.
 * {@code occurred_at} 은 {@code clock_timestamp()}(실제 저장 시각). 저장은 {@link ActivityEventWriter} 의 네이티브 쿼리라 실행 전에
 * 영속성 컨텍스트가 flush 된다 — 도메인 쪽 제약 위반은 커밋 때가 아니라 이 호출에서 난다. 그 예외는 Spring 예외로 바뀌어 나간다
 * (제약 위반 {@code DataIntegrityViolationException}, 버전 충돌 {@code ObjectOptimisticLockingFailureException}, -308).
 */
@Service
public class ActivityRecorder {

    private final ActivityEventWriter writer;

    public ActivityRecorder(ActivityEventWriter writer) {
        this.writer = writer;
    }

    /** 사람·에이전트 행위. 행위 계정은 {@code caller.accountId()}(에이전트면 토큰 주인). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CallerContext caller, ActivityEvent event) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(caller.kind(), "caller.kind");
        Objects.requireNonNull(caller.accountId(), "caller.accountId");
        if (caller.kind() == CallerKind.AGENT && caller.agentTokenId() == null) {
            // 토큰이 없으면 세션(-178)을 이을 근거가 없어 에이전트 필터에서 영구히 빠진다
            throw new IllegalArgumentException("agent caller must carry agentTokenId: " + event.eventKey());
        }
        // TODO(-178): 에이전트면 caller.agentTokenId() 의 현재 연결 세션을 agent_session_id 로. 세션 기록이 생기기 전에는 비운다(계약 4절)
        insert(caller.kind().name(), caller.accountId(), null, event);
    }

    /** 비비·시스템 행위. 행위 계정은 비운다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBy(SystemActor actor, ActivityEvent event) {
        Objects.requireNonNull(actor, "actor");
        insert(actor.name(), null, null, event);
    }

    private void insert(String actorKind, Long actorAccountId, Long agentSessionId, ActivityEvent e) {
        Objects.requireNonNull(e, "event");
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            // PostgreSQL 이 INSERT 를 거부하기 전에 원인을 분명히 알린다 (계약 2.1)
            throw new IllegalStateException("ActivityRecorder must not be called inside a readOnly transaction");
        }
        writer.insert(actorKind, actorAccountId, agentSessionId, e);
    }
}
