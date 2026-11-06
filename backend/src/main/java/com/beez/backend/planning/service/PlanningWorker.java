package com.beez.backend.planning.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 질문 생성(133)과 설계 초안 생성(134)을 주기적으로 처리한다. 두 작업은 AI 응답을 오래 기다릴 수 있어 서로 막지 않도록
 * 스케줄 메서드를 따로 둔다(스케줄 스레드 풀은 application.yml). 시험은 워커를 끄고 {@code runOnce} 를 직접 부른다.
 */
@Component
public class PlanningWorker {
    private static final Logger log = LoggerFactory.getLogger(PlanningWorker.class);
    static final int BATCH = 5;

    private final PlanningQuestionGenerator questions;
    private final DesignDraftRunner drafts;
    private final boolean enabled;

    public PlanningWorker(PlanningQuestionGenerator questions, DesignDraftRunner drafts,
                          @Value("${beez.planning.worker-enabled:true}") boolean enabled) {
        this.questions = questions; this.drafts = drafts; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${beez.planning.poll-millis:3000}")
    public void pollQuestions() {
        if (!enabled) return;
        try {
            for (int i = 0; i < BATCH && questions.runOnce(); i++) { /* 한 번에 몇 건까지만 처리한다 */ }
        } catch (RuntimeException e) {
            log.error("기획 보완 질문 생성 중 오류가 발생했습니다. 예외 종류={}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${beez.planning.poll-millis:3000}")
    public void pollDrafts() {
        if (!enabled) return;
        try {
            for (int i = 0; i < BATCH && drafts.runOnce(); i++) { /* 한 번에 몇 건까지만 처리한다 */ }
        } catch (RuntimeException e) {
            log.error("설계 초안 생성 중 오류가 발생했습니다. 예외 종류={}", e.getClass().getSimpleName());
        }
    }
}
