package com.beez.backend.notification;

import java.time.Instant;

/**
 * 알림 한 건 (명세 00-common Notification 스키마). ID 는 십진 문자열.
 * documentId 는 DOCUMENT_VERSION 일 때만, baselineVersionNo 는 BASELINE 일 때만, readAt 은 읽은 알림만 있다(없으면 응답에서 빠진다 — non_null).
 * baselineVersionNo 는 저장하지 않고 조회할 때 기준 ID(targetId)로 찾는다 — 기준 화면·조회 API 가 버전 번호로 열기 때문이다.
 */
public record NotificationResponse(String id, String projectId, String type, String title, String body,
                                   String targetType, String targetId, String documentId, Integer baselineVersionNo,
                                   Instant createdAt, Instant readAt) {
}
