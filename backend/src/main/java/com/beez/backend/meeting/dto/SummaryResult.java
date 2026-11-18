package com.beez.backend.meeting.dto;

import java.util.List;

/**
 * 검증·정규화한 정리 내용. {@code meeting_summary.result} 에 snake_case JSON 으로 저장하고 응답에도 그대로 쓴다.
 * 모든 ID 는 십진 문자열이다.
 */
public record SummaryResult(List<String> conflicts, List<Decision> decisions, List<DocumentChange> documentChanges,
                            List<ActionItem> actionItems) {

    /** 결정. AI 가 정한 사람을 주지 않으면 decidedByMemberId 는 없다 */
    public record Decision(String text, String decidedByMemberId) {
    }

    public record Locator(String type, int startLine, int endLine) {
    }

    /** 바뀔 문서와 줄. before 는 그 줄 범위의 원문과 정확히 같다 */
    public record DocumentChange(String documentId, String baseVersionId, Locator locator, String before, String after,
                                 List<String> evidenceEntryIds) {
    }

    public record ActionItem(String text, String memberId) {
    }
}
