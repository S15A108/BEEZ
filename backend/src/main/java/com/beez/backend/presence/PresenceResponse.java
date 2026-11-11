package com.beez.backend.presence;

import com.beez.backend.common.api.Ids;
import java.time.Instant;
import java.util.List;

/** MON-A07 응답. 값이 없는 필드는 전역 설정(non_null)으로 생략된다. */
public record PresenceResponse(List<Item> items) {

    /** 명세 00-common Presence. 접속한 적이 없으면 online=false 뿐이다. 오프라인이면 마지막 위치를 그대로 준다. */
    public record Item(String memberId, boolean online, Instant lastSeenAt, String zone, String seat, String panel) {

        static Item never(long memberId) {
            return new Item(Ids.str(memberId), false, null, null, null, null);
        }

        static Item of(long memberId, boolean online, PresenceEntry e) {
            return new Item(Ids.str(memberId), online, e.lastSeenAt(), e.zone(), e.seat(), e.panel());
        }
    }
}
