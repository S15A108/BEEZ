package com.beez.backend.notification;

import java.time.Instant;

/**
 * 알림 한 건 (명세 00-common Notification 스키마). ID 는 십진 문자열.
 * documentId 는 DOCUMENT_VERSION 일 때만, readAt 은 읽은 알림만 있다(없으면 응답에서 빠진다 — non_null).
 */
public record NotificationResponse(String id, String projectId, String type, String title, String body,
                                   String targetType, String targetId, String documentId, Instant createdAt,
                                   Instant readAt) {
}
