package com.beez.backend.presence;

import java.time.Instant;

/** 한 멤버의 마지막 보고. zone·seat·panel 은 위치 정보라 로그에 찍지 않는다 (toString 에서 뺀다). */
record PresenceEntry(Instant lastSeenAt, String zone, String seat, String panel) {

    @Override
    public String toString() {
        return "PresenceEntry[lastSeenAt=" + lastSeenAt + "]";
    }
}
