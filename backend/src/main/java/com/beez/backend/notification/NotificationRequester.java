package com.beez.backend.notification;

import com.beez.backend.project.ProjectMemberRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 알림 입구 (S15P31A108-181, 계약 docs/design/10-notification-activity-contract.md 2절).
 *
 * <p><b>호출 규칙.</b> 도메인 트랜잭션 <b>안에서</b> 부른다(Propagation.MANDATORY — 밖에서 부르면 예외). 그래서 도메인 처리가 롤백되면
 * 알림도 남지 않고, 커밋과 저장 사이에 죽어 알림만 빠지는 일도 없다. 트랜잭션이 없는 곳(필터·어댑터·스케줄러)은
 * {@code TransactionTemplate} 으로 짧은 쓰기 트랜잭션을 열고 부른다. {@code readOnly} 트랜잭션 안에서는 부르지 않는다.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 에서도 부르지 않는다(BEFORE_COMMIT 또는 같은 트랜잭션 {@code @EventListener}).
 *
 * <p><b>저장 규칙.</b>
 * <ul>
 *   <li>같은 키가 이미 있으면 조용히 건너뛴다(처음 것 유지, 내용이 달라도). {@code ON CONFLICT (dedupe_key) DO NOTHING}</li>
 *   <li>수신자가 그 프로젝트의 활성 멤버가 아니면(나감·제외·탈퇴·보관) 그 수신자만 건너뛴다</li>
 *   <li>제목 100자·본문 500자를 넘으면 코드 포인트 기준으로 잘라(끝에 '…') 저장한다</li>
 * </ul>
 * 조회·저장은 모두 EntityManager(Hibernate)를 거친다. JdbcTemplate 은 같은 트랜잭션에서 JPA 로 바꾸고
 * 아직 flush 하지 않은 변경(방금 제외한 멤버십 등)을 보지 못한다. 저장({@link NotificationWriter}) 전 auto-flush 에서 난
 * 도메인 쪽 제약 위반·버전 충돌은 Spring 예외로 바뀌어 나간다(-308).
 */
@Service
public class NotificationRequester {

    static final int TITLE_MAX = 100;
    static final int BODY_MAX = 500;
    private static final String ELLIPSIS = "…";

    private final ProjectMemberRepository members;
    private final NotificationWriter writer;

    public NotificationRequester(ProjectMemberRepository members, NotificationWriter writer) {
        this.members = members;
        this.writer = writer;
    }

    /** 알림 한 건. 수신자가 비멤버이거나 키가 이미 있으면 아무것도 남기지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void request(NotificationRequest request) {
        requestAll(List.of(request));
    }

    /**
     * 여러 수신자에게 한 번에. 활성 멤버 확인은 프로젝트마다 조회 한 번({@code account_id IN (…)})이다.
     * 목록 안에 같은 키가 두 번 있어도 한 건만 남는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestAll(List<NotificationRequest> requests) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            // PostgreSQL 이 INSERT 를 거부하기 전에 원인을 분명히 알린다 (계약 2.1)
            throw new IllegalStateException("NotificationRequester must not be called inside a readOnly transaction");
        }
        Map<Long, List<NotificationRequest>> byProject = requests.stream()
                .collect(Collectors.groupingBy(NotificationRequest::projectId, LinkedHashMap::new, Collectors.toList()));
        byProject.forEach((projectId, group) -> {
            Set<Long> recipients = group.stream().map(NotificationRequest::recipientAccountId).collect(Collectors.toSet());
            Set<Long> active = new HashSet<>(members.activeAccountIdsAmong(projectId, recipients));
            for (NotificationRequest r : group) {
                if (active.contains(r.recipientAccountId())) {
                    insert(r);
                }
            }
        });
    }

    private void insert(NotificationRequest r) {
        writer.insert(r, truncate(r.title(), TITLE_MAX), truncate(r.body(), BODY_MAX));
    }

    /** 코드 포인트 max 개를 넘으면 max-1 개 + '…'. UTF-16 단위로 자르면 이모지가 반으로 갈린다. */
    static String truncate(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max - 1)) + ELLIPSIS;
    }
}
