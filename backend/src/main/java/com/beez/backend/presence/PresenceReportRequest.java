package com.beez.backend.presence;

/**
 * MON-A06 요청. 모두 선택이고 본문 없이 보내면 "살아 있다"는 신호만 된다. 마지막 보고로 통째로 덮어쓰므로 FE 는 매번 현재 값을 모두 보낸다.
 * activity({action, target_type, target_id}) 는 MVP 에서 저장하지 않는다 — 보내도 무시한다 (S15P31A108-386).
 */
public record PresenceReportRequest(String zone, String seat, String panel) {
}
