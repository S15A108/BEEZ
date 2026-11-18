package com.beez.backend.designchange.dto;

/**
 * 명세 00-common ChangedItem. 항목 하나의 전후 버전.
 * 신규 항목은 before 가 없고(생략), 폐기 항목은 after 가 없다(생략). 둘 다 있으면 수정이다.
 */
public record ChangedItemResponse(String designItemId, String beforeVersionId, String afterVersionId) {
}
