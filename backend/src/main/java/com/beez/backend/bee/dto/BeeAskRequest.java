package com.beez.backend.bee.dto;

/**
 * BEE-A01 요청. 검증은 서비스에서 한다.
 * scope 는 APPROVED(기본) 또는 INCLUDE_DRAFTS — 후자는 아직 지원하지 않아 400 으로 거절한다(S15P31A108-344 D1).
 */
public record BeeAskRequest(String question, String taskId, String scope) {
}
