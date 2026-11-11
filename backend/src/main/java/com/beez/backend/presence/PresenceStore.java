package com.beez.backend.presence;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 멤버별 마지막 보고를 프로세스 메모리에 둔다 (S15P31A108-386).
 *
 * <ul>
 *   <li>서버 1대 기준이고 재시작하면 모두 "접속한 적 없음"으로 돌아간다 — Q04·Q05 의 메모리 한도와 같은 가정이다.
 *       여러 대로 늘리면 DB 로 옮겨야 한다.</li>
 *   <li>TTL 이 지나도 항목을 지우지 않는다. 오프라인 응답이 마지막 위치와 마지막 접속 시각을 그대로 줘야 해서다.
 *       online 은 읽는 시점에 계산한다. 지우는 때는 정리 이벤트({@link #removeMemberAfterCommit}·{@link #removeProjectAfterCommit})와
 *       보존 기간 경과({@link #purge})뿐이다.</li>
 *   <li>키는 (프로젝트, 멤버)다. 다른 프로젝트의 같은 계정과 섞이지 않는다.</li>
 * </ul>
 */
@Component
public class PresenceStore {

    private record Key(long projectId, long memberId) {
    }

    private final Map<Key, PresenceEntry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final PresenceProperties properties;

    @Autowired
    public PresenceStore(PresenceProperties properties) {
        this(properties, Clock.systemUTC());
    }

    PresenceStore(PresenceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 마지막 값으로 통째로 덮어쓴다 (PUT). 같은 값을 다시 보내도 시각만 갱신되어 멱등이다. */
    void report(long projectId, long memberId, String zone, String seat, String panel) {
        entries.put(new Key(projectId, memberId), new PresenceEntry(clock.instant(), zone, seat, panel));
    }

    Optional<PresenceEntry> find(long projectId, long memberId) {
        return Optional.ofNullable(entries.get(new Key(projectId, memberId)));
    }

    /** 마지막 보고가 TTL 안이면 online. */
    boolean isOnline(PresenceEntry entry) {
        return entry.lastSeenAt().plus(properties.ttl()).isAfter(clock.instant());
    }

    /**
     * 멤버십이 끝나면(나가기·제외·계정 삭제) 위치를 지운다. 커밋 뒤에 지운다 — 커밋 전에 지우면 아직 멤버인 그 사람의 다음 보고가
     * 다시 채워, 멤버십이 끝난 뒤에도 옛 위치가 남는다. 롤백되면 지우지 않는다.
     */
    public void removeMemberAfterCommit(long projectId, long memberId) {
        afterCommit(() -> entries.remove(new Key(projectId, memberId)));
    }

    /** 프로젝트가 보관(삭제)되면 그 프로젝트의 위치를 모두 지운다. */
    public void removeProjectAfterCommit(long projectId) {
        afterCommit(() -> entries.keySet().removeIf(k -> k.projectId() == projectId));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 보존 기간이 지난 항목을 뺀다. 접속하지 않는 사람의 항목이 메모리에 영원히 쌓이지 않게 한다. */
    @Scheduled(fixedDelayString = "${beez.presence.purge-millis:600000}")
    public void purge() {
        Instant cutoff = clock.instant().minus(properties.retention());
        entries.values().removeIf(e -> e.lastSeenAt().isBefore(cutoff));
    }

    /** 시험용. */
    int size() {
        return entries.size();
    }
}
