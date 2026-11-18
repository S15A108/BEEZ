package com.beez.backend.relation.dto;

/** 명세 00-common DependencyCandidate. AI 등이 추천한 미확정 후보. confirmed 는 항상 false */
public record DependencyCandidateResponse(String fromDesignItemId, String toDesignItemId, String reason,
                                          boolean confirmed) {
}
