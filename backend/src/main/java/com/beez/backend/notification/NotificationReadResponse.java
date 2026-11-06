package com.beez.backend.notification;

/** NTF-A02 응답. 이미 읽은 알림은 세지 않는다 — 같은 요청을 다시 보내면 0. */
public record NotificationReadResponse(int updatedCount) {
}
