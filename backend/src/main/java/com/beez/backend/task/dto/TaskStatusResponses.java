package com.beez.backend.task.dto;

import java.util.List;

/** 작업·검증·에이전트 현황 응답 (S15P31A108-64, MON-T01·MON-V01·MON-A05). 명세 00-common 의 StateCount·WaitingCount·AgentWork */
public final class TaskStatusResponses {

    private TaskStatusResponses() {
    }

    /** 명세 StateCount — 그 API 가 집계하는 상태 하나의 건수 */
    public record StateCount(String status, int count) {
    }

    /** 명세 WaitingCount */
    public record WaitingCount(String waitingReason, int count) {
    }

    /** MON-T01 작업 상태 현황 */
    public record TaskSummary(List<StateCount> byStatus, List<WaitingCount> byWaitingReason) {
    }

    /** MON-V01 검증 상태 현황 */
    public record ValidationSummary(List<StateCount> byStatus) {
    }

    /**
     * 명세 AgentWork — 에이전트 세션 하나의 "명시 보고된" 현재 작업. 값이 없는 선택 필드는 응답에서 빠진다(전역 non_null).
     * last_summary 는 완료 보고의 summary 가 세션에 남지 않아 아직 비어 있다.
     */
    public record AgentWork(String memberId, String agentSessionId, String agentName, String currentTaskId, String taskTitle,
                            String taskStatus, String validationStatus, String waitingReason, String lastSummary) {
    }
}
