package com.beez.backend.meeting.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.meeting.dto.MeetingRef;
import com.beez.backend.meeting.repository.MeetingRepository;
import com.beez.backend.meeting.repository.MeetingRepository.ConflictFinding;
import com.beez.backend.meeting.repository.MeetingRepository.TaskState;
import com.beez.backend.meeting.service.MeetingOpenService.ConflictMeetingCommand;
import com.beez.backend.validation.event.ConflictFindingsSaved;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검증 결과가 커밋된 뒤 작업 충돌 회의를 연다 (S15P31A108-248). 커밋이 끝난 트랜잭션에는 참여할 수 없으므로
 * 새 트랜잭션(REQUIRES_NEW)에서 {@link MeetingOpenService#openForConflict} 를 부른다 — MEETING_OPENED 알림도 그 안에서 저장된다.
 * 실패해도 이미 커밋된 검증 결과에는 영향이 없다: 예외는 여기서 삼키고 로그만 남기며, 다음 재검증에서 다시 시도된다.
 * 검증 워커 스레드에서 도는 동기 처리다(템플릿 문구라 DB 작업뿐). 비비 브리핑을 AI 로 만들게 되면 그때 비동기로 뺀다.
 */
@Component
class ConflictMeetingListener {

    private static final Logger log = LoggerFactory.getLogger(ConflictMeetingListener.class);

    private final MeetingOpenService opener;
    private final MeetingRepository meetings;
    private final TransactionTemplate tx;

    ConflictMeetingListener(MeetingOpenService opener, MeetingRepository meetings, PlatformTransactionManager manager) {
        this.opener = opener;
        this.meetings = meetings;
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ConflictFindingsSaved event) {
        try {
            tx.executeWithoutResult(status -> open(event));
        } catch (ApiException e) {
            if ("baseline_missing".equals(e.getCode())) {
                log.info("기준 설계 이전이라 충돌 회의를 열지 않음 project={} validation={}", event.projectId(), event.validationId());
            } else {
                log.warn("충돌 회의 개설 실패 project={} validation={}", event.projectId(), event.validationId(), e);
            }
        } catch (RuntimeException e) {
            log.warn("충돌 회의 개설 실패 project={} validation={}", event.projectId(), event.validationId(), e);
        }
    }

    /** 같은 쌍의 충돌 메시지를 모두 담는다(중복·빈 문구 제외). 하나도 없으면 템플릿 문구 */
    static String brief(String summary, List<String> messages) {
        List<String> distinct = messages.stream().filter(m -> m != null && !m.isBlank()).map(String::strip).distinct().toList();
        if (distinct.isEmpty()) {
            return summary + ". 어느 쪽을 따를지 정해 주세요.";
        }
        if (distinct.size() == 1) {
            return distinct.get(0);
        }
        StringBuilder text = new StringBuilder(summary).append(" (").append(distinct.size()).append("가지)");
        for (int i = 0; i < distinct.size(); i++) {
            text.append('\n').append(i + 1).append(". ").append(distinct.get(i));
        }
        return text.toString();
    }

    private void open(ConflictFindingsSaved event) {
        TreeSet<Long> ids = new TreeSet<>(event.relatedTaskIds());
        ids.add(event.taskId());
        Map<Long, TaskState> states = meetings.taskStates(event.projectId(), ids);
        for (ConflictPairs.Pair pair : ConflictPairs.select(event.taskId(), event.relatedTaskIds(), states)) {
            String summary = "'" + pair.task().title() + "' 와 '" + pair.related().title() + "' 가 서로 충돌해요";
            List<ConflictFinding> findings = meetings.conflictFindings(event.projectId(), event.validationId(),
                    pair.task().id(), pair.related().id(), event.analyzedRelatedPlans().get(pair.related().id()));
            String brief = brief(summary, findings.stream().map(ConflictFinding::message).toList());
            List<MeetingRef> refs = new ArrayList<>();
            refs.add(new MeetingRef("TASK", String.valueOf(pair.task().id()), pair.task().title()));
            refs.add(new MeetingRef("TASK", String.valueOf(pair.related().id()), pair.related().title()));
            opener.openForConflict(new ConflictMeetingCommand(event.projectId(),
                    List.of(pair.task().id(), pair.related().id()), summary, brief, refs, List.of(),
                    ConflictGuidanceBuilder.build(event.validationId(), findings)));
        }
    }
}
