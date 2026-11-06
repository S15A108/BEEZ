package com.beez.backend.bee.dto;

/** BEE-A03 요청. rating 은 HELPFUL 또는 WRONG_SOURCE. 검증은 서비스에서 한다 */
public record BeeFeedbackRequest(String rating, String comment) {
}
