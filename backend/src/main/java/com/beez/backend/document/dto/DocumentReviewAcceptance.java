package com.beez.backend.document.dto;

/**
 * DOC-A16 접수 결과(S15P31A108-391). 같은 키로 이미 끝난 검토면 {@code review}(201), 접수했거나 아직 도는 중이면
 * {@code request}(202, 작업본 조회의 {@code bee_review_request} 와 같은 모양) 중 하나만 있다.
 */
public record DocumentReviewAcceptance(DocumentBeeReviewResponse review, BeeReviewRequestResponse request) {
    public static DocumentReviewAcceptance completed(DocumentBeeReviewResponse review) { return new DocumentReviewAcceptance(review, null); }
    public static DocumentReviewAcceptance running(BeeReviewRequestResponse request) { return new DocumentReviewAcceptance(null, request); }
}
